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
package io.github.agentic.ai.graph.executor;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.exception.GraphRunnerException;
import io.github.agentic.ai.graph.streaming.GraphFlux;
import io.github.agentic.ai.graph.streaming.ParallelGraphFlux;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that parallel streaming branches merge their {@link GraphResponse#done(Object)}
 * state updates before the converged node runs.
 */
public class ParallelGraphFluxStateMergeTest {

	@Test
	void testParallelGraphFluxDoneMapIsMergedBeforeJoinNode() throws Exception {
		CompiledGraph graph = buildGraph();

		OverAllState finalState = graph.invoke(Map.of()).orElseThrow();

		assertEquals("left", finalState.value("join_left", ""));
		assertEquals("right", finalState.value("join_right", ""));
		assertEquals(List.of("left", "right"), finalState.value("join_results", List.of()));
	}

	@Test
	void testParallelGraphFluxDoneNonMapKeepsGraphResponseState() throws Exception {
		CompiledGraph graph = buildGraphWithNonMapDoneValue();

		OverAllState finalState = graph.invoke(Map.of()).orElseThrow();

		assertEquals("left-token", finalState.value("join_left_payload", ""));
		assertEquals("right", finalState.value("join_right", ""));
	}

	@Test
	void recursionLimitEmitsErrorInsteadOfSuccessfulPartialState() throws Exception {
		StateGraph stateGraph = new StateGraph()
			.addNode("loop", node_async(state -> Map.of("count", state.value("count", 0) + 1)))
			.addEdge(START, "loop")
			.addEdge("loop", "loop");
		CompiledGraph graph = stateGraph.compile(CompileConfig.builder().recursionLimit(2).build());

		List<GraphResponse<NodeOutput>> responses = graph.graphResponseStream(Map.of(), RunnableConfig.builder().build())
			.collectList()
			.block();

		GraphResponse<NodeOutput> lastResponse = responses.get(responses.size() - 1);
		assertAll(
				() -> assertTrue(lastResponse.isError(), "recursion limit should produce an error response"),
				() -> assertTrue(lastResponse.getOutput().isCompletedExceptionally()),
				() -> assertTrue(completionFailure(lastResponse) instanceof GraphRunnerException),
				() -> assertTrue(completionFailure(lastResponse).getMessage().contains("recursion limit")));
	}

	@Test
	void graphFluxAppliesChunkAndResultCallbacks() throws Exception {
		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("mapped_result", new ReplaceStrategy());
			strategies.put("seen_chunk", new ReplaceStrategy());
			return strategies;
		}).addNode("stream", node_async(state -> Map.of("stream",
					GraphFlux.of("stream", "mapped_result", Flux.just("first", "last"),
							value -> Map.of("mapped_result", "mapped-" + value),
							value -> "chunk-" + value))))
			.addNode("join", node_async(state -> Map.of("seen_chunk", state.value("mapped_result", ""))))
			.addEdge(START, "stream")
			.addEdge("stream", "join")
			.addEdge("join", END);
		CompiledGraph graph = stateGraph.compile();

		List<GraphResponse<NodeOutput>> responses = graph.graphResponseStream(Map.of(), RunnableConfig.builder().build())
			.collectList()
			.block();
		OverAllState finalState = graph.invoke(Map.of()).orElseThrow();

		List<String> chunkValues = responses.stream()
			.filter(response -> response.getOutput() != null && !response.getOutput().isCompletedExceptionally())
			.map(response -> response.getOutput().join())
			.filter(StreamingOutput.class::isInstance)
			.map(StreamingOutput.class::cast)
			.filter(output -> output.getOriginData() != null)
			.map(output -> String.valueOf(output.getOriginData()))
			.toList();

		assertAll(
				() -> assertEquals(List.of("chunk-first", "chunk-last"), chunkValues),
				() -> assertEquals("mapped-last", finalState.value("mapped_result", "")),
				() -> assertEquals("mapped-last", finalState.value("seen_chunk", "")));
	}

	@Test
	void graphFluxCallbackAccessorsRetainElementType() {
		GraphFlux<String> graphFlux = GraphFlux.of("stream", "result", Flux.just("value"),
				value -> Map.of("result", value.length()), String::toUpperCase);

		Function<String, ?> resultMapper = graphFlux.getMapResult();
		Function<String, String> chunkMapper = graphFlux.getChunkResult();

		assertAll(
				() -> assertEquals(Map.of("result", 5), resultMapper.apply("value")),
				() -> assertEquals("VALUE", chunkMapper.apply("value")));
	}

	@Test
	void parallelGraphFluxPreservesTypedCallbacksWhenAssigningNodeIds() throws Exception {
		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("left_result", new ReplaceStrategy());
			strategies.put("right_result", new ReplaceStrategy());
			strategies.put("joined", new ReplaceStrategy());
			return strategies;
		}).addNode("left", node_async(state -> Map.of("left_stream",
				GraphFlux.of(null, "left_result", Flux.just("left"),
						value -> Map.of("left_result", value.length()), String::toUpperCase))))
			.addNode("right", node_async(state -> Map.of("right_stream",
					GraphFlux.of(null, "right_result", Flux.just("right"),
							value -> Map.of("right_result", value.length()), String::toUpperCase))))
			.addNode("join", node_async(state -> Map.of("joined",
					state.value("left_result", 0) + state.value("right_result", 0))))
			.addEdge(START, "left")
			.addEdge(START, "right")
			.addEdge("left", "join")
			.addEdge("right", "join")
			.addEdge("join", END);

		OverAllState finalState = stateGraph.compile().invoke(Map.of()).orElseThrow();

		assertAll(
				() -> assertEquals(4, finalState.value("left_result", 0)),
				() -> assertEquals(5, finalState.value("right_result", 0)),
				() -> assertEquals(9, finalState.value("joined", 0)));
	}

	@Test
	void emptyParallelGraphFluxPreservesOrdinaryStateBeforeJoinNode() throws Exception {
		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("ordinary", new ReplaceStrategy());
			strategies.put("join_ordinary", new ReplaceStrategy());
			return strategies;
		}).addNode("empty", node_async(state -> Map.of(
				"parallel", ParallelGraphFlux.empty(),
				"ordinary", "kept")))
			.addNode("join", node_async(state -> Map.of("join_ordinary", state.value("ordinary", ""))))
			.addEdge(START, "empty")
			.addEdge("empty", "join")
			.addEdge("join", END);
		CompiledGraph graph = stateGraph.compile();

		OverAllState finalState = graph.invoke(Map.of()).orElseThrow();

		assertEquals("kept", finalState.value("join_ordinary", ""));
	}

	private static Throwable completionFailure(GraphResponse<NodeOutput> response) {
		try {
			response.getOutput().join();
			throw new AssertionError("Expected response output to fail");
		}
		catch (java.util.concurrent.CompletionException ex) {
			return ex.getCause();
		}
	}

	private static CompiledGraph buildGraph() throws Exception {
		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("left_result", new ReplaceStrategy());
			strategies.put("right_result", new ReplaceStrategy());
			strategies.put("parallel_results", new AppendStrategy());
			strategies.put("join_left", new ReplaceStrategy());
			strategies.put("join_right", new ReplaceStrategy());
			strategies.put("join_results", new ReplaceStrategy());
			return strategies;
		}).addNode("left", node_async(state -> Map.of("left_stream", Flux.just(
				GraphResponse.done(Map.of(
						"left_result", "left",
						"parallel_results", List.of("left")))))))
			.addNode("right", node_async(state -> Map.of("right_stream", Flux.just(
					GraphResponse.done(Map.of(
							"right_result", "right",
							"parallel_results", List.of("right")))))))
			.addNode("join", node_async(state -> {
				assertInstanceOf(String.class, state.value("left_result", ""));
				assertInstanceOf(String.class, state.value("right_result", ""));
				return Map.of(
						"join_left", state.value("left_result", ""),
						"join_right", state.value("right_result", ""),
						"join_results", state.value("parallel_results", List.of()));
			}))
			.addEdge(START, "left")
			.addEdge(START, "right")
			.addEdge("left", "join")
			.addEdge("right", "join")
			.addEdge("join", END);
		return stateGraph.compile();
	}

	private static CompiledGraph buildGraphWithNonMapDoneValue() throws Exception {
		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("left_stream", new ReplaceStrategy());
			strategies.put("right_result", new ReplaceStrategy());
			strategies.put("join_left_payload", new ReplaceStrategy());
			strategies.put("join_right", new ReplaceStrategy());
			return strategies;
		}).addNode("left", node_async(state -> Map.of("left_stream",
				Flux.just(GraphResponse.done("left-token")))))
			.addNode("right", node_async(state -> Map.of("right_stream",
					Flux.just(GraphResponse.done(Map.of("right_result", "right"))))))
			.addNode("join", node_async(state -> {
				GraphResponse<?> leftResponse = assertInstanceOf(
						GraphResponse.class, state.value("left_stream", Object.class).orElseThrow());
				return Map.of(
						"join_left_payload", leftResponse.resultValue().orElse(""),
						"join_right", state.value("right_result", ""));
			}))
			.addEdge(START, "left")
			.addEdge(START, "right")
			.addEdge("left", "join")
			.addEdge("right", "join")
			.addEdge("join", END);
		return stateGraph.compile();
	}

}
