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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.graph.action.AsyncNodeAction;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for parallel node aggregation strategies (ANY_OF and ALL_OF).
 * This test class focuses on testing the behavior of parallel nodes with different
 * aggregation strategies, including scenarios with streaming nodes (Flux).
 */
public class StateGraphParallelTest {

	private static final Logger log = LoggerFactory.getLogger(StateGraphParallelTest.class);

	private KeyStrategyFactory createKeyStrategyFactory() {
		return () -> {
			Map<String, KeyStrategy> keyStrategyMap = new HashMap<>();
			keyStrategyMap.put("messages", new AppendStrategy());
			keyStrategyMap.put("stream", new AppendStrategy());
			keyStrategyMap.put("nodeId", new ReplaceStrategy());
			return keyStrategyMap;
		};
	}

	private AsyncNodeAction makeNode(String id) {
		return node_async(state -> {
			log.info("call node {}", id);
			return Map.of("messages", id);
		});
	}

	private AsyncNodeAction immediateNode(String id) {
		return node_async(state -> {
			log.info("call node {}", id);
			return Map.of("messages", id, "nodeId", id);
		});
	}

	private AsyncNodeAction controlledNode(String id, CountDownLatch entered,
			CountDownLatch release, AtomicBoolean interrupted) {
		return node_async(state -> {
			entered.countDown();
			try {
				release.await();
			}
			catch (InterruptedException ex) {
				interrupted.set(true);
				Thread.currentThread().interrupt();
				throw new RuntimeException(ex);
			}
			return Map.of("messages", id, "nodeId", id);
		});
	}

	private AsyncNodeAction immediateStreamingNode(String id, String... values) {
		return node_async(state -> {
			log.info("call streaming node {}", id);
			Flux<String> flux = Flux.fromArray(values)
					.map(value -> id + ":" + value);
			return Map.of("stream", flux, "nodeId", id);
		});
	}

	private AsyncNodeAction controlledStreamingNode(String id, CountDownLatch entered,
			CountDownLatch release, AtomicBoolean interrupted, String... values) {
		return node_async(state -> {
			entered.countDown();
			try {
				release.await();
			}
			catch (InterruptedException ex) {
				interrupted.set(true);
				Thread.currentThread().interrupt();
				throw new RuntimeException(ex);
			}
			Flux<String> flux = Flux.fromArray(values)
					.map(value -> id + ":" + value);
			return Map.of("stream", flux, "nodeId", id);
		});
	}

