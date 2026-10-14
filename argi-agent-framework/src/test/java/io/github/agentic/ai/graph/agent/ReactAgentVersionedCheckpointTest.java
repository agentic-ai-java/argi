/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
import io.github.agentic.ai.graph.checkpoint.CheckpointExecutionQueue;
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.VersionedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.VersionedCheckpointScope;
import io.github.agentic.ai.graph.checkpoint.savers.VersionedMemoryCheckpointSaver;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ReactAgentVersionedCheckpointTest {

	private static final String BLOCK_FIRST = "block-first";

	private static final String TOOL_CANCEL = "tool-cancel";

	private static final String QUEUED = "queued";

	@Test
	void cancellingWaitingTurnDoesNotOpenVersionedScopeOrRewind() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Disposable active = fixture.subscribe(BLOCK_FIRST);
			fixture.model.awaitFirstCall(BLOCK_FIRST);
			int versionedReads = fixture.saver.versionedReads.get();
			int plainPuts = fixture.saver.plainPuts.get();
			int conditionalPuts = fixture.saver.conditionalPuts.get();

			Disposable waiting = fixture.subscribe(QUEUED);
			waiting.dispose();

			assertEquals(versionedReads, fixture.saver.versionedReads.get(),
					"cancelling a queued turn must not capture a versioned pre-turn snapshot");
			assertEquals(plainPuts, fixture.saver.plainPuts.get(),
					"cancelling a queued turn must not issue a legacy rewind write");
			assertEquals(conditionalPuts, fixture.saver.conditionalPuts.get(),
					"cancelling a queued turn must not rewind the active turn");

			active.dispose();
			fixture.awaitTerminated(BLOCK_FIRST);
		}
	}

	@Test
	void cancellationBeforeModelOutputUsesVersionedRewind() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Disposable active = fixture.subscribe(BLOCK_FIRST);
			fixture.model.awaitFirstCall(BLOCK_FIRST);

			active.dispose();
			fixture.awaitTerminated(BLOCK_FIRST);

			assertEquals(0, fixture.saver.plainPuts.get(), "versioned cancellation must never use legacy put");
			assertTrue(fixture.saver.conditionalPuts.get() > 0,
					"startup checkpoints and cancellation rewind should be CAS writes");
			Checkpoint checkpoint = fixture.saver.getVersioned(fixture.config).checkpoint().orElseThrow();
			assertEquals(Map.of(), checkpoint.getState());
			assertEquals(StateGraph.START, checkpoint.getNodeId());
			assertEquals(StateGraph.END, checkpoint.getNextNodeId());
		}
	}

	@Test
	void cancellationAfterScopeEntryBeforeAnyOwnedWriteDoesNotRewindVersionedCheckpoint() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Checkpoint seed = Checkpoint.builder()
				.state(Map.of("owner", "seed"))
				.nodeId(StateGraph.START)
				.nextNodeId(StateGraph.END)
				.build();
			fixture.saver.putIfVersion(fixture.config, seed, 0);
			CheckpointSnapshot beforeSubscribe = fixture.saver.getVersioned(fixture.config);
			int conditionalPutsBeforeSubscribe = fixture.saver.conditionalPuts.get();
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch terminated = new CountDownLatch(1);

			Disposable active = CheckpointExecutionQueue.serialize(fixture.saver, fixture.config,
					() -> VersionedCheckpointScope.withScope(fixture.saver, fixture.config,
							scope -> Flux.<NodeOutput>never()
								.doOnSubscribe(subscription -> entered.countDown())
								.doFinally(signal -> {
									if (signal == reactor.core.publisher.SignalType.CANCEL) {
										try {
											scope.rewind(fixture.config);
										}
										catch (Exception ex) {
											throw new AssertionError(ex);
										}
									}
								})))
				.doFinally(signal -> terminated.countDown())
				.subscribe();
			assertTrue(entered.await(10, TimeUnit.SECONDS), "scope-backed operation must start");
			assertEquals(conditionalPutsBeforeSubscribe, fixture.saver.conditionalPuts.get(),
					"entering the scope must not create an owned write");

			active.dispose();
			assertTrue(terminated.await(10, TimeUnit.SECONDS), "subscription must terminate after cancellation");

			CheckpointSnapshot afterCancel = fixture.saver.getVersioned(fixture.config);
			assertEquals(conditionalPutsBeforeSubscribe, fixture.saver.conditionalPuts.get(),
					"cancellation with an entered scope but no owned writes must not rewind");
			assertEquals(beforeSubscribe.revision(), afterCancel.revision());
			assertEquals("seed", afterCancel.checkpoint().orElseThrow().getState().get("owner"));
			assertEquals(StateGraph.END, afterCancel.checkpoint().orElseThrow().getNextNodeId());
		}
	}

	@Test
	void cancellationAfterOwnWriteRewindsWithVersionedScope() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Disposable active = fixture.subscribe(TOOL_CANCEL);
			fixture.model.awaitSecondCall(TOOL_CANCEL);

			active.dispose();
			fixture.awaitTerminated(TOOL_CANCEL);

			assertEquals(1, fixture.tools.calls.get(), "the real tool should execute once before cancellation");
			assertEquals(0, fixture.saver.plainPuts.get(), "rewind must use the versioned scope, not legacy put");
			assertTrue(fixture.saver.conditionalPuts.get() >= 3,
					"model/tool checkpoints and cancellation rewind should all be CAS writes");
			Checkpoint checkpoint = fixture.saver.getVersioned(fixture.config).checkpoint().orElseThrow();
			assertEquals(Map.of(), checkpoint.getState());
			assertEquals(StateGraph.START, checkpoint.getNodeId());
			assertEquals(StateGraph.END, checkpoint.getNextNodeId());
		}
	}

	@Test
	void externalWriterBeforeCancelWinsOverVersionedRewind() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Disposable active = fixture.subscribe(TOOL_CANCEL);
			fixture.model.awaitSecondCall(TOOL_CANCEL);
			fixture.saver.externalWrite(fixture.config, Map.of("owner", "external"));

			active.dispose();
			fixture.awaitTerminated(TOOL_CANCEL);

			assertEquals(1, fixture.tools.calls.get(), "cancellation must not replay tool side effects");
			assertEquals(0, fixture.saver.plainPuts.get(), "stale cancellation must not blind-write over a winner");
			assertEquals("external",
					fixture.saver.getVersioned(fixture.config).checkpoint().orElseThrow().getState().get("owner"));
		}
	}

	@Test
	void coldResubscriptionUsesFreshVersionedScope() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Flux<NodeOutput> cold = fixture.agent.stream(BLOCK_FIRST, fixture.config);

			Disposable first = cold.doFinally(signal -> fixture.terminated(BLOCK_FIRST).countDown()).subscribe();
			fixture.model.awaitFirstCall(BLOCK_FIRST);
			first.dispose();
			fixture.awaitTerminated(BLOCK_FIRST);
			fixture.saver.externalWrite(fixture.config, Map.of("owner", "external"));
			int readsAfterFirstSubscription = fixture.saver.versionedReads.get();

			List<NodeOutput> outputs = cold.collectList().block(Duration.ofSeconds(10));

			assertNotNull(outputs);
			assertEquals(readsAfterFirstSubscription + 1, fixture.saver.versionedReads.get(),
					"each cold subscription must create exactly one fresh initial versioned scope read");
			assertEquals(0, fixture.saver.plainPuts.get(), "the cancelled first subscription must not legacy-rewind");
			assertEquals("external",
					fixture.saver.getVersioned(fixture.config).checkpoint().orElseThrow().getState().get("owner"));
		}
	}

	@Test
	void legacyPreTurnReadFailureIsNotSwallowedInVersionedMode() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.saver.failPlainReads.set(true);

			fixture.agent.stream("answer", fixture.config).collectList().block(Duration.ofSeconds(10));

			assertEquals(0, fixture.saver.plainReads.get(), "versioned ReactAgent must not use the legacy get path");
		}
	}

	@Test
	void initialVersionedSnapshotFailurePropagates() {
		try (Fixture fixture = new Fixture()) {
			fixture.saver.failVersionedReads.set(true);

			RuntimeException error = assertThrows(RuntimeException.class,
					() -> fixture.agent.stream("answer", fixture.config).collectList().block(Duration.ofSeconds(10)));

			assertTrue(rootCause(error).getMessage().contains("versioned snapshot failed"));
		}
	}

	private static Throwable rootCause(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current;
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

	private static final class CountingTools {

		private final AtomicInteger calls = new AtomicInteger();

		@Tool(description = "increment a counter")
		public String count() {
			return "count-" + calls.incrementAndGet();
		}

	}

	private static final class ControlledModel implements ChatModel {

		private final AtomicInteger blockFirstAttempts = new AtomicInteger();

		private final Map<String, CountDownLatch> firstCalls = Map.of(BLOCK_FIRST, new CountDownLatch(1), TOOL_CANCEL,
				new CountDownLatch(1), QUEUED, new CountDownLatch(1));

		private final Map<String, CountDownLatch> secondCalls = Map.of(TOOL_CANCEL, new CountDownLatch(1));

		private final Map<String, Sinks.One<ChatResponse>> blockedResponses = Map.of(BLOCK_FIRST, Sinks.one(),
				TOOL_CANCEL, Sinks.one(), QUEUED, Sinks.one());

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("Tests use streaming model calls");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			String request = lastUserText(prompt);
			if (BLOCK_FIRST.equals(request)) {
				firstCalls.get(BLOCK_FIRST).countDown();
				if (blockFirstAttempts.incrementAndGet() == 1) {
					return blockedResponses.get(BLOCK_FIRST).asMono().flux();
				}
				return Flux.just(response("answer-after-resubscribe"));
			}
			if (TOOL_CANCEL.equals(request)) {
				if (hasToolResponse(prompt)) {
					secondCalls.get(TOOL_CANCEL).countDown();
					return blockedResponses.get(TOOL_CANCEL).asMono().flux();
				}
				firstCalls.get(TOOL_CANCEL).countDown();
				return Flux.just(toolCallResponse());
			}
			if (QUEUED.equals(request)) {
				firstCalls.get(QUEUED).countDown();
				return blockedResponses.get(QUEUED).asMono().flux();
			}
			return Flux.just(response("answer-" + request));
		}

		private void awaitFirstCall(String request) throws InterruptedException {
			assertTrue(firstCalls.get(request).await(10, TimeUnit.SECONDS), "model call must start: " + request);
		}

		private void awaitSecondCall(String request) throws InterruptedException {
			assertTrue(secondCalls.get(request).await(10, TimeUnit.SECONDS),
					"second model call must start: " + request);
		}

	}

	private static final class RecordingVersionedSaver implements VersionedCheckpointSaver {

		private final VersionedMemoryCheckpointSaver delegate = new VersionedMemoryCheckpointSaver();

		private final AtomicInteger plainReads = new AtomicInteger();

		private final AtomicInteger versionedReads = new AtomicInteger();

		private final AtomicInteger plainPuts = new AtomicInteger();

		private final AtomicInteger conditionalPuts = new AtomicInteger();

		private final AtomicBoolean failPlainReads = new AtomicBoolean();

		private final AtomicBoolean failVersionedReads = new AtomicBoolean();

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			plainReads.incrementAndGet();
			if (failPlainReads.get()) {
				throw new IllegalStateException("legacy snapshot failed");
			}
			return delegate.get(config);
		}

		@Override
		public CheckpointSnapshot getVersioned(RunnableConfig config) {
			versionedReads.incrementAndGet();
			if (failVersionedReads.get()) {
				throw new IllegalStateException("versioned snapshot failed");
			}
			return delegate.getVersioned(config);
		}

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			plainPuts.incrementAndGet();
			return delegate.put(config, checkpoint);
		}

		@Override
		public RunnableConfig putIfVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision)
				throws Exception {
			conditionalPuts.incrementAndGet();
			return delegate.putIfVersion(config, checkpoint, expectedRevision);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return delegate.release(config);
		}

		@Override
		public BaseCheckpointSaver.Tag releaseIfVersion(RunnableConfig config, long expectedRevision) throws Exception {
			return delegate.releaseIfVersion(config, expectedRevision);
		}

		private void externalWrite(RunnableConfig config, Map<String, Object> state) throws Exception {
			Checkpoint checkpoint = Checkpoint.builder()
				.state(state)
				.nodeId("external")
				.nextNodeId(StateGraph.END)
				.build();
			delegate.putIfVersion(config, checkpoint, delegate.getVersioned(config).revision());
		}

	}

	private static final class Fixture implements AutoCloseable {

		private final RecordingVersionedSaver saver = new RecordingVersionedSaver();

		private final ControlledModel model = new ControlledModel();

		private final CountingTools tools = new CountingTools();

		private final RunnableConfig config = RunnableConfig.builder().threadId("versioned-react-agent").build();

		private final ReactAgent agent;

		private final Map<String, CountDownLatch> terminated = Map.of(BLOCK_FIRST, new CountDownLatch(1), TOOL_CANCEL,
				new CountDownLatch(1), QUEUED, new CountDownLatch(1));

		private Fixture() {
			ToolCallback count = ToolCallbacks.from(tools)[0];
			agent = ReactAgent.builder()
				.name("versioned-react-agent")
				.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
				.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(count))
				.saver(saver)
				.build();
		}

		private Disposable subscribe(String request) throws Exception {
			return agent.stream(request, config).doFinally(signal -> terminated(request).countDown()).subscribe();
		}

		private CountDownLatch terminated(String request) {
			return terminated.get(request);
		}

		private void awaitTerminated(String request) throws InterruptedException {
			assertTrue(terminated(request).await(10, TimeUnit.SECONDS), "subscription must terminate: " + request);
		}

		@Override
		public void close() {
			model.blockedResponses.values().forEach(sink -> sink.tryEmitValue(response("released")));
		}

	}

}
