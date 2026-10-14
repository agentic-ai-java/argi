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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.adapter.springai.SpringAiToolAdapter;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionGuard;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;
import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_CONTEXT_KEY;
import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the actual node boundary rather than reimplementing its execution algorithm.
 */
class NeutralToolRuntimeTest {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void neutralToolFailuresRespectErrorPolicy(boolean parallel) throws Exception {
		Tool failing = tool("lookup",
				(call, context) -> CompletableFuture.failedFuture(new IllegalStateException("backend unavailable")));
		verifyErrorPolicy(failing, parallel, false);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void nestedAsyncFailuresPreserveRootCauseMetadata(boolean parallel) throws Exception {
		Tool failing = tool("lookup", (call, context) -> CompletableFuture
			.failedFuture(new java.util.concurrent.CompletionException(
					new java.util.concurrent.ExecutionException(new IllegalStateException("backend unavailable")))));
		Map<String, Object> output = builder(failing).parallelToolExecution(parallel)
			.build()
			.apply(state(call("one"), call("two")), RunnableConfig.builder().build());
		assertThat(results(output)).hasSize(2).allSatisfy(message -> {
			assertThat(message.metadata()).containsEntry("tool.error", true)
				.containsEntry(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, IllegalStateException.class.getName());
			assertThat(message.text()).contains("backend unavailable");
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void builtInToolFailuresRespectErrorPolicy(boolean parallel) throws Exception {
		var callback = FunctionToolCallback.builder("lookup", (Map<String, Object> input) -> {
			throw new IllegalStateException("backend unavailable");
		}).description("Lookup").inputType(Map.class).build();
		verifyErrorPolicy(new SpringAiToolAdapter(callback), parallel, false);
		verifyErrorPolicy(new SpringAiToolAdapter(callback), parallel, true);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void asyncFailuresRespectErrorPolicy(boolean parallel) throws Exception {
		verifyErrorPolicy(
				asyncTool(() -> CompletableFuture.failedFuture(new IllegalStateException("backend unavailable"))),
				parallel, false);
		verifyErrorPolicy(asyncTool(() -> {
			throw new IllegalStateException("backend unavailable");
		}), parallel, false);
		verifyErrorPolicy(asyncTool(() -> null), parallel, false, "tool returned no stage");
		verifyErrorPolicy(asyncTool(() -> {
			CompletableFuture<String> future = new CompletableFuture<>();
			future.cancel(true);
			return future;
		}), parallel, false, "CancellationException");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void asyncTimeoutCancelsTheSourceAndRespectsErrorPolicy(boolean parallel) throws Exception {
		var source = new AtomicReference<CompletableFuture<String>>();
		Tool tool = asyncTool(() -> {
			var future = new CompletableFuture<String>();
			source.set(future);
			return future;
		});
		verifyErrorPolicy(tool, parallel, false, "TimeoutException");
		assertThat(source.get()).isCancelled();
	}

	private static Tool asyncTool(Supplier<CompletableFuture<String>> execution) {
		return tool("lookup", (call, context) -> {
			CompletableFuture<String> source = execution.get();
			if (source == null) {
				return null;
			}
			CompletableFuture<ToolResult> result = new CompletableFuture<>();
			source.whenComplete((value, error) -> {
				if (source.isCancelled()) {
					result.cancel(true);
				}
				else if (error != null) {
					result.completeExceptionally(error);
				}
				else {
					result.complete(ToolResult.text(value));
				}
			});
			result.whenComplete((value, error) -> {
				if (result.isCancelled()) {
					source.cancel(true);
				}
			});
			return result;
		});
	}

	private static void verifyErrorPolicy(Tool failing, boolean parallel, boolean wrapAsync) throws Exception {
		verifyErrorPolicy(failing, parallel, wrapAsync, "backend unavailable");
	}

	private static void verifyErrorPolicy(Tool failing, boolean parallel, boolean wrapAsync, String expectedFailure)
			throws Exception {
		var output = builder(failing).parallelToolExecution(parallel)
			.toolExecutionTimeout(Duration.ofMillis(150))
			.throwOnToolError(false)
			.build()
			.apply(state(call("one")), RunnableConfig.builder().build());
		assertThat(results(output).get(0).metadata()).containsEntry("tool.error", true);
		var node = builder(failing).parallelToolExecution(parallel)
			.toolExecutionTimeout(Duration.ofMillis(150))
			.throwOnToolError(true)
			.build();
		assertThatThrownBy(() -> node.apply(state(call("one")), RunnableConfig.builder().build()))
			.hasStackTraceContaining(expectedFailure);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void anUnknownToolPreventsReturnDirectForTheWholeBatch(boolean parallel) throws Exception {
		Tool direct = new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", "Lookup", "{}");
			}

			@Override
			public boolean returnDirect() {
				return true;
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("done"));
			}
		};
		Map<String, Object> output = builder(direct).parallelToolExecution(parallel)
			.build()
			.apply(state(call("known"), new ToolCall("unknown", "missing", "{}")), RunnableConfig.builder().build());
		ChatMessage message = results(output).get(0);
		assertThat(message.metadata()).doesNotContainKey(
				io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectConstants.FINISH_REASON_METADATA_KEY);
		assertThat(results(output).get(1).metadata()).containsEntry("tool.error", true);
	}

	@Test
	void bindingSnapshotsAreScopedByAgentWithinOneGraphRun() throws Exception {
		RunnableConfig config = RunnableConfig.builder().build();
		new AgentToolBindingStore().publish(config, new AgentToolBindings(new ToolRegistry(List.of())));
		AgentToolNode node = builder(
				tool("lookup", (call, context) -> CompletableFuture.completedFuture(ToolResult.text("configured"))))
			.agentName("current")
			.build();
		assertThat(results(node.apply(state(call("one")), config)).get(0).text()).isEqualTo("configured");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void propagatesContextAndIsolatesToolUpdates(boolean parallel) throws Exception {
		RunnableConfig config = RunnableConfig.builder().addMetadata("request", "request-1").build();
		AtomicInteger executions = new AtomicInteger();
		Tool tool = tool("lookup", (call, context) -> {
			assertThat(context.attributes()).containsEntry("tenant", "tenant-1")
				.containsEntry("request", "request-1")
				.containsEntry(AGENT_CONFIG_CONTEXT_KEY, config);
			assertThat(context.attributes().get(AGENT_STATE_CONTEXT_KEY)).isInstanceOf(OverAllState.class);
			updates(context).put(call.id(), "saved");
			executions.incrementAndGet();
			return CompletableFuture.completedFuture(ToolResult.text(call.id()));
		});
		AgentToolNode node = builder(tool).parallelToolExecution(parallel)
			.toolContext(Map.of("tenant", "tenant-1"))
			.build();
		Map<String, Object> result = node.apply(state(call("one"), call("two")), config);

		assertThat(executions).hasValue(2);
		assertThat(result).containsEntry("one", "saved").containsEntry("two", "saved");
		assertThat(results(result)).extracting(ChatMessage::text).containsExactly("one", "two");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void timeoutCancelsOriginalStageAndDiscardsOnlyFailedUpdates(boolean parallel) throws Exception {
		CompletableFuture<ToolResult> stalled = new CompletableFuture<>();
		CountDownLatch cancelled = new CountDownLatch(1);
		stalled.whenComplete((value, error) -> cancelled.countDown());
		Tool tool = tool("lookup", (call, context) -> {
			updates(context).put(call.id(), "saved");
			return "good".equals(call.id()) ? CompletableFuture.completedFuture(ToolResult.text("ok")) : stalled;
		});
		AgentToolNode node = builder(tool).parallelToolExecution(parallel)
			.toolExecutionTimeout(Duration.ofMillis(150))
			.build();
		Map<String, Object> result = node.apply(state(call("good"), call("bad")), RunnableConfig.builder().build());

		assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(stalled).isCancelled();
		assertThat(result).containsEntry("good", "saved").doesNotContainKey("bad");
		assertThat(results(result)).hasSize(2);
		assertThat(results(result).get(1).metadata()).containsEntry("tool.error", true);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void timeoutAlsoInterruptsBlockingInvocationBeforeItReturnsAStage(boolean parallel) throws Exception {
		CountDownLatch interrupted = new CountDownLatch(1);
		CompletableFuture<ToolResult> lateStage = new CompletableFuture<>();
		CountDownLatch cancelled = new CountDownLatch(1);
		lateStage.whenComplete((value, error) -> cancelled.countDown());
		Tool tool = tool("lookup", (call, context) -> {
			if ("good".equals(call.id())) {
				updates(context).put("good", "saved");
				return CompletableFuture.completedFuture(ToolResult.text("ok"));
			}
			updates(context).put("bad", "discarded");
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException failure) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
			}
			updates(context).put("late", "discarded");
			return lateStage;
		});
		AgentToolNode node = builder(tool).parallelToolExecution(parallel)
			.toolExecutionTimeout(Duration.ofMillis(150))
			.build();
		Map<String, Object> result = node.apply(state(call("good"), call("bad")), RunnableConfig.builder().build());

		assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(lateStage).isCancelled();
		assertThat(result).containsEntry("good", "saved").doesNotContainKeys("bad", "late");
		assertThat(results(result).get(1).metadata()).containsEntry("tool.error", true);
	}

	@Test
	void neutralDispatchCannotStarveASingleThreadGraphExecutor() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Tool tool = tool("lookup",
					(call, context) -> CompletableFuture.completedFuture(ToolResult.text(call.id())));
			AgentToolNode node = builder(tool).parallelToolExecution(true)
				.toolExecutionTimeout(Duration.ofSeconds(2))
				.build();
			RunnableConfig config = RunnableConfig.builder()
				.addMetadata(io.github.agentic.ai.graph.internal.node.ParallelNode
					.formatNodeId(RunnableConfig.AGENT_TOOL_NAME), executor)
				.build();
			Map<String, Object> result = node.apply(state(call("one"), call("two")), config);
			assertThat(results(result)).extracting(ChatMessage::text).containsExactly("one", "two");
			assertThat(results(result))
				.allSatisfy(message -> assertThat(message.metadata()).doesNotContainEntry("tool.error", true));
		}
		finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void leaseLossCancelsOriginalStageAndFailsTheNode(boolean parallel) throws Exception {
		ManualGuard guard = new ManualGuard();
		CountDownLatch started = new CountDownLatch(parallel ? 2 : 1);
		List<CompletableFuture<ToolResult>> sources = new java.util.concurrent.CopyOnWriteArrayList<>();
		Tool tool = tool("lookup", (call, context) -> {
			CompletableFuture<ToolResult> source = new CompletableFuture<>();
			sources.add(source);
			started.countDown();
			return source;
		});
		AgentToolNode node = builder(tool).parallelToolExecution(parallel).build();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var execution = executor.submit(() -> node.apply(state(call("one"), call("two")),
					RunnableConfig.builder().executionGuard(guard).build()));
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
			guard.lose();
			var failure = assertThrows(java.util.concurrent.ExecutionException.class,
					() -> execution.get(2, TimeUnit.SECONDS));
			assertThat(failure.getCause()).isInstanceOf(LeaseLostException.class);
			assertThat(sources).allSatisfy(source -> assertThat(source).isCancelled());
			assertThat(guard.callbacks).isEmpty();
		}
		finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void interruptionCancelsSourceAndPreservesInterruptFlag() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CompletableFuture<ToolResult> source = new CompletableFuture<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		CompletableFuture<Map<String, Object>> output = new CompletableFuture<>();
		AgentToolNode node = builder(tool("lookup", (call, context) -> {
			updates(context).put("discarded", true);
			started.countDown();
			return source;
		})).build();
		Thread worker = new Thread(() -> {
			try {
				output.complete(node.apply(state(call("one")), RunnableConfig.builder().build()));
				interrupted.set(Thread.currentThread().isInterrupted());
			}
			catch (Throwable failure) {
				output.completeExceptionally(failure);
			}
		});
		worker.start();
		try {
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
			worker.interrupt();
			Map<String, Object> result = output.get(2, TimeUnit.SECONDS);
			worker.join(2000);
			assertThat(worker.isAlive()).isFalse();
			assertThat(interrupted).isTrue();
			assertThat(source).isCancelled();
			assertThat(result).doesNotContainKey("discarded");
			assertThat(results(result).get(0).metadata()).containsEntry("tool.error", true);
		}
		finally {
			worker.interrupt();
			worker.join(2000);
		}
	}

	@Test
	void fullResultSurvivesInternalProtocolAndCheckpointSerialization() throws Exception {
		ToolCall call = new ToolCall("one", "lookup", "{}");
		ToolResult output = new ToolResult(
				List.of(ContentPart.text("partial"), ContentPart.json("{\"ok\":false}"),
						ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2, 3 })),
				true, Map.of("origin", "backend"));
		AgentToolNode node = builder(tool("lookup", (invocation, context) -> CompletableFuture.completedFuture(output)))
			.build();
		Map<String, Object> result = node.apply(state(call("one")), RunnableConfig.builder().build());
		var serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		for (Map<String, Object> restored : List.of(result, serializer.dataFromBytes(serializer.dataToBytes(result)),
				serializer.cloneObject(result).data())) {
			ChatMessage message = results(restored).get(0);
			assertThat(message.toolCallId()).isEqualTo(call.id());
			assertThat(message.toolName()).isEqualTo(call.name());
			assertThat(message.content()).hasSize(3);
			assertThat(message.content().get(0)).isEqualTo(output.content().get(0));
			assertThat(message.content().get(1)).isEqualTo(output.content().get(1));
			assertThat(message.content().get(2).data()).containsExactly(1, 2, 3);
			assertThat(message.metadata()).containsEntry("origin", "backend").containsEntry("tool.error", true);
			var request = io.github.agentic.ai.api.chat.ChatRequest.of(results(restored));
			assertThat(request.messages()).containsExactly(message);
		}
	}

