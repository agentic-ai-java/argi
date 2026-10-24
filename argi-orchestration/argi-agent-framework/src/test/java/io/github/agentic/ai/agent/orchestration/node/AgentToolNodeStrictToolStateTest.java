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
package io.github.agentic.ai.agent.orchestration.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchSpec;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchViolationException;
import io.github.agentic.ai.agent.tool.patch.strict.StrictMergeRules;
import io.github.agentic.ai.workflow.graph.KeyStrategy;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.RunnableConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants.AGENT_STATE_CONTEXT_KEY;
import static io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Strict tool-state mode ({@code strictToolState(true)}) on {@link AgentToolNode}: tools
 * see a read-only state view, failed calls never contribute state, sequential merges
 * honor the declared KeyStrategies, and parallel merges reject multi-writer keys without
 * a declared strategy — with completion order never changing the merged result. Legacy
 * pins (strict off) guard the default path. All scenarios run without a model.
 */
@Timeout(10)
class AgentToolNodeStrictToolStateTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	// === sequential: strict merge semantics ===

	@Test
	void sequentialStrictMergesThroughDeclaredKeyStrategy() throws Exception {
		var strategies = Map.of("shared", KeyStrategy.APPEND);
		var output = execute(false, true, null, strategies, writer("t0", "shared", List.of("v0")),
				writer("t1", "shared", List.of("v1")));
		assertThat(output).containsEntry("shared", List.of("v0", "v1"));
		assertThat(messages(output)).hasSize(2);
	}

	@Test
	void sequentialLegacyLastWriteWinsWhenStrictOff() throws Exception {
		// Declaring APPEND here must NOT change legacy sequential: the strategy map
		// is only consulted in strict mode (the divergence this slice closes).
		var output = execute(false, false, null, Map.of("shared", KeyStrategy.APPEND), writer("t0", "shared", "v0"),
				writer("t1", "shared", "v1"));
		assertThat(output).containsEntry("shared", "v1");
	}

	@Test
	void sequentialStrictDiscardsFailedCallPatchWithoutPolicy() throws Exception {
		Tool failing = writing("failing", "leak", "leaked", CompletableFuture.completedFuture(ToolResult.error("boom")));
		var output = execute(false, true, null, null, failing);
		assertThat(output).doesNotContainKey("leak");
		assertThat(messages(output)).hasSize(1);
	}

	@Test
	void sequentialStrictRejectsSharedCollectionMutation() throws Exception {
		var output = execute(false, true, null, null, mutatingTool("mutator"));
		assertThat(output).doesNotContainKey("leak");
		assertThat(messages(output)).hasSize(1);
		assertThat(messages(output).get(0).metadata()).containsEntry("failureKind", "execution");
	}

	@Test
	void sequentialStrictValidatesPatchAgainstPolicy() throws Exception {
		var spec = ToolContextPatchSpec.builder().writableKey("allowed").keyType("allowed", String.class).build();
		var policy = ToolContextPatchPolicy.builder().specFor("t0", spec).build();
		Tool undeclared = writingEntries("t0", Map.of("allowed", "fine", "other", "value"),
				CompletableFuture.completedFuture(ToolResult.text("done")));
		var output = execute(false, true, policy, null, undeclared);
		assertThat(output).doesNotContainKey("allowed").doesNotContainKey("other");
		assertThat(messages(output)).hasSize(1);
	}

	// === parallel: deterministic merge under forced completion orders ===

	@Test
	void parallelStrictAppendIsCompletionOrderIndependent() throws Exception {
		var strategies = Map.of("shared", KeyStrategy.APPEND);
		BiFunction<Integer, ToolContext, Map<String, Object>> patch = (index,
				context) -> Map.<String, Object>of("shared", List.of("v" + index));
		var reversed = executeParallelOrdered(true, null, strategies, patch, new int[] { 2, 1, 0 });
		var rotated = executeParallelOrdered(true, null, strategies, patch, new int[] { 1, 2, 0 });
		assertThat(reversed).containsEntry("shared", List.of("v0", "v1", "v2"));
		assertThat(rotated).containsEntry("shared", List.of("v0", "v1", "v2"));
	}

	@Test
	void parallelStrictMergeIsCompletionOrderIndependent() throws Exception {
		var strategies = Map.of("merged", KeyStrategy.MERGE);
		BiFunction<Integer, ToolContext, Map<String, Object>> patch = (index,
				context) -> Map.<String, Object>of("merged", Map.of("k" + index, index));
		var output = executeParallelOrdered(true, null, strategies, patch, new int[] { 2, 0, 1 });
		assertThat(output).containsEntry("merged", Map.of("k0", 0, "k1", 1, "k2", 2));
	}

	@Test
	void parallelStrictSingleWriterKeepsReplaceDefault() throws Exception {
		BiFunction<Integer, ToolContext, Map<String, Object>> patch = (index, context) -> index == 0
				? Map.<String, Object>of("onlyA", "a") : Map.<String, Object>of("onlyB", "b");
		var output = executeParallelOrdered(true, null, null, patch, new int[] { 1, 0 });
		assertThat(output).containsEntry("onlyA", "a").containsEntry("onlyB", "b");
	}

	@Test
	void parallelStrictDiscardsEveryPatchOnUndeclaredConflict() throws Exception {
		BiFunction<Integer, ToolContext, Map<String, Object>> patch = (index, context) -> index == 0
				? Map.<String, Object>of("conflict", "x", "onlyA", "a")
				: Map.<String, Object>of("conflict", "y", "onlyB", "b");
		var output = executeParallelOrdered(true, null, null, patch, new int[] { 1, 0 });
		assertThat(output).doesNotContainKey("conflict").doesNotContainKey("onlyA").doesNotContainKey("onlyB");
		assertThat(messages(output)).extracting(ChatMessage::text).containsExactly("done", "done");
	}

	@Test
	void parallelStrictDiscardsPatchesForEveryConflictingKey() throws Exception {
		// Two undeclared conflicts (c1 by tools 0/1, c2 by tools 2/3): every patch
		// touching ANY conflicting key must be dropped, not just the first key's.
		BiFunction<Integer, ToolContext, Map<String, Object>> patch = (index, context) -> switch (index) {
			case 0 -> Map.<String, Object>of("c1", "a", "onlyA", "a");
			case 1 -> Map.<String, Object>of("c1", "b");
			case 2 -> Map.<String, Object>of("c2", "c", "onlyC", "c");
			default -> Map.<String, Object>of("c2", "d");
		};
		var output = executeParallelOrdered(true, null, null, patch, new int[] { 3, 1, 2, 0 });
		assertThat(output).doesNotContainKey("c1").doesNotContainKey("c2")
			.doesNotContainKey("onlyA")
			.doesNotContainKey("onlyC");
		assertThat(messages(output)).extracting(ChatMessage::text).containsExactly("done", "done", "done",
				"done");
	}

	@Test
	void parallelStrictAbortsOnUndeclaredConflictWhenPolicyAborts() throws Exception {
		var policy = ToolContextPatchPolicy.builder()
			.onViolation(ToolContextPatchPolicy.ViolationPolicy.ABORT_RUN)
			.build();
		BiFunction<Integer, ToolContext, Map<String, Object>> patch = (index,
				context) -> Map.<String, Object>of("conflict", "v" + index);
		assertThatThrownBy(() -> executeParallelOrdered(true, policy, null, patch, new int[] { 0, 1 }))
			.isInstanceOf(ToolContextPatchViolationException.class)
			.hasMessageContaining("conflict")
			.satisfies(failure -> assertThat(((ToolContextPatchViolationException) failure).failure().errorCode())
				.isEqualTo(StrictMergeRules.UNDECLARED_CONFLICT));
	}

	@Test
	void parallelStrictConflictResolvedWhenOneWriterFailed() throws Exception {
		Tool failing = writing("failing", "conflict", "bad", CompletableFuture.completedFuture(ToolResult.error("boom")));
		Tool healthy = writing("healthy", "conflict", "good", CompletableFuture.completedFuture(ToolResult.text("done")));
		var output = execute(true, true, null, null, failing, healthy);
		assertThat(output).containsEntry("conflict", "good");
		assertThat(messages(output)).hasSize(2);
	}

	@Test
	void parallelStrictRejectsSharedCollectionMutation() throws Exception {
		var output = execute(true, true, null, null, mutatingTool("mutator"), writer("writer", "kept", "fine"));
		assertThat(output).containsEntry("kept", "fine").doesNotContainKey("leak");
		assertThat(messages(output)).hasSize(2);
		assertThat(messages(output).get(0).metadata()).containsEntry("failureKind", "execution");
	}

	// === legacy pins: strict off, behavior byte-identical ===

	@Test
	void parallelLegacyReplacesUndeclaredKeyWhenStrictOff() throws Exception {
		var output = execute(true, false, null, null, writer("t0", "shared", "v0"), writer("t1", "shared", "v1"));
		assertThat(output).containsEntry("shared", "v1");
	}

	@Test
	void parallelLegacyAppendsWhenStrictOff() throws Exception {
		var strategies = Map.of("shared", KeyStrategy.APPEND);
		var output = execute(true, false, null, strategies, writer("t0", "shared", List.of("v0")),
				writer("t1", "shared", List.of("v1")));
		assertThat(output).containsEntry("shared", List.of("v0", "v1"));
	}

	// === harness ===

	/**
	 * Runs a parallel batch whose tool-call completion order is forced: the node runs on
	 * a side thread while this thread completes each call's future in the given order
	 * once all tools have written their patches.
	 */
	private static Map<String, Object> executeParallelOrdered(boolean strict, ToolContextPatchPolicy policy,
			Map<String, KeyStrategy> strategies, BiFunction<Integer, ToolContext, Map<String, Object>> patchFactory,
			int[] completionOrder) throws Exception {
		int toolCount = completionOrder.length;
		var futures = new ConcurrentHashMap<Integer, CompletableFuture<ToolResult>>();
		var started = new CountDownLatch(toolCount);
		var tools = new Tool[toolCount];
		for (int index = 0; index < toolCount; index++) {
			final int toolIndex = index;
			tools[toolIndex] = tool("tool-" + toolIndex, (call, context) -> {
				updates(context).putAll(patchFactory.apply(toolIndex, context));
				var future = new CompletableFuture<ToolResult>();
				futures.put(toolIndex, future);
				started.countDown();
				return future;
			});
		}
		var calls = new ArrayList<ToolCall>();
		for (int index = 0; index < toolCount; index++) {
			calls.add(new ToolCall("call-" + index, tools[index].definition().name(), "{}"));
		}
		var builder = nodeBuilder(true, strict, policy, tools);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Map<String, Object>> output = executor
				.submit(() -> builder.build().apply(state(calls, strategies), RunnableConfig.builder().build()));
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			for (int order : completionOrder) {
				futures.get(order).complete(ToolResult.text("done"));
			}
			return output.get(5, TimeUnit.SECONDS);
		}
		catch (ExecutionException failure) {
			throw failure.getCause() instanceof Exception cause ? cause : failure;
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static Map<String, Object> execute(boolean parallel, boolean strict, ToolContextPatchPolicy policy,
			Map<String, KeyStrategy> strategies, Tool... tools) throws Exception {
		var calls = new ArrayList<ToolCall>();
		for (int index = 0; index < tools.length; index++) {
			calls.add(new ToolCall("call-" + index, tools[index].definition().name(), "{}"));
		}
		return nodeBuilder(parallel, strict, policy, tools).build()
			.apply(state(calls, strategies), RunnableConfig.builder().build());
	}

	private static AgentToolNode.Builder nodeBuilder(boolean parallel, boolean strict, ToolContextPatchPolicy policy,
			Tool... tools) {
		var builder = AgentToolNode.builder()
			.toolRegistry(new ToolRegistry(List.of(tools)))
			.parallelToolExecution(parallel)
			.throwOnToolError(false)
			.toolExecutionTimeout(TIMEOUT)
			.strictToolState(strict);
		if (policy != null) {
			builder.toolContextPatchPolicy(policy);
		}
		return builder;
	}

	private static OverAllState state(List<ToolCall> calls, Map<String, KeyStrategy> strategies) {
		var state = new OverAllState(Map.of("messages", new ArrayList<>(List.of(ChatMessage.assistant("", calls)))));
		if (strategies != null) {
			state.registerKeyAndStrategy(strategies);
		}
		return state;
	}

	private static Tool writer(String name, String key, Object value) {
		return writing(name, key, value, CompletableFuture.completedFuture(ToolResult.text("done")));
	}

	private static Tool writing(String name, String key, Object value, CompletionStage<ToolResult> stage) {
		return writingEntries(name, Map.of(key, value), stage);
	}

	private static Tool writingEntries(String name, Map<String, Object> entries, CompletionStage<ToolResult> stage) {
		return tool(name, (call, context) -> {
			updates(context).putAll(entries);
			return stage;
		});
	}

	/**
	 * A tool that first writes a patch entry, then attempts to mutate the shared
	 * {@code messages} collection reached through the state — the shallow-copy hazard
	 * strict mode must reject.
	 */
	private static Tool mutatingTool(String name) {
		return tool(name, (call, context) -> {
			updates(context).put("leak", "leaked");
			var visible = (OverAllState) context.attributes().get(AGENT_STATE_CONTEXT_KEY);
			@SuppressWarnings("unchecked")
			List<Object> shared = (List<Object>) visible.data().get("messages");
			shared.add("hijack");
			return CompletableFuture.completedFuture(ToolResult.text("done"));
		});
	}

	private static Tool tool(String name, BiFunction<ToolCall, ToolContext, CompletionStage<ToolResult>> execution) {
		return new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition(name, name, "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return execution.apply(call, context);
				}
		};
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> updates(ToolContext context) {
		return (Map<String, Object>) context.attributes().get(AGENT_STATE_FOR_UPDATE_CONTEXT_KEY);
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messages(Map<String, Object> output) {
		return (List<ChatMessage>) output.get("messages");
	}

}
