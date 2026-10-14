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

import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.GraphRunnerContext;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.internal.node.ParallelNode;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.streaming.GraphFlux;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParallelNodeUsageTest {

	private static AsyncNodeActionWithConfig branch(String id, boolean streaming, Usage usage) {
		Map<String, Object> delta = Map.of(NodeOutput.TOKEN_USAGE_KEY, usage, "answers", List.of(id));
		return (state, config) -> CompletableFuture.completedFuture(streaming
				? Map.of("stream", GraphFlux.of(id, "answer", Flux.just("answer"), ignored -> delta, null)) : delta);
	}

	@Test
	void aggregatesStreamingNonStreamingAndMixedBranchesWithoutPersistingUsage() throws Exception {
		for (boolean leftStream : List.of(false, true)) {
			for (boolean rightStream : List.of(false, true)) {
				Map<String, KeyStrategy> channels = Map.of("answers", new AppendStrategy(), NodeOutput.TOKEN_USAGE_KEY,
						new AppendStrategy());
				var parallel = new ParallelNode.AsyncParallelNodeAction("parallel", "join",
						List.of(branch("left", leftStream, new Usage(3_000_000_000L, 2L, 3_000_000_002L)),
								branch("right", rightStream, new Usage(4L, 5L, 9L)),
								(state, config) -> CompletableFuture
									.completedFuture(Map.of("answers", List.of("ordinary")))),
						List.of("left", "right", "ordinary"), channels, CompileConfig.builder().build());
				var graph = new StateGraph(() -> channels).addNode("parallel", parallel)
					.addEdge(StateGraph.START, "parallel")
					.addEdge("parallel", StateGraph.END)
					.compile();
				var context = new GraphRunnerContext(new OverAllState().registerKeyAndStrategy(channels),
						RunnableConfig.builder().build(), graph);
				context.setNextNodeId("parallel");
				MainGraphExecutor main = mock(MainGraphExecutor.class);
				when(main.execute(any(), any())).thenReturn(Flux.empty());
				List<GraphResponse<NodeOutput>> responses = new NodeExecutor(main)
					.execute(context, new AtomicReference<>())
					.collectList()
					.block(Duration.ofSeconds(5));
				assertTrue(responses.stream().noneMatch(GraphResponse::isError));
				// Run the continuation in non-streaming mode as graphResponseStream
				// normally does.
				for (GraphResponse<NodeOutput> response : responses) {
					if (response.hasContinuation()) {
						response.getContinuation().get().blockLast(Duration.ofSeconds(5));
					}
				}
				assertEquals(new Usage(3_000_000_004L, 7L, 3_000_000_011L),
						context.buildNodeOutput("parallel").tokenUsage());
				assertEquals(3, ((List<?>) context.getCurrentStateData().get("answers")).size());
				assertFalse(context.getCurrentStateData().containsKey(NodeOutput.TOKEN_USAGE_KEY));
			}
		}
	}

}