	@Test
	void partialResultsResumeWithoutRepeatingCompletedInvocations() throws Exception {
		AtomicInteger executions = new AtomicInteger();
		Tool tool = tool("lookup", (call, context) -> {
			assertThat(call.id()).isEqualTo("two");
			executions.incrementAndGet();
			return CompletableFuture.completedFuture(ToolResult.text("second"));
		});
		ChatMessage first = ToolCallResponse
			.from(new ToolCall("one", "lookup", "{}"),
					new ToolResult(List.of(ContentPart.json("{\"first\":true}")), false, Map.of("origin", "first")))
			.toMessage();
		List<ChatMessage> partial = AgentChatProtocol.toolResponse(List.of(first), Map.of("checkpoint", true));
		List<ChatMessage> history = new ArrayList<>();
		history.add(assistant(call("one"), call("two")));
		history.addAll(partial);
		AgentToolNode node = builder(tool).build();
		OverAllState resumed = new OverAllState(Map.of("messages", history));
		resumed.registerKeyAndStrategy("messages", new io.github.agentic.ai.graph.state.strategy.AppendStrategy());
		Map<String, Object> result = node.apply(resumed, RunnableConfig.builder().build());
		assertThat(executions).hasValue(1);
		resumed.updateState(result);
		List<ChatMessage> restored = resumed.<List<ChatMessage>>value("messages").orElseThrow();
		assertThat(restored).hasSize(3);
		assertThat(restored.get(1)).isEqualTo(partial.get(0));
		assertThat(restored.get(2)).isEqualTo(ToolResult.text("second").toMessage(new ToolCall("two", "lookup", "{}")));
		assertThat(node.apply(resumed, RunnableConfig.builder().build())).isEmpty();
		assertThat(executions).hasValue(1);
	}

