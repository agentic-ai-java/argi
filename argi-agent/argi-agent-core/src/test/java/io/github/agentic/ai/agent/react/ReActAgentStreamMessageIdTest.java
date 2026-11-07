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

import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.result.StructuredOutputFinalizer;
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
import io.github.agentic.ai.runtime.execution.ExecutionStoppedException;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Message-ID consistency of the independent ReAct streaming path (issue #280):
 * TEXT_DELTA events and the complete-message snapshot of the same logical model
 * message share one executor-assigned, invocation-local ID; turns, tool messages
 * and multiple messages of a single response stay distinct; the schema repair
 * replaces the terminal message under the ID consumers already saw. All
 * assertions go through a consumer that maintains a message view keyed by ID,
 * never by event order or a fixed stream marker.
 */
class ReActAgentStreamMessageIdTest {

	private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"answer\"],"
			+ "\"additionalProperties\":false,\"properties\":{\"answer\":{\"type\":\"string\"}}}";

	@Test
	void multiFragmentDeltasSealUnderTheSnapshotId() throws Exception {
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(
				TurnScript.of(List.of(ChatEvent.textDelta("Hel"), ChatEvent.textDelta("lo")),
						response(ChatMessage.assistant("Hello"), List.of(), 1))));
		MessageView view = runSingleTurn(client);

		assertEquals(Map.of("model-0", "Hello"), view.deltaTexts());
		assertEquals(Map.of("model-0", "Hello"), view.snapshotTexts());
		view.assertSealed("model-0");
	}

	@Test
	void multiTurnToolLoopKeepsEachTurnsDeltasIsolated() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(
				TurnScript.of(List.of(ChatEvent.textDelta("use")),
						response(ChatMessage.assistant("use", List.of(call)), List.of(call), 1)),
				TurnScript.of(List.of(ChatEvent.textDelta("do"), ChatEvent.textDelta("ne")),
						response(ChatMessage.assistant("done"), List.of(), 1))));
		MessageView view = runSingleTurn(client,
				agent -> agent.tools(new ToolRegistry(List.of(tool("lookup")))));

		// Turn 0's deltas belong to model-0 alone and are sealed by its snapshot;
		// turn 1's deltas never merge into the first message; the tool result
		// message keeps its own distinct ID between them.
		assertEquals(Map.of("model-0", "use", "model-1", "done"), view.deltaTexts());
		assertEquals(Map.of("model-0", "use", "tool-call-1", "result", "model-1", "done"), view.snapshotTexts());
		view.assertSealed("model-0", "model-1");
	}

	@Test
	void textlessToolTurnEmitsNoDeltasAndDistinctIds() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(
				TurnScript.of(List.of(), response(ChatMessage.assistant("", List.of(call)), List.of(call), 1)),
				TurnScript.of(List.of(ChatEvent.textDelta("done")), response(ChatMessage.assistant("done"),
						List.of(), 1))));
		MessageView view = runSingleTurn(client,
				agent -> agent.tools(new ToolRegistry(List.of(tool("lookup")))));

		// A turn without streamed text emits no delta at all; its model message,
		// the tool message, and the next turn's model message stay distinct.
		assertEquals(Map.of("model-1", "done"), view.deltaTexts());
		assertTrue(view.snapshotTexts().containsKey("model-0"));
		assertTrue(view.snapshotTexts().containsKey("tool-call-1"));
		assertTrue(view.snapshotTexts().containsKey("model-1"));
		view.assertSealed("model-1");
	}

	@Test
	void multiMessageResponseIsRejectedByTheTurnContract() throws Exception {
		ChatResponse twoMessages = new ChatResponse(null, "test",
				List.of(ChatMessage.assistant("first"), ChatMessage.assistant("second")), List.of(),
				FinishReason.STOP, new Usage(1L, 1L, 1L), Map.of());
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(
				TurnScript.of(List.of(ChatEvent.textDelta("first")), twoMessages)));
		MessageView view = runSingleTurn(client);

		// The actual turn contract (ModelResponse requires exactly one assistant
		// message) distinguishes a multi-message response by rejecting it; the
		// turn's partial deltas stay attributed to that turn's ID.
		Throwable failure = assertFailure(view);
		assertTrue(failure instanceof IllegalArgumentException,
				() -> "multi-message responses violate the turn contract");
		assertEquals(Map.of("model-0", "first"), view.deltaTexts());
		assertTrue(view.snapshotTexts().isEmpty(), "no message snapshot is emitted for a rejected turn");
	}

	@Test
	void interceptorStreamCannotSmuggleMultipleMessagesPerTurn() throws Exception {
		ChatResponse twoMessages = new ChatResponse(null, "test",
				List.of(ChatMessage.assistant("first"), ChatMessage.assistant("second")), List.of(),
				FinishReason.STOP, new Usage(1L, 1L, 1L), Map.of());
		// ModelResponse.stream carries no single-message validation, so an
		// interceptor-supplied stream is the one path that could bypass the turn
		// contract; the turn emission point must reject it the same way.
		ModelInterceptor smuggler = new ModelInterceptor() {
			public String getName() {
				return "smuggle-multi-message";
			}

			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return ModelResponse.stream(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
					public void request(long demand) {
						subscriber.onNext(ChatEvent.textDelta("first"));
						subscriber.onNext(ChatEvent.response(twoMessages));
						subscriber.onComplete();
					}

					public void cancel() {
					}
				}));
			}
		};
		ReActAgent agent = ReActAgent.builder(unsupportedClient()).interceptors(List.of(smuggler)).build();
		MessageView view = new MessageView();
		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(view);
			try {
				view.finished().get(3, TimeUnit.SECONDS);
			}
			catch (ExecutionException expectedRunFailure) {
				// The view retains the failure; assertions below.
			}
		}

		Throwable failure = assertFailure(view);
		assertTrue(failure instanceof IllegalStateException,
				() -> "interceptor streams must obey the one-assistant-message turn contract: " + failure);
		assertTrue(failure.getMessage().contains("exactly one assistant message"));
		assertEquals(Map.of("model-0", "first"), view.deltaTexts());
		assertTrue(view.snapshotTexts().isEmpty(), "no snapshot may share one turn ID across two messages");
	}

	@Test
	void cancelledStreamAttributesPartialDeltasToTheCancelledTurn() throws Exception {
		CountDownLatch firstDeltaSeen = new CountDownLatch(1);
		CountDownLatch releaseGate = new CountDownLatch(1);
		ChatClient gated = gatedClient(firstDeltaSeen, releaseGate);
		ReActAgent agent = ReActAgent.builder(gated).streamModel(true).build();
		MessageView view = new MessageView();
		Flow.Subscriber<AgentEvent> observing = new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(AgentEvent event) {
				view.onNext(event);
				if (event instanceof AgentEvent.MessageDelta) {
					firstDeltaSeen.countDown();
				}
			}

			@Override
			public void onError(Throwable error) {
				view.onError(error);
			}

			@Override
			public void onComplete() {
				view.onComplete();
			}
		};
		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(observing);
			assertTrue(firstDeltaSeen.await(3, TimeUnit.SECONDS), "the first delta must arrive");
			context.control().cancel();
			releaseGate.countDown();
			try {
				view.finished().get(3, TimeUnit.SECONDS);
			}
			// ExecutionStoppedException extends CancellationException, which
			// CompletableFuture.get() rethrows as-is instead of wrapping in
			// ExecutionException — both shapes mean "run failed", asserted below.
			catch (ExecutionException | CancellationException expectedRunFailure) {
			}
		}

		Throwable failure = assertFailure(view);
		assertTrue(failure instanceof ExecutionStoppedException, () -> "cancellation must stop the run: " + failure);
		// The partial delta stays attributed to the cancelled turn's ID; no
		// snapshot seals it and no post-cancel delta leaks through.
		assertEquals(Map.of("model-0", "par"), view.deltaTexts());
		assertTrue(view.snapshotTexts().isEmpty());
	}

	@Test
	void repeatedInvocationsStaySelfConsistentPerView() throws Exception {
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(
				TurnScript.of(List.of(ChatEvent.textDelta("first")),
						response(ChatMessage.assistant("first"), List.of(), 1)),
				TurnScript.of(List.of(ChatEvent.textDelta("second")),
						response(ChatMessage.assistant("second"), List.of(), 1))));
		ReActAgent agent = ReActAgent.builder(client).streamModel(true).build();

		MessageView firstView = new MessageView();
		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(firstView);
			firstView.finished().get(3, TimeUnit.SECONDS);
		}
		MessageView secondView = new MessageView();
		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(secondView);
			secondView.finished().get(3, TimeUnit.SECONDS);
		}

		// Cross-call ID uniqueness is explicitly not required; what the contract
		// guarantees is that each invocation's projection is self-consistent, so
		// a fresh per-invocation view never inherits the previous run's text.
		assertEquals(Map.of("model-0", "first"), firstView.deltaTexts());
		assertEquals(Map.of("model-0", "second"), secondView.deltaTexts());
		secondView.assertSealed("model-0");
	}

	@Test
	void failedStreamAttributesPartialDeltasToTheFailingTurn() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(
				TurnScript.of(List.of(ChatEvent.textDelta("use")),
						response(ChatMessage.assistant("use", List.of(call)), List.of(call), 1)),
				TurnScript.failingAfter(List.of(ChatEvent.textDelta("par")),
						new IllegalStateException("stream broke"))));
		MessageView view = runSingleTurn(client,
				agent -> agent.tools(new ToolRegistry(List.of(tool("lookup")))));

		Throwable failure = assertFailure(view);
		assertEquals("stream broke", failure.getMessage());
		// Turn 0 is fully sealed; turn 1's partial text stays attributed to its
		// own ID instead of leaking into the sealed turn 0 message.
		assertEquals(Map.of("model-0", "use", "model-1", "par"), view.deltaTexts());
		view.assertSealed("model-0");
		assertNull(view.snapshotTexts().get("model-1"));
	}

	@Test
	void repairReplacesTheTerminalUnderItsOwnId() throws Exception {
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(TurnScript.of(
				List.of(ChatEvent.textDelta("not-json")),
				response(ChatMessage.assistant("not-json"), List.of(), 1))));
		ReActAgent agent = ReActAgent.builder(client)
			.streamModel(true)
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(repairClient("{\"answer\":\"fixed\"}")))
			.build();
		MessageView view = new MessageView();

		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(view);
			view.finished().get(3, TimeUnit.SECONDS);
		}

		// The repaired terminal replaces the failed message under the ID the
		// consumer already displayed, so an ID-keyed view ends up consistent.
		assertEquals("{\"answer\":\"fixed\"}", view.snapshotTexts().get("model-0"));
		assertNull(view.snapshotTexts().get("model-repair"));
	}

	@Test
	void returnDirectRepairReplacesTheToolMessageUnderItsOwnId() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		Tool direct = new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", "lookup", "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall invocation, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("not-json"));
			}

			public boolean returnDirect() {
				return true;
			}
		};
		ScriptedStreamClient client = ScriptedStreamClient.of(List.of(TurnScript.of(List.of(),
				response(ChatMessage.assistant("", List.of(call)), List.of(call), 1))));
		ReActAgent agent = ReActAgent.builder(client)
			.streamModel(true)
			.tools(new ToolRegistry(List.of(direct)))
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(repairClient("{\"answer\":\"fixed\"}")))
			.build();
		MessageView view = new MessageView();

		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(view);
			view.finished().get(3, TimeUnit.SECONDS);
		}

		assertEquals("{\"answer\":\"fixed\"}", view.snapshotTexts().get("tool-call-1"));
		assertNull(view.snapshotTexts().get("model-repair"));
	}

	@Test
	void concurrentRunsKeepProjectionsIsolated() throws Exception {
		ScriptedStreamClient shared = ScriptedStreamClient.of(List.of(
				TurnScript.of(List.of(ChatEvent.textDelta("left")),
						response(ChatMessage.assistant("left"), List.of(), 1)),
				TurnScript.of(List.of(ChatEvent.textDelta("right")),
						response(ChatMessage.assistant("right"), List.of(), 1))));
		ReActAgent agent = ReActAgent.builder(shared).streamModel(true).build();
		MessageView first = new MessageView();
		MessageView second = new MessageView();

		Thread runner = new Thread(() -> {
			try (AgentExecutionContext context = context("run-second")) {
				agent.stream(request(), context).subscribe(second);
				try {
					second.finished().get(3, TimeUnit.SECONDS);
				}
				catch (Exception expectedCompletion) {
					// The view retains the outcome or failure for assertion below.
				}
			}
		});
		runner.start();
		try (AgentExecutionContext context = context("run-first")) {
			agent.stream(request(), context).subscribe(first);
			first.finished().get(3, TimeUnit.SECONDS);
			runner.join(TimeUnit.SECONDS.toMillis(3));
			second.finished().get(3, TimeUnit.SECONDS);
		}

		// Two invocations of the same agent hold independent projections: each
		// view sees exactly its own text under its own local IDs.
		assertTrue(viewHolds(first, "left") ^ viewHolds(second, "left"));
		assertTrue(viewHolds(first, "right") ^ viewHolds(second, "right"));
		assertEquals(1, first.deltaTexts().size());
		assertEquals(1, second.deltaTexts().size());
	}

	private static boolean viewHolds(MessageView view, String text) {
		return text.equals(view.deltaTexts().get("model-0")) && text.equals(view.snapshotTexts().get("model-0"));
	}

	private static MessageView runSingleTurn(ScriptedStreamClient client) throws Exception {
		return runSingleTurn(client, builder -> {
		});
	}

	private static MessageView runSingleTurn(ScriptedStreamClient client, Consumer<ReActAgent.Builder> configure)
			throws Exception {
		ReActAgent.Builder builder = ReActAgent.builder(client).streamModel(true);
		configure.accept(builder);
		ReActAgent agent = builder.build();
		MessageView view = new MessageView();
		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(view);
			try {
				view.finished().get(3, TimeUnit.SECONDS);
			}
			catch (ExecutionException expectedRunFailure) {
				// The view retains the failure; callers assert on it.
			}
		}
		return view;
	}

	private static Throwable assertFailure(MessageView view) {
		assertTrue(view.finished().isCompletedExceptionally(), "the run was expected to fail");
		return view.finished().handle((outcome, error) -> error).getNow(null);
	}

	private static ChatResponse response(ChatMessage message, List<ToolCall> calls, long total) {
		return new ChatResponse(null, "test", List.of(message), calls, FinishReason.STOP, new Usage(1L, 1L, total),
				Map.of());
	}

	private static Tool tool(String name) {
		return new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("result"));
			}
		};
	}

	private static ChatClient repairClient(String json) {
		return new ChatClient() {
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(response(ChatMessage.assistant(json), List.of(), 1));
			}

			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				throw new UnsupportedOperationException();
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
	}

	private static ChatClient unsupportedClient() {
		return new ChatClient() {
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.failedFuture(new UnsupportedOperationException("not reached"));
			}

			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				throw new UnsupportedOperationException("not reached");
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
	}

	/**
	 * Streams one delta, then blocks until the gate opens, then streams the rest
	 * of the turn — the seam a test uses to cancel the run mid-stream.
	 */
	private static ChatClient gatedClient(CountDownLatch firstDeltaSeen, CountDownLatch releaseGate) {
		return new ChatClient() {
			public Flow.Publisher<ChatEvent> stream(ChatRequest chatRequest) {
				return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
					public void request(long demand) {
						subscriber.onNext(ChatEvent.textDelta("par"));
						try {
							releaseGate.await(3, TimeUnit.SECONDS);
						}
						catch (InterruptedException interrupted) {
							Thread.currentThread().interrupt();
						}
						subscriber.onNext(ChatEvent.textDelta("tial"));
						subscriber.onNext(ChatEvent.response(
								response(ChatMessage.assistant("partial"), List.of(), 1)));
						subscriber.onComplete();
					}

					public void cancel() {
					}
				});
			}

			public CompletionStage<ChatResponse> call(ChatRequest chatRequest) {
				return CompletableFuture.failedFuture(new UnsupportedOperationException("streaming client"));
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
	}

	private static AgentRequest request() {
		return AgentRequest.messages(List.of(ChatMessage.user("hello")));
	}

	private static AgentExecutionContext context() {
		return context("run");
	}

	private static AgentExecutionContext context(String runId) {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId(runId).correlationId("correlation").budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none()).build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	/**
	 * The consumer the issue asks for: maintains a message view keyed by the
	 * event message ID — concatenated deltas per ID, latest snapshot per ID —
	 * and can verify that a streamed message was sealed by its snapshot.
	 */
	private static final class MessageView implements Flow.Subscriber<AgentEvent> {

		private final Map<String, StringBuilder> deltasById = new LinkedHashMap<>();

		private final Map<String, String> snapshotTexts = new LinkedHashMap<>();

		private final CompletableFuture<AgentOutcome> finished = new CompletableFuture<>();

		Map<String, String> deltaTexts() {
			Map<String, String> texts = new LinkedHashMap<>();
			deltasById.forEach((id, builder) -> texts.put(id, builder.toString()));
			return texts;
		}

		Map<String, String> snapshotTexts() {
			return snapshotTexts;
		}

		CompletableFuture<AgentOutcome> finished() {
			return finished;
		}

		void assertSealed(String... messageIds) {
			for (String id : messageIds) {
				String snapshot = snapshotTexts.get(id);
				assertTrue(snapshot != null, () -> "no snapshot for " + id);
				StringBuilder delta = deltasById.get(id);
				if (delta != null) {
					assertEquals(snapshot, delta.toString(), () -> "deltas of " + id + " must seal into its snapshot");
				}
			}
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(AgentEvent event) {
			if (event instanceof AgentEvent.MessageDelta delta) {
				deltasById.computeIfAbsent(delta.messageId(), id -> new StringBuilder()).append(delta.text());
			}
			else if (event instanceof AgentEvent.Message message) {
				snapshotTexts.put(message.messageId(), message.message().text());
			}
			else if (event instanceof AgentEvent.Finished done) {
				finished.complete(done.outcome());
			}
		}

		@Override
		public void onError(Throwable error) {
			finished.completeExceptionally(error);
		}

		@Override
		public void onComplete() {
		}

	}

	/**
	 * One streamed model turn: a sequence of chat events, optionally ending in
	 * a stream failure instead of a completed response.
	 */
	private record TurnScript(List<ChatEvent> events, Throwable failure) {

		static TurnScript of(List<ChatEvent> events, ChatResponse reply) {
			List<ChatEvent> script = new ArrayList<>(events);
			script.add(ChatEvent.response(reply));
			return new TurnScript(List.copyOf(script), null);
		}

		static TurnScript failingAfter(List<ChatEvent> events, Throwable failure) {
			return new TurnScript(List.copyOf(events), failure);
		}

	}

	private static final class ScriptedStreamClient implements ChatClient {

		private final Queue<TurnScript> turns;

		private ScriptedStreamClient(List<TurnScript> turns) {
			this.turns = new ArrayDeque<>(turns);
		}

		static ScriptedStreamClient of(List<TurnScript> turns) {
			return new ScriptedStreamClient(turns);
		}

		public synchronized Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			TurnScript script = turns.remove();
			return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					for (ChatEvent event : script.events()) {
						subscriber.onNext(event);
					}
					if (script.failure() == null) {
						subscriber.onComplete();
					}
					else {
						subscriber.onError(script.failure());
					}
				}

				public void cancel() {
				}
			});
		}

		public CompletionStage<ChatResponse> call(ChatRequest request) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("streaming client"));
		}

		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
