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
package io.github.agentic.ai.graph.executor;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.GraphRunnerContext;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.streaming.GraphFlux;
import io.github.agentic.ai.graph.streaming.ParallelGraphFlux;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the executor directly so runner-level cancellation cannot hide invalid
 * commits.
 */
class GraphFluxTerminalTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void successfulStreamEmitsEachChunkOnceAndCommitsBeforeContinuation() throws Exception {
		AtomicInteger mappings = new AtomicInteger();
		GraphFlux<String> stream = GraphFlux.of(null, "result", Flux.just("first", "last"), value -> {
			mappings.incrementAndGet();
			return Map.of("result", value);
		}, null);
		GraphRunnerContext context = context(Map.of("stream", stream, "ordinary", "committed"));

		List<GraphResponse<NodeOutput>> responses = execute(context);

		assertEquals(1, mappings.get());
		assertEquals("last", context.getCurrentStateData().get("result"));
		assertEquals("committed", context.getCurrentStateData().get("ordinary"));
		assertEquals(3, responses.size());
		assertTrue(responses.get(2).hasContinuation());
		assertEquals(List.of("first", "last"),
				responses.stream()
					.filter(item -> item.getOutput() != null)
					.map(item -> (StreamingOutput<?>) item.getOutput().join())
					.map(StreamingOutput::getOriginData)
					.toList());
	}

	@Test
	void unmappedStreamCanBeMergedIntoStateWithoutSelfReferencingItsSnapshot() throws Exception {
		GraphFlux<String> stream = GraphFlux.of(null, "result", Flux.just("first", "last"), null, null);
		GraphRunnerContext context = context(Map.of("stream", stream));

		List<GraphResponse<NodeOutput>> responses = execute(context);

		GraphResponse<?> result = assertInstanceOf(GraphResponse.class, context.getCurrentStateData().get("result"));
		StreamingOutput<?> output = assertInstanceOf(StreamingOutput.class, result.getOutput().join());
		assertNotSame(context.getOverallState(), output.state());
		assertTrue(output.state().data().isEmpty());
		assertEquals("last", output.getOriginData());
		assertTrue(responses.get(responses.size() - 1).hasContinuation());
		OverAllState restored = context.cloneState(context.getCurrentStateData());
		assertTrue(restored.data().containsKey("result"));
	}

	@Test
	void nativeMessageStreamAlsoCapturesStateBeforeTheResultIsMerged() throws Exception {
		GraphRunnerContext context = context(Map.of());
		StreamingOutput<?> output = context
			.buildStreamingOutput(io.github.agentic.ai.api.chat.ChatMessage.assistant("answer"), null, "model", true);

		context.mergeIntoCurrentState(Map.of("result", output));

		assertNotSame(context.getOverallState(), output.state());
		assertTrue(output.state().data().isEmpty());
		StreamingOutput<?> restored = assertInstanceOf(StreamingOutput.class,
				context.cloneState(context.getCurrentStateData()).data().get("result"));
		assertEquals("answer", restored.text());
		assertTrue(restored.state().data().isEmpty());
	}

	@Test
	void failureSignalsAndFailureValuesNeverMapOrCommitState() throws Exception {
		IllegalStateException failure = new IllegalStateException("provider failed");
		for (Flux<?> source : List.of(Flux.just("partial").concatWith(Flux.error(failure)),
				Flux.just("partial", failure, "late"), Flux.just("partial", GraphResponse.error(failure), "late"),
				Flux.empty())) {
			AtomicInteger mappings = new AtomicInteger();
			GraphFlux<?> stream = GraphFlux.of(null, "result", source, value -> {
				mappings.incrementAndGet();
				return Map.of("result", "must not commit");
			}, null);
			GraphRunnerContext context = context(Map.of("stream", stream, "ordinary", "must not commit"));

			List<GraphResponse<NodeOutput>> responses = execute(context);

			assertEquals(0, mappings.get());
			assertTrue(context.getCurrentStateData().isEmpty());
			assertEquals(1, responses.stream().filter(GraphResponse::isError).count());
			assertTrue(responses.get(responses.size() - 1).isError());
			assertFalse(responses.stream().anyMatch(GraphResponse::hasContinuation));
			assertTrue(responses.stream()
				.filter(item -> !item.isError())
				.allMatch(item -> !"late".equals(((StreamingOutput<?>) item.getOutput().join()).getOriginData())));
		}
	}

	@Test
	void parallelFailureCancelsPeersAndDoesNotCommitAnyBranch() throws Exception {
		Sinks.Empty<Void> peerStarted = Sinks.empty();
		CountDownLatch peerCancelled = new CountDownLatch(1);
		AtomicInteger mappings = new AtomicInteger();
		GraphFlux<String> failed = GraphFlux.of("failed", "failed_result",
				peerStarted.asMono().thenMany(Flux.error(new IllegalStateException("provider failed"))), value -> {
					mappings.incrementAndGet();
					return value;
				}, null);
		GraphFlux<String> pending = GraphFlux.of("pending", "pending_result", Flux.defer(() -> {
			peerStarted.tryEmitEmpty();
			return Flux.<String>never().doOnCancel(peerCancelled::countDown);
		}), value -> {
			mappings.incrementAndGet();
			return value;
		}, null);
		GraphRunnerContext context = context(
				Map.of("stream", ParallelGraphFlux.of(List.of(failed, pending)), "ordinary", "must not commit"));

		List<GraphResponse<NodeOutput>> responses = execute(context);

		assertTrue(peerCancelled.await(5, TimeUnit.SECONDS));
		assertEquals(0, mappings.get());
		assertTrue(context.getCurrentStateData().isEmpty());
		assertEquals(1, responses.size());
		assertTrue(responses.get(0).isError());
	}

	private static GraphRunnerContext context(Map<String, Object> updates) throws Exception {
		CompiledGraph graph = new StateGraph().addNode("stream", node_async(state -> updates))
			.addEdge(START, "stream")
			.addEdge("stream", END)
			.compile();
		GraphRunnerContext context = new GraphRunnerContext(new OverAllState(), RunnableConfig.builder().build(),
				graph);
		context.setNextNodeId("stream");
		return context;
	}

	private static List<GraphResponse<NodeOutput>> execute(GraphRunnerContext context) {
		return new NodeExecutor(new MainGraphExecutor()).execute(context, new AtomicReference<>())
			.collectList()
			.block(TIMEOUT);
	}

}