	@Test
	void validatesArgumentsBeforeDispatch() throws Exception {
		AtomicInteger executions = new AtomicInteger();
		AgentToolNode node = builder(tool("lookup", (call, context) -> {
			executions.incrementAndGet();
			return CompletableFuture.completedFuture(ToolResult.text("unexpected"));
		})).validateToolArguments(true).build();
		var invalid = new ToolCall("one", "lookup", "{\"query\":42}");
		Map<String, Object> result = node.apply(state(invalid), RunnableConfig.builder().build());

		assertThat(executions).hasValue(0);
		assertThat(results(result).get(0).metadata()).containsEntry("tool.error", true).containsKey("errorCode");
	}

	@Test
	void nullStageNullResultAndInvocationFailureBecomeErrorResults() throws Exception {
		for (BiFunction<ToolCall, ToolContext, CompletionStage<ToolResult>> execute : List
			.<BiFunction<ToolCall, ToolContext, CompletionStage<ToolResult>>>of((call, context) -> null,
					(call, context) -> CompletableFuture.completedFuture(null), (call, context) -> {
						throw new IllegalStateException("backend unavailable");
					})) {
			var result = builder(tool("lookup", execute)).build()
				.apply(state(call("one")), RunnableConfig.builder().build());
			assertThat(results(result).get(0).metadata()).containsEntry("tool.error", true);
		}
	}