	private void releaseAndShutdown(CountDownLatch release, ExecutorService executor) {
		release.countDown();
		executor.shutdown();
		try {
			executor.awaitTermination(1, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Tests ANY_OF aggregation strategy - should proceed with the first completed branch.
	 * This test verifies that when ANY_OF strategy is configured, only the result from
	 * the fastest branch is used, and execution continues immediately without waiting
	 * for slower branches.
	 */
	@Test
	void testParallelNodeAggregationStrategyAnyOf() throws Exception {
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean(false);
		ExecutorService executor = Executors.newFixedThreadPool(3);

		var workflow = new StateGraph(createKeyStrategyFactory())
				.addNode("fastNode", immediateNode("fastNode"))
				.addNode("slowNode1", controlledNode("slowNode1", entered, release, interrupted))
				.addNode("slowNode2", controlledNode("slowNode2", entered, release, interrupted))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "fastNode")
				.addEdge(START, "slowNode1")
				.addEdge(START, "slowNode2")
				.addEdge("fastNode", "merge")
				.addEdge("slowNode1", "merge")
				.addEdge("slowNode2", "merge")
				.addEdge("merge", END);

		var app = workflow.compile();
		AtomicReference<OverAllState> finalState = new AtomicReference<>();

		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					app.stream(Map.of(),
							RunnableConfig.builder()
									.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ANY_OF)
									.addParallelNodeExecutor(START, executor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalState::set)
							.blockLast());

			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalState.get(), "Final state should not be null");
			assertEquals("fastNode", finalState.get().value("nodeId").orElseThrow());
			assertFalse(run.isCompletedExceptionally());
			List<String> messages = (List<String>) finalState.get().value("messages").orElse(List.of());
			assertTrue(messages.contains("fastNode"),
					"Result should contain fastNode (the first completed branch)");
			assertFalse(messages.contains("slowNode1") || messages.contains("slowNode2"),
					"Result should NOT contain slow nodes with ANY_OF strategy (got: " + messages + ")");
			assertTrue(messages.contains("merge"), "Result should contain merge node");
		}
		finally {
			releaseAndShutdown(release, executor);
		}
	}

	/**
	 * Tests ALL_OF aggregation strategy - should wait for all branches to complete.
	 * This test verifies that when ALL_OF strategy is configured (or default),
	 * all parallel branches complete before proceeding.
	 */
	@Test
	void testParallelNodeAggregationStrategyAllOf() throws Exception {
		CountDownLatch entered = new CountDownLatch(3);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean(false);
		ExecutorService executor = Executors.newFixedThreadPool(3);

		var workflow = new StateGraph(createKeyStrategyFactory())
				.addNode("fastNode", controlledNode("fastNode", entered, release, interrupted))
				.addNode("slowNode1", controlledNode("slowNode1", entered, release, interrupted))
				.addNode("slowNode2", controlledNode("slowNode2", entered, release, interrupted))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "fastNode")
				.addEdge(START, "slowNode1")
				.addEdge(START, "slowNode2")
				.addEdge("fastNode", "merge")
				.addEdge("slowNode1", "merge")
				.addEdge("slowNode2", "merge")
				.addEdge("merge", END);

		var app = workflow.compile();
		AtomicReference<OverAllState> finalState = new AtomicReference<>();

		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					app.stream(Map.of(),
							RunnableConfig.builder()
									.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ALL_OF)
									.addParallelNodeExecutor(START, executor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalState::set)
							.blockLast());

			assertTrue(entered.await(2, TimeUnit.SECONDS));
			assertFalse(run.isDone(), "ALL_OF must wait while one branch is blocked");
			release.countDown();
			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalState.get(), "Final state should not be null");
			List<String> messages = (List<String>) finalState.get().value("messages").orElse(List.of());
			assertTrue(messages.containsAll(List.of("fastNode", "slowNode1", "slowNode2", "merge")),
					"Result should contain every branch and merge node (got: " + messages + ")");
		}
		finally {
			releaseAndShutdown(release, executor);
		}
	}

	/**
	 * Tests default aggregation strategy (ALL_OF) when no strategy is explicitly configured.
	 */
	@Test
	void testParallelNodeAggregationStrategyDefault() throws Exception {
		CountDownLatch entered = new CountDownLatch(3);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean(false);
		ExecutorService executor = Executors.newFixedThreadPool(3);

		var workflow = new StateGraph(createKeyStrategyFactory())
				.addNode("node1", controlledNode("node1", entered, release, interrupted))
				.addNode("node2", controlledNode("node2", entered, release, interrupted))
				.addNode("node3", controlledNode("node3", entered, release, interrupted))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "node1")
				.addEdge(START, "node2")
				.addEdge(START, "node3")
				.addEdge("node1", "merge")
				.addEdge("node2", "merge")
				.addEdge("node3", "merge")
				.addEdge("merge", END);

		var app = workflow.compile();
		AtomicReference<OverAllState> finalState = new AtomicReference<>();

		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					app.stream(Map.of(),
							RunnableConfig.builder()
									.addParallelNodeExecutor(START, executor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalState::set)
							.blockLast());

			assertTrue(entered.await(2, TimeUnit.SECONDS));
			assertFalse(run.isDone(), "Default aggregation must wait while one branch is blocked");
			release.countDown();
			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalState.get(), "Final state should not be null");
			List<String> messages = (List<String>) finalState.get().value("messages").orElse(List.of());
			assertTrue(messages.containsAll(List.of("node1", "node2", "node3", "merge")),
					"Result should contain every branch and merge node (got: " + messages + ")");
		}
		finally {
			releaseAndShutdown(release, executor);
		}
	}

	/**
	 * Tests default aggregation strategy configured via defaultParallelAggregationStrategy.
	 */
	@Test
	void testParallelNodeAggregationStrategyDefaultConfig() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean(false);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		var workflow = new StateGraph(createKeyStrategyFactory())
				.addNode("fastNode", immediateNode("fastNode"))
				.addNode("slowNode", controlledNode("slowNode", entered, release, interrupted))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "fastNode")
				.addEdge(START, "slowNode")
				.addEdge("fastNode", "merge")
				.addEdge("slowNode", "merge")
				.addEdge("merge", END);

		var app = workflow.compile();
		AtomicReference<OverAllState> finalState = new AtomicReference<>();

		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					app.stream(Map.of(),
							RunnableConfig.builder()
									.defaultParallelAggregationStrategy(NodeAggregationStrategy.ANY_OF)
									.addParallelNodeExecutor(START, executor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalState::set)
							.blockLast());

			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalState.get(), "Final state should not be null");
			assertEquals("fastNode", finalState.get().value("nodeId").orElseThrow());
			assertFalse(run.isCompletedExceptionally());
			List<String> messages = (List<String>) finalState.get().value("messages").orElse(List.of());
			assertTrue(messages.contains("fastNode"),
					"Result should contain fastNode (the first completed branch with ANY_OF)");
			assertFalse(messages.contains("slowNode"),
					"Result should NOT contain slowNode with default ANY_OF strategy (got: " + messages + ")");
		}
		finally {
			releaseAndShutdown(release, executor);
		}
	}

	/**
	 * Tests that merge node-specific strategy overrides default strategy.
	 * This test verifies that when both default and node-specific strategies are configured,
	 * the node-specific strategy takes precedence.
	 */
	@Test
	void testParallelNodeAggregationStrategyMergeNodeOverridesDefault() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean(false);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		var workflow = new StateGraph(createKeyStrategyFactory())
				.addNode("fastNode", immediateNode("fastNode"))
				.addNode("slowNode", controlledNode("slowNode", entered, release, interrupted))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "fastNode")
				.addEdge(START, "slowNode")
				.addEdge("fastNode", "merge")
				.addEdge("slowNode", "merge")
				.addEdge("merge", END);

		var app = workflow.compile();
		AtomicReference<OverAllState> finalState = new AtomicReference<>();

		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					app.stream(Map.of(),
							RunnableConfig.builder()
									.defaultParallelAggregationStrategy(NodeAggregationStrategy.ALL_OF)
									.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ANY_OF)
									.addParallelNodeExecutor(START, executor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalState::set)
							.blockLast());

			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalState.get(), "Final state should not be null");
			assertEquals("fastNode", finalState.get().value("nodeId").orElseThrow());
			assertFalse(run.isCompletedExceptionally());
			List<String> messages = (List<String>) finalState.get().value("messages").orElse(List.of());
			assertTrue(messages.contains("fastNode"),
					"Result should contain fastNode (merge node's ANY_OF overrides default ALL_OF)");
			assertFalse(messages.contains("slowNode"),
					"Result should NOT contain slowNode - merge node's ANY_OF should override default ALL_OF (got: " + messages + ")");
			assertTrue(messages.contains("merge"), "Result should contain merge node");
		}
		finally {
			releaseAndShutdown(release, executor);
		}
	}

	/**
	 * Tests parallel node aggregation strategy with streaming nodes (Flux).
	 * This test verifies that when parallel branches include streaming nodes,
	 * the aggregation strategy correctly handles Flux results.
	 */
	@Test
	void testParallelNodeAggregationStrategyWithStreamingNodes() throws Exception {
		CountDownLatch anyOfEntered = new CountDownLatch(2);
		CountDownLatch anyOfRelease = new CountDownLatch(1);
		AtomicBoolean anyOfInterrupted = new AtomicBoolean(false);
		ExecutorService anyOfExecutor = Executors.newFixedThreadPool(3);

		var workflow = new StateGraph(createKeyStrategyFactory())
				.addNode("streamingNode1", immediateStreamingNode("streamingNode1", "chunk1", "chunk2", "chunk3"))
				.addNode("normalNode", controlledNode("normalNode", anyOfEntered, anyOfRelease, anyOfInterrupted))
				.addNode("streamingNode2", controlledStreamingNode("streamingNode2", anyOfEntered, anyOfRelease,
						anyOfInterrupted, "chunkA", "chunkB"))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "streamingNode1")
				.addEdge(START, "normalNode")
				.addEdge(START, "streamingNode2")
				.addEdge("streamingNode1", "merge")
				.addEdge("normalNode", "merge")
				.addEdge("streamingNode2", "merge")
				.addEdge("merge", END);

		var app = workflow.compile();

		AtomicReference<OverAllState> finalStateAnyOf = new AtomicReference<>();
		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					app.stream(Map.of(),
							RunnableConfig.builder()
									.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ANY_OF)
									.addParallelNodeExecutor(START, anyOfExecutor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalStateAnyOf::set)
							.blockLast());

			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalStateAnyOf.get(), "Final state should not be null");
			assertEquals("streamingNode1", finalStateAnyOf.get().value("nodeId").orElseThrow());
			assertTrue(finalStateAnyOf.get().value("stream").isPresent(),
					"Result should contain streamingNode1's data (first completed)");
			List<String> messages = (List<String>) finalStateAnyOf.get().value("messages").orElse(List.of());
			assertFalse(messages.contains("normalNode"), "ANY_OF should ignore blocked normal branch");
		}
		finally {
			releaseAndShutdown(anyOfRelease, anyOfExecutor);
		}

		CountDownLatch allOfEntered = new CountDownLatch(3);
		CountDownLatch allOfRelease = new CountDownLatch(1);
		AtomicBoolean allOfInterrupted = new AtomicBoolean(false);
		ExecutorService allOfExecutor = Executors.newFixedThreadPool(3);
		var allOfWorkflow = new StateGraph(createKeyStrategyFactory())
				.addNode("streamingNode1", controlledStreamingNode("streamingNode1", allOfEntered, allOfRelease,
						allOfInterrupted, "chunk1", "chunk2", "chunk3"))
				.addNode("normalNode", controlledNode("normalNode", allOfEntered, allOfRelease, allOfInterrupted))
				.addNode("streamingNode2", controlledStreamingNode("streamingNode2", allOfEntered, allOfRelease,
						allOfInterrupted, "chunkA", "chunkB"))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "streamingNode1")
				.addEdge(START, "normalNode")
				.addEdge(START, "streamingNode2")
				.addEdge("streamingNode1", "merge")
				.addEdge("normalNode", "merge")
				.addEdge("streamingNode2", "merge")
				.addEdge("merge", END);
		var allOfApp = allOfWorkflow.compile();
		AtomicReference<OverAllState> finalStateAllOf = new AtomicReference<>();

		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					allOfApp.stream(Map.of(),
							RunnableConfig.builder()
									.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ALL_OF)
									.addParallelNodeExecutor(START, allOfExecutor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalStateAllOf::set)
							.blockLast());

			assertTrue(allOfEntered.await(2, TimeUnit.SECONDS));
			assertFalse(run.isDone(), "ALL_OF must wait while streaming branches are blocked");
			allOfRelease.countDown();
			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalStateAllOf.get(), "Final state should not be null");
			List<String> messages = (List<String>) finalStateAllOf.get().value("messages").orElse(List.of());
			assertTrue(messages.containsAll(List.of("normalNode", "merge")),
					"ALL_OF should include the normal branch and merge node (got: " + messages + ")");
			assertTrue(finalStateAllOf.get().value("stream").isPresent(),
					"ALL_OF should include data from streaming branches");
		}
		finally {
			releaseAndShutdown(allOfRelease, allOfExecutor);
		}
	}

	/**
	 * Tests parallel node aggregation strategy with mixed streaming and non-streaming nodes.
	 * This test verifies that when parallel branches mix streaming (Flux) and non-streaming nodes,
	 * the aggregation strategy correctly handles both types of results.
	 */
	@Test
	void testParallelNodeAggregationStrategyWithMixedStreamingAndNormalNodes() throws Exception {
		CountDownLatch anyOfEntered = new CountDownLatch(1);
		CountDownLatch anyOfRelease = new CountDownLatch(1);
		AtomicBoolean anyOfInterrupted = new AtomicBoolean(false);
		ExecutorService anyOfExecutor = Executors.newFixedThreadPool(2);

		var workflow = new StateGraph(createKeyStrategyFactory())
				.addNode("fastNormalNode", immediateNode("fastNormalNode"))
				.addNode("slowStreamingNode", controlledStreamingNode("slowStreamingNode", anyOfEntered, anyOfRelease,
						anyOfInterrupted, "data1", "data2", "data3"))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "fastNormalNode")
				.addEdge(START, "slowStreamingNode")
				.addEdge("fastNormalNode", "merge")
				.addEdge("slowStreamingNode", "merge")
				.addEdge("merge", END);

		var app = workflow.compile();

		AtomicReference<OverAllState> finalState = new AtomicReference<>();
		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					app.stream(Map.of(),
							RunnableConfig.builder()
									.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ANY_OF)
									.addParallelNodeExecutor(START, anyOfExecutor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalState::set)
							.blockLast());

			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalState.get(), "Final state should not be null");
			assertEquals("fastNormalNode", finalState.get().value("nodeId").orElseThrow());
			List<String> messages = (List<String>) finalState.get().value("messages").orElse(List.of());
			assertTrue(messages.contains("fastNormalNode"),
					"Result should contain fastNormalNode's data (first completed)");
			assertFalse(finalState.get().value("stream").isPresent(),
					"Result should NOT contain slowStreamingNode data with ANY_OF strategy");
		}
		finally {
			releaseAndShutdown(anyOfRelease, anyOfExecutor);
		}

		CountDownLatch allOfEntered = new CountDownLatch(2);
		CountDownLatch allOfRelease = new CountDownLatch(1);
		AtomicBoolean allOfInterrupted = new AtomicBoolean(false);
		ExecutorService allOfExecutor = Executors.newFixedThreadPool(2);
		var allOfWorkflow = new StateGraph(createKeyStrategyFactory())
				.addNode("fastNormalNode", controlledNode("fastNormalNode", allOfEntered, allOfRelease, allOfInterrupted))
				.addNode("slowStreamingNode", controlledStreamingNode("slowStreamingNode", allOfEntered, allOfRelease,
						allOfInterrupted, "data1", "data2", "data3"))
				.addNode("merge", makeNode("merge"))
				.addEdge(START, "fastNormalNode")
				.addEdge(START, "slowStreamingNode")
				.addEdge("fastNormalNode", "merge")
				.addEdge("slowStreamingNode", "merge")
				.addEdge("merge", END);
		var allOfApp = allOfWorkflow.compile();
		AtomicReference<OverAllState> finalStateAllOf = new AtomicReference<>();

		try {
			CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
					allOfApp.stream(Map.of(),
							RunnableConfig.builder()
									.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ALL_OF)
									.addParallelNodeExecutor(START, allOfExecutor)
									.build())
							.doOnNext(output -> log.info("Node output: {}", output.node()))
							.map(NodeOutput::state)
							.doOnNext(finalStateAllOf::set)
							.blockLast());

			assertTrue(allOfEntered.await(2, TimeUnit.SECONDS));
			assertFalse(run.isDone(), "ALL_OF must wait while mixed branches are blocked");
			allOfRelease.countDown();
			run.get(2, TimeUnit.SECONDS);

			assertNotNull(finalStateAllOf.get(), "Final state should not be null");
			List<String> messages = (List<String>) finalStateAllOf.get().value("messages").orElse(List.of());
			assertTrue(messages.containsAll(List.of("fastNormalNode", "merge")),
					"ALL_OF should include the normal branch and merge node (got: " + messages + ")");
			assertTrue(finalStateAllOf.get().value("stream").isPresent(),
					"ALL_OF should include the streaming branch");
		}
		finally {
			releaseAndShutdown(allOfRelease, allOfExecutor);
		}
	}

}
