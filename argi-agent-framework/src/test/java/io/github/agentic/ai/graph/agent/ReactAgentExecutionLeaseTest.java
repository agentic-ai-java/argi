/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionLease;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.checkpoint.savers.MemoryLeasedCheckpointSaver;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ReactAgentExecutionLeaseTest {

	@Test
	void leasedAgentAcquiresLeaseBeforeVersionedSnapshot() throws Exception {
		RecordingLeasedSaver saver = new RecordingLeasedSaver();
		ReactAgent agent = ReactAgent.builder()
			.name("leased-simple-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new SingleAnswerModel()))
			.saver(saver)
			.build();
		RunnableConfig config = RunnableConfig.builder().threadId("leased-simple").build();

		List<NodeOutput> outputs = agent.stream("hello", config).collectList().block(Duration.ofSeconds(10));

		assertNotNull(outputs);
		assertTrue(saver.indexOf("acquireLease") < saver.indexOf("getVersioned"),
				"ReactAgent must acquire the execution lease before opening the versioned scope");
		assertTrue(saver.indexOf("getVersioned") < saver.lastIndexOf("releaseLease"),
				"lease release must stay outside the versioned scope lifetime");
	}

	@Test
	void cancellationRewindsBeforeLeaseRelease() throws Exception {
		RecordingLeasedSaver saver = new RecordingLeasedSaver();
		BlockingAfterToolModel model = new BlockingAfterToolModel();
		CountingTools tools = new CountingTools();
		ToolCallback count = ToolCallbacks.from(tools)[0];
		ReactAgent agent = ReactAgent.builder()
			.name("leased-cancel-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(count))
			.saver(saver)
			.build();
		RunnableConfig config = RunnableConfig.builder().threadId("leased-cancel").build();
		CountDownLatch terminated = new CountDownLatch(1);

		Disposable active = agent.stream("cancel-after-tool", config)
			.doFinally(signal -> terminated.countDown())
			.subscribe();
		assertTrue(model.awaitSecondCall(), "second model call must start after tool checkpoint");

		active.dispose();
		model.release();
		assertTrue(terminated.await(10, TimeUnit.SECONDS), "subscription must terminate after cancellation");

		assertEquals(1, tools.calls.get(), "tool side effect should occur once before cancellation");
		assertTrue(saver.lastIndexOf("putIfLeasedVersion") < saver.lastIndexOf("releaseLease"),
				"cancellation rewind must be attempted before lease release");
		Checkpoint checkpoint = saver.getVersioned(config).checkpoint().orElseThrow();
		assertEquals(Map.of(), checkpoint.getState());
		assertEquals(StateGraph.START, checkpoint.getNodeId());
		assertEquals(StateGraph.END, checkpoint.getNextNodeId());
	}

	@Test
	void coldSubscriptionOpensLeaseAndSnapshotOnlyOnSubscribe() throws Exception {
		RecordingLeasedSaver saver = new RecordingLeasedSaver();
		ReactAgent agent = ReactAgent.builder()
			.name("leased-cold-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new SingleAnswerModel()))
			.saver(saver)
			.build();
		RunnableConfig config = RunnableConfig.builder().threadId("leased-cold").build();

		Flux<NodeOutput> cold = agent.stream("hello", config);
		assertEquals(0, saver.count("acquireLease"));
		assertEquals(0, saver.count("getVersioned"));

		cold.collectList().block(Duration.ofSeconds(10));
		assertEquals(1, saver.count("acquireLease"));
		assertEquals(1, saver.count("getVersioned"));

		cold.collectList().block(Duration.ofSeconds(10));
		assertEquals(2, saver.count("acquireLease"));
		assertEquals(2, saver.count("getVersioned"));
	}

	@Test
	void cancellingQueuedLeasedTurnDoesNotAcquireLeaseOrReadSnapshot() throws Exception {
		RecordingLeasedSaver saver = new RecordingLeasedSaver();
		BlockingFirstModel model = new BlockingFirstModel();
		ReactAgent agent = ReactAgent.builder()
			.name("leased-queue-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.saver(saver)
			.build();
		RunnableConfig config = RunnableConfig.builder().threadId("leased-queue").build();
		CountDownLatch activeTerminated = new CountDownLatch(1);

		Disposable active = agent.stream("active", config)
			.doFinally(signal -> activeTerminated.countDown())
			.subscribe();
		assertTrue(model.awaitFirstCall(), "active subscription must enter the model before queueing another");
		int acquiresBeforeQueuedCancel = saver.count("acquireLease");
		int readsBeforeQueuedCancel = saver.count("getVersioned");

		Disposable queued = agent.stream("queued", config).subscribe();
		queued.dispose();

		assertEquals(acquiresBeforeQueuedCancel, saver.count("acquireLease"));
		assertEquals(readsBeforeQueuedCancel, saver.count("getVersioned"));
		active.dispose();
		model.release();
		assertTrue(activeTerminated.await(10, TimeUnit.SECONDS), "active subscription must terminate after release");
	}

	@Test
	void lostOwnerSkipsCancellationRewind() throws Exception {
		RecordingLeasedSaver saver = new RecordingLeasedSaver();
		BlockingAfterToolModel model = new BlockingAfterToolModel();
		CountingTools tools = new CountingTools();
		ToolCallback count = ToolCallbacks.from(tools)[0];
		ReactAgent agent = ReactAgent.builder()
			.name("leased-lost-owner-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(count))
			.saver(saver)
			.build();
		RunnableConfig config = RunnableConfig.builder().threadId("leased-lost-owner").build();
		CountDownLatch terminated = new CountDownLatch(1);

		Disposable active = agent.stream("cancel-after-tool", config)
			.doFinally(signal -> terminated.countDown())
			.subscribe();
		assertTrue(model.awaitSecondCall(), "second model call must start after tool checkpoint");
		saver.stealOwnershipAndWrite(config, Map.of("owner", "external"));

		active.dispose();
		model.release();
		assertTrue(terminated.await(10, TimeUnit.SECONDS), "subscription must terminate after cancellation");

		assertEquals(1, tools.calls.get(), "tool side effect should occur once before cancellation");
		Checkpoint checkpoint = saver.getVersioned(config).checkpoint().orElseThrow();
		assertEquals("external", checkpoint.getState().get("owner"));
	}

	private static ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static ChatResponse toolCallResponse() {
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "count", "{}");
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
	}

	private static String lastUserText(Prompt prompt) {
		return prompt.getInstructions()
			.stream()
			.filter(UserMessage.class::isInstance)
			.map(Message::getText)
			.reduce((first, second) -> second)
			.orElse("answer");
	}

	private static boolean hasToolResponse(Prompt prompt) {
		return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
	}

	private static final class SingleAnswerModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			return response("answer");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	private static final class BlockingAfterToolModel implements ChatModel {

		private final CountDownLatch secondCall = new CountDownLatch(1);

		private final Sinks.One<ChatResponse> blockedResponse = Sinks.one();

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("test uses streaming model calls");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			if ("cancel-after-tool".equals(lastUserText(prompt)) && !hasToolResponse(prompt)) {
				return Flux.just(toolCallResponse());
			}
			secondCall.countDown();
			return blockedResponse.asMono().flux();
		}

		private boolean awaitSecondCall() throws InterruptedException {
			return secondCall.await(10, TimeUnit.SECONDS);
		}

		private void release() {
			blockedResponse.tryEmitValue(response("released"));
		}

	}

	private static final class BlockingFirstModel implements ChatModel {

		private final CountDownLatch firstCall = new CountDownLatch(1);

		private final Sinks.One<ChatResponse> blockedResponse = Sinks.one();

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("test uses streaming model calls");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			firstCall.countDown();
			return blockedResponse.asMono().flux();
		}

		private boolean awaitFirstCall() throws InterruptedException {
			return firstCall.await(10, TimeUnit.SECONDS);
		}

		private void release() {
			blockedResponse.tryEmitValue(response("released"));
		}

	}

	private static final class CountingTools {

		private final AtomicInteger calls = new AtomicInteger();

		@Tool(description = "increment a counter")
		public String count() {
			return "count-" + calls.incrementAndGet();
		}

	}

	private static final class RecordingLeasedSaver implements LeasedCheckpointSaver {

		private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

		private final MemoryLeasedCheckpointSaver delegate = new MemoryLeasedCheckpointSaver(serializer(),
				LeaseOptions.defaults());

		private final AtomicReference<ExecutionLease> activeLease = new AtomicReference<>();

		@Override
		public LeaseOptions leaseOptions() {
			return delegate.leaseOptions();
		}

		@Override
		public ExecutionLease acquireLease(RunnableConfig config, UUID ownerId) throws Exception {
			events.add("acquireLease");
			ExecutionLease lease = delegate.acquireLease(config, ownerId);
			activeLease.set(lease);
			return lease;
		}

		@Override
		public ExecutionLease renewLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			events.add("renewLease");
			return delegate.renewLease(config, lease);
		}

		@Override
		public boolean releaseLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			events.add("releaseLease");
			return delegate.releaseLease(config, lease);
		}

		@Override
		public RunnableConfig putIfLeasedVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision,
				ExecutionLease lease) throws Exception {
			events.add("putIfLeasedVersion");
			return delegate.putIfLeasedVersion(config, checkpoint, expectedRevision, lease);
		}

		@Override
		public Tag releaseIfLeasedVersion(RunnableConfig config, long expectedRevision, ExecutionLease lease)
				throws Exception {
			events.add("releaseIfLeasedVersion");
			return delegate.releaseIfLeasedVersion(config, expectedRevision, lease);
		}

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			events.add("get");
			return delegate.get(config);
		}

		@Override
		public CheckpointSnapshot getVersioned(RunnableConfig config) {
			events.add("getVersioned");
			return delegate.getVersioned(config);
		}

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			events.add("put");
			return delegate.put(config, checkpoint);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			events.add("release");
			return delegate.release(config);
		}

		private int indexOf(String event) {
			return events.indexOf(event);
		}

		private int lastIndexOf(String event) {
			return events.lastIndexOf(event);
		}

		private int count(String event) {
			synchronized (events) {
				return (int) events.stream().filter(event::equals).count();
			}
		}

		private void stealOwnershipAndWrite(RunnableConfig config, Map<String, Object> state) throws Exception {
			ExecutionLease previous = activeLease.get();
			if (previous != null) {
				delegate.releaseLease(config, previous);
			}
			ExecutionLease winner = delegate.acquireLease(config, UUID.randomUUID());
			Checkpoint checkpoint = Checkpoint.builder()
				.state(state)
				.nodeId("external")
				.nextNodeId(StateGraph.END)
				.build();
			delegate.putIfLeasedVersion(config, checkpoint, delegate.getVersioned(config).revision(), winner);
			delegate.releaseLease(config, winner);
		}

		private static StateSerializer serializer() {
			return new ArgiJacksonStateSerializer(io.github.agentic.ai.graph.OverAllState::new, new ObjectMapper());
		}

	}

}
