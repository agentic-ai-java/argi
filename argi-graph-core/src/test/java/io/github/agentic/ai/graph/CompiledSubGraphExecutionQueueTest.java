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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointExecutionQueue;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.internal.node.SubCompiledGraphNodeAction;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.outputKeyToParent;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.resumeSubGraphId;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class CompiledSubGraphExecutionQueueTest {

	@Test
	void resumedChildUpdatesCheckpointOnlyAfterItsQueuedTurnStarts() throws Exception {
		final MemorySaver saver = new MemorySaver();
		final RunnableConfig childConfig = config("shared_subgraph_nested");
		seed(saver, childConfig);
		final CompiledGraph child = childGraph(saver);
		final Disposable blocker = holdThread(saver, childConfig);
		CompletableFuture<List<GraphResponse<NodeOutput>>> pending = null;
		try {
			final Flux<GraphResponse<NodeOutput>> childStream = resumedChildStream(child, saver);
			assertEquals(List.of("seed"), latestMessages(saver, childConfig),
					"Constructing a resumed subgraph must not write its checkpoint");
			pending = childStream.collectList().toFuture();
			assertFalse(pending.isDone());
			assertEquals(List.of("seed"), latestMessages(saver, childConfig),
					"A queued child must not update state before acquiring its permit");

			// Simulate a final checkpoint from the active child-thread owner. The
			// queued resume must merge into this state, not the earlier seed.
			saver.put(childConfig, Checkpoint.builder().nodeId(START).nextNodeId("append")
				.state(Map.of("messages", List.of("seed", "active"))).build());
			blocker.dispose();
			pending.get(5, TimeUnit.SECONDS);
			assertEquals(List.of("seed", "active", "parent", "child"),
					latestMessages(saver, childConfig));
		}
		finally {
			if (pending != null) {
				pending.cancel(true);
			}
			blocker.dispose();
		}
	}

	@Test
	void cancellingQueuedChildResumeDoesNotWriteParentState() throws Exception {
		final MemorySaver saver = new MemorySaver();
		final RunnableConfig childConfig = config("shared_subgraph_nested");
		seed(saver, childConfig);
		final CompiledGraph child = childGraph(saver);
		final Disposable blocker = holdThread(saver, childConfig);
		final CompletableFuture<List<GraphResponse<NodeOutput>>> pending =
				resumedChildStream(child, saver).collectList().toFuture();
		try {
			assertFalse(pending.isDone());
			assertTrue(pending.cancel(true));
			blocker.dispose();

			child.stream(Map.of(), childConfig).last().toFuture().get(5, TimeUnit.SECONDS);
			assertEquals(List.of("seed", "child"), latestMessages(saver, childConfig),
					"Cancelled queued work must not apply its parent-state update");
		}
		finally {
			pending.cancel(true);
			blocker.dispose();
		}
	}

	@Test
	void nestedGraphsSharingSaverCompleteAcrossAsyncNodeBoundary() throws Exception {
		final MemorySaver saver = new MemorySaver();
		final CompletableFuture<Map<String, Object>> leafCompletion = new CompletableFuture<>();
		final AtomicBoolean leafEntered = new AtomicBoolean();
		final CompiledGraph leaf = new StateGraph(CompiledSubGraphExecutionQueueTest::strategies)
			.addNode("leaf", (state, runnableConfig) -> {
				leafEntered.set(true);
				return leafCompletion;
			})
			.addEdge(START, "leaf").addEdge("leaf", END)
			.compile(compileConfig(saver));
		final CompiledGraph middle = new StateGraph(CompiledSubGraphExecutionQueueTest::strategies)
			.addNode("inner", leaf).addEdge(START, "inner").addEdge("inner", END)
			.compile(compileConfig(saver));
		final CompiledGraph parent = new StateGraph(CompiledSubGraphExecutionQueueTest::strategies)
			.addNode("outer", middle).addEdge(START, "outer").addEdge("outer", END)
			.compile(compileConfig(saver));
		final CompletableFuture<NodeOutput> result = parent
			.stream(Map.of("messages", List.of("seed")), config("nested"))
			.last().toFuture();
		try {
			assertTrue(leafEntered.get(), "Nested child must acquire its own thread permit");
			assertFalse(result.isDone());
			leafCompletion.complete(Map.of("messages", List.of("leaf")));
			final NodeOutput output = result.get(5, TimeUnit.SECONDS);
			assertTrue(output.isEND());
			assertEquals(List.of("seed", "leaf"), output.state().value("messages").orElseThrow());
			assertEquals(List.of("seed", "leaf"), latestMessages(saver, config("nested")));
			assertEquals(List.of("seed", "leaf"),
					latestMessages(saver, config("nested_subgraph_outer_subgraph_inner")));
		}
		finally {
			result.cancel(true);
			leafCompletion.cancel(true);
		}
	}

	private static CompiledGraph childGraph(MemorySaver saver) throws Exception {
		return new StateGraph(CompiledSubGraphExecutionQueueTest::strategies)
			.addNode("append", (state, config) -> completedFuture(Map.of("messages", List.of("child"))))
			.addEdge(START, "append").addEdge("append", END)
			.compile(compileConfig(saver));
	}

	@SuppressWarnings("unchecked")
	private static Flux<GraphResponse<NodeOutput>> resumedChildStream(CompiledGraph child, MemorySaver saver)
			throws Exception {
		final SubCompiledGraphNodeAction action = new SubCompiledGraphNodeAction("nested", compileConfig(saver), child);
		final RunnableConfig parentConfig = RunnableConfig.builder(config("shared"))
			.addMetadata(resumeSubGraphId("nested"), true).build();
		final OverAllState parentState = OverAllStateBuilder.builder().withKeyStrategies(strategies())
			.withData(Map.of("messages", List.of("parent"))).build();
		return (Flux<GraphResponse<NodeOutput>>) action.apply(parentState, parentConfig)
			.get(5, TimeUnit.SECONDS).get(outputKeyToParent("nested"));
	}

	private static Disposable holdThread(MemorySaver saver, RunnableConfig config) {
		final AtomicBoolean entered = new AtomicBoolean();
		final Disposable blocker = CheckpointExecutionQueue.serialize(saver, config, () -> {
			entered.set(true);
			return Flux.never();
		}).subscribe();
		assertTrue(entered.get(), "The checkpoint thread must be held before queuing the child");
		return blocker;
	}

	private static void seed(MemorySaver saver, RunnableConfig config) throws Exception {
		saver.put(config, Checkpoint.builder().nodeId(START).nextNodeId("append")
			.state(Map.of("messages", List.of("seed"))).build());
	}

	private static Map<String, KeyStrategy> strategies() {
		return Map.of("messages", new AppendStrategy(false));
	}

	private static CompileConfig compileConfig(MemorySaver saver) {
		return CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build();
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	private static Object latestMessages(MemorySaver saver, RunnableConfig config) {
		return saver.get(config).orElseThrow().getState().get("messages");
	}

}
