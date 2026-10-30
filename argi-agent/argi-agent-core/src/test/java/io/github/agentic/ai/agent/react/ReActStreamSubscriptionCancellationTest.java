/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model stream subscription that arrives after the run was cancelled must be cancelled
 * instead of started: otherwise a stopped agent keeps an upstream model call alive and
 * spends tokens for a run nobody is waiting for (issue #276).
 */
class ReActStreamSubscriptionCancellationTest {

	@Test
	void cancelBeforeModelOnSubscribeNeverRequests() throws Exception {
		DeferredPublisher model = new DeferredPublisher(response(ChatMessage.assistant("late"), List.of(), 1), false);
		ScriptedPublisherClient client = new ScriptedPublisherClient(List.of(model));
		List<AgentEvent> events = new CopyOnWriteArrayList<>();
		try (AgentExecutionContext context = context()) {
			Flow.Subscription subscription = subscribe(
					ReActAgent.builder(client).streamModel(true).build(), events, context);

			assertTrue(model.subscribed.await(3, TimeUnit.SECONDS), "model publisher was never subscribed");
			subscription.cancel();
			model.deliverOnSubscribe();

			assertEquals(0, model.requests.get(), "a cancelled run must not request from a late subscription");
			assertEquals(1, model.cancels.get(), "the late subscription must be cancelled");
		}
		assertTrue(events.isEmpty(), "a cancelled run must not deliver events");
	}

	@Test
	void normalStreamingCompletionRequestsOnceAndIsNotCancelled() throws Exception {
		DeferredPublisher model = new DeferredPublisher(response(ChatMessage.assistant("answer"), List.of(), 1), true);
		ScriptedPublisherClient client = new ScriptedPublisherClient(List.of(model));
		List<AgentEvent> events = new CopyOnWriteArrayList<>();
		try (AgentExecutionContext context = context()) {
			subscribe(ReActAgent.builder(client).streamModel(true).build(), events, context);

			assertTrue(model.subscribed.await(3, TimeUnit.SECONDS), "model publisher was never subscribed");
			model.deliverOnSubscribe();
			awaitFinished(events);
		}
		assertEquals(1, model.requests.get());
		assertEquals(0, model.cancels.get(), "a completed run must not cancel its own subscription");
	}

	@Test
	void multiTurnStreamingRequestsEachTurn() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		DeferredPublisher first = new DeferredPublisher(
				response(ChatMessage.assistant("use", List.of(call)), List.of(call), 1), true);
		DeferredPublisher second = new DeferredPublisher(response(ChatMessage.assistant("done"), List.of(), 1), true);
		ScriptedPublisherClient client = new ScriptedPublisherClient(List.of(first, second));
		List<AgentEvent> events = new CopyOnWriteArrayList<>();
		Tool lookup = tool("lookup", "{}",
				(ignored, context) -> CompletableFuture.completedFuture(ToolResult.text("result")));
		try (AgentExecutionContext context = context()) {
			subscribe(ReActAgent.builder(client).streamModel(true).tools(new ToolRegistry(List.of(lookup))).build(),
					events, context);

			assertTrue(first.subscribed.await(3, TimeUnit.SECONDS), "first turn was never subscribed");
			first.deliverOnSubscribe();
			assertTrue(second.subscribed.await(3, TimeUnit.SECONDS), "second turn was never subscribed");
			second.deliverOnSubscribe();
			awaitFinished(events);
		}
		assertEquals(1, first.requests.get());
		assertEquals(1, second.requests.get());
		assertEquals(0, first.cancels.get() + second.cancels.get());
	}

	@Test
	void cancelDuringSecondTurnClosesOnlyTheActiveSubscription() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		DeferredPublisher first = new DeferredPublisher(
				response(ChatMessage.assistant("use", List.of(call)), List.of(call), 1), true);
		DeferredPublisher second = new DeferredPublisher(response(ChatMessage.assistant("done"), List.of(), 1), false);
		ScriptedPublisherClient client = new ScriptedPublisherClient(List.of(first, second));
		List<AgentEvent> events = new CopyOnWriteArrayList<>();
		Tool lookup = tool("lookup", "{}",
				(ignored, context) -> CompletableFuture.completedFuture(ToolResult.text("result")));
		try (AgentExecutionContext context = context()) {
			Flow.Subscription subscription = subscribe(
					ReActAgent.builder(client).streamModel(true).tools(new ToolRegistry(List.of(lookup))).build(),
					events, context);

			assertTrue(first.subscribed.await(3, TimeUnit.SECONDS), "first turn was never subscribed");
			first.deliverOnSubscribe();
			assertTrue(second.subscribed.await(3, TimeUnit.SECONDS), "second turn was never subscribed");

			subscription.cancel();
			second.deliverOnSubscribe();

			assertEquals(1, first.requests.get());
			assertEquals(0, first.cancels.get());
			assertEquals(0, second.requests.get());
			assertEquals(1, second.cancels.get());
		}
	}

	@Test
	void executionControlCancelStopsLateOnSubscribe() throws Exception {
		DeferredPublisher model = new DeferredPublisher(response(ChatMessage.assistant("late"), List.of(), 1), false);
		ScriptedPublisherClient client = new ScriptedPublisherClient(List.of(model));
		List<AgentEvent> events = new CopyOnWriteArrayList<>();
		try (AgentExecutionContext context = context()) {
			subscribe(ReActAgent.builder(client).streamModel(true).build(), events, context);

			assertTrue(model.subscribed.await(3, TimeUnit.SECONDS), "model publisher was never subscribed");
			context.control().cancel();
			model.deliverOnSubscribe();

			assertEquals(0, model.requests.get(), "a stopped execution must not start a late subscription");
			assertEquals(1, model.cancels.get());
		}
	}

	private static Flow.Subscription subscribe(Agent agent, List<AgentEvent> events, AgentExecutionContext context) {
		AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
		agent.stream(request(), context).subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription value) {
				subscription.set(value);
				value.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(AgentEvent event) {
				events.add(event);
			}

			@Override
			public void onError(Throwable error) {
			}

			@Override
			public void onComplete() {
			}
		});
		assertNotNull(subscription.get(), "the run must expose its subscription");
		return subscription.get();
	}

	private static void awaitFinished(List<AgentEvent> events) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (events.stream().noneMatch(event -> event instanceof AgentEvent.Finished)
				&& System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Finished), "stream did not finish");
	}

	private static AgentRequest request() {
		return AgentRequest.messages(List.of(ChatMessage.user("hello")));
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run").correlationId("correlation").budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none()).build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	private static Tool tool(String name, String schema, ToolExecutor executor) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, schema);
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return executor.execute(call, context);
			}
		};
	}

	private static ChatResponse response(ChatMessage message, List<ToolCall> calls, long total) {
		return new ChatResponse(null, "test", List.of(message), calls, FinishReason.STOP, new Usage(1L, 1L, total),
				Map.of());
	}

	@FunctionalInterface
	private interface ToolExecutor {
		CompletionStage<ToolResult> execute(ToolCall call, ToolContext context);
	}

	/**
	 * Publishes a single-turn stream whose {@code onSubscribe} is deferred until the test
	 * delivers it, so cancellation can be raced against a not-yet-registered subscription.
	 */
	private static final class DeferredPublisher implements Flow.Publisher<ChatEvent> {

		private final ChatResponse terminal;

		private final boolean emitOnRequest;

		final CountDownLatch subscribed = new CountDownLatch(1);

		final AtomicInteger requests = new AtomicInteger();

		final AtomicInteger cancels = new AtomicInteger();

		private volatile Flow.Subscriber<? super ChatEvent> subscriber;

		private DeferredPublisher(ChatResponse terminal, boolean emitOnRequest) {
			this.terminal = terminal;
			this.emitOnRequest = emitOnRequest;
		}

		@Override
		public void subscribe(Flow.Subscriber<? super ChatEvent> value) {
			this.subscriber = value;
			this.subscribed.countDown();
		}

		void deliverOnSubscribe() {
			subscriber.onSubscribe(new Flow.Subscription() {
				@Override
				public void request(long demand) {
					requests.incrementAndGet();
					if (emitOnRequest) {
						subscriber.onNext(ChatEvent.textDelta(terminal.messages().get(0).text()));
						subscriber.onNext(ChatEvent.response(terminal));
						subscriber.onComplete();
					}
				}

				@Override
				public void cancel() {
					cancels.incrementAndGet();
				}
			});
		}

	}

	private static final class ScriptedPublisherClient implements ChatClient {

		private final Deque<Flow.Publisher<ChatEvent>> publishers;

		private final List<ChatRequest> requests = new ArrayList<>();

		private ScriptedPublisherClient(List<Flow.Publisher<ChatEvent>> publishers) {
			this.publishers = new ArrayDeque<>(publishers);
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			throw new UnsupportedOperationException("streaming-only test client");
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			requests.add(request);
			return publishers.remove();
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