	@Test
	void rejectsNonPositiveTimeout() {
		assertThatThrownBy(() -> builder().toolExecutionTimeout(Duration.ZERO).build())
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> builder().toolExecutionTimeout(Duration.ofNanos(-1)).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static AgentToolNode.Builder builder(Tool... tools) {
		return AgentToolNode.builder()
			.agentName("neutral-tools")
			.toolRegistry(new ToolRegistry(List.of(tools)))
			.toolExecutionTimeout(Duration.ofSeconds(5));
	}

	private static Tool tool(String name, BiFunction<ToolCall, ToolContext, CompletionStage<ToolResult>> execute) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, "Lookup",
						"{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return execute.apply(call, context);
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> updates(ToolContext context) {
		return (Map<String, Object>) context.attributes().get(AGENT_STATE_FOR_UPDATE_CONTEXT_KEY);
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> results(Map<String, Object> result) {
		return (List<ChatMessage>) result.get("messages");
	}

	private static ToolCall call(String id) {
		return new ToolCall(id, "lookup", "{}");
	}

	private static ChatMessage assistant(ToolCall... calls) {
		return ChatMessage.assistant("", List.of(calls));
	}

	private static OverAllState state(ToolCall... calls) {
		return new OverAllState(Map.of("messages", List.of(assistant(calls))));
	}

	private static final class ManualGuard implements ExecutionGuard {

		private final List<Runnable> callbacks = new ArrayList<>();

		private LeaseLostException loss;

		@Override
		public synchronized void assertActive() {
			if (loss != null) {
				throw loss;
			}
		}

		@Override
		public synchronized AutoCloseable onLoss(Runnable cancellation) {
			if (loss != null) {
				cancellation.run();
			}
			else {
				callbacks.add(cancellation);
			}
			return () -> {
				synchronized (ManualGuard.this) {
					callbacks.remove(cancellation);
				}
			};
		}

		synchronized void lose() {
			loss = new LeaseLostException("neutral-tools", UUID.randomUUID(), 1, "Test lease loss");
			List<Runnable> pending = List.copyOf(callbacks);
			callbacks.clear();
			pending.forEach(Runnable::run);
		}

	}

}
