/*
 * Copyright 2024-2026 the original author or authors.
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

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.messages.AssistantMessage;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cancellation rewind must capture its pre-turn snapshot at subscription time, not at
 * Flux assembly time. Graph execution starts on subscribe, so a snapshot read while the
 * Flux is being assembled goes stale: a state commit landing between assembly and
 * subscription would be rolled back by a later cancellation, and every re-subscription of
 * the cold Flux would reuse that same stale read.
 */
class ReactAgentRewindSubscriptionScopeTest {

	private static final class LookupTools {

		@Tool(description = "look up something")
		public String lookup(@ToolParam(description = "the query") String query) {
			return "result-for-" + query;
		}

	}

	/**
	 * Calls 1 and 2 return immediately with distinct answers; call 3 blocks until
	 * released, so the third turn is reliably mid-flight when the test disposes.
	 */
	private static final class ScriptedChatModel implements ChatModel {

		private final AtomicInteger callCount = new AtomicInteger();

		private final CountDownLatch thirdCallEntered = new CountDownLatch(1);

		private final CountDownLatch releaseThirdCall = new CountDownLatch(1);

		@Override
		public ChatResponse call(Prompt prompt) {
			int call = callCount.incrementAndGet();
			if (call == 3) {
				thirdCallEntered.countDown();
				try {
					releaseThirdCall.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return new ChatResponse(List.of(new Generation(new AssistantMessage("blocked-turn"))));
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("answer-" + call))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	private static final class SingleAnswerChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	private static final class CountingSaver implements BaseCheckpointSaver {

		private final MemorySaver delegate;

		private final AtomicInteger reads = new AtomicInteger();

		CountingSaver(MemorySaver delegate) {
			this.delegate = delegate;
		}

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			reads.incrementAndGet();
			return delegate.get(config);
		}

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return delegate.release(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			return delegate.put(config, checkpoint);
		}

	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messagesOf(Checkpoint checkpoint) {
		Object messages = checkpoint.getState().get("messages");
		return messages instanceof List<?> list ? (List<ChatMessage>) list : List.of();
	}

	@Test
	void cancellationRewindsToSubscriptionTimeSnapshotNotAssemblyTime() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel();
		ToolCallback lookup = ToolCallbacks.from(new LookupTools())[0];
		MemorySaver saver = new MemorySaver();
		ReactAgent agent = ReactAgent.builder()
			.name("rewind_scope_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(lookup))
			.saver(saver)
			.build();
		RunnableConfig config = RunnableConfig.builder().threadId("rewind-scope").build();

		// Turn one completes: the thread commits [human, answer-1].
		agent.streamMessages(io.github.agentic.ai.api.chat.ChatMessage.user("turn one"), config)
			.collectList()
			.block(Duration.ofSeconds(10));

		// Assemble turn two's Flux WITHOUT subscribing to it.
		Flux<NodeOutput> deferred = agent.stream("turn two", config);

		// A full turn commits between assembly and subscription:
		// [human, answer-1, human, answer-2].
		agent.streamMessages(io.github.agentic.ai.api.chat.ChatMessage.user("turn three"), config)
			.collectList()
			.block(Duration.ofSeconds(10));

		// Subscribe only now: the model call blocks until released, so the turn is
		// reliably mid-flight when we dispose. The rewind must restore the
		// subscription-time snapshot (the committed state containing answer-2),
		// never the stale assembly-time one taken before turn three ran.
		Disposable disposable = deferred.subscribe();
		assertTrue(model.thirdCallEntered.await(10, TimeUnit.SECONDS),
				"the deferred stream should start on subscription");
		disposable.dispose();
		model.releaseThirdCall.countDown();
		Thread.sleep(500);

		Checkpoint checkpoint = saver.get(config).orElse(null);
		assertNotNull(checkpoint, "the thread must remain addressable after the cancelled turn");
		List<ChatMessage> messages = messagesOf(checkpoint);
		assertEquals(4, messages.size(),
				"cancellation must roll back to the subscription-time snapshot, not the stale assembly-time one");
		ChatMessage last = messages.get(messages.size() - 1);
		assertTrue(last.role() == ChatRole.ASSISTANT && "answer-2".equals(last.text()),
				"the committed answer-2 turn must survive the cancellation: " + messages);
	}

	@Test
	void snapshotIsCapturedPerSubscriptionNotAtAssemblyTime() throws Exception {
		ToolCallback lookup = ToolCallbacks.from(new LookupTools())[0];
		CountingSaver counting = new CountingSaver(new MemorySaver());
		ReactAgent agent = ReactAgent.builder()
			.name("rewind_timing_agent")
			.chatClient(
					new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new SingleAnswerChatModel()))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(lookup))
			.saver(counting)
			.build();
		RunnableConfig config = RunnableConfig.builder().threadId("rewind-timing").build();

		Flux<NodeOutput> deferred = agent.stream("turn one", config);
		assertEquals(0, counting.reads.get(), "assembling the Flux must not read the checkpoint");

		deferred.collectList().block(Duration.ofSeconds(10));
		assertTrue(counting.reads.get() >= 1, "each subscription must capture its own pre-turn snapshot");
	}

}
