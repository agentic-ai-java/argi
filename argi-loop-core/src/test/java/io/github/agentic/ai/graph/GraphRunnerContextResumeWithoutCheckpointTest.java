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

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig.node_async;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression tests for issue #28: in multi-agent orchestration, resuming the parent graph
 * after a Human-in-the-Loop interruption carries {@code HUMAN_FEEDBACK} metadata into every
 * sibling sub-agent. Sub-agents that were never interrupted have no checkpoint, and the
 * resume path used to fail with {@code IllegalStateException: Resume request without a
 * valid checkpoint!}. They must fall back to a fresh start instead.
 */
@DisplayName("GraphRunnerContext resume without checkpoint tests")
class GraphRunnerContextResumeWithoutCheckpointTest {

	@Test
	@DisplayName("Resume config without a checkpoint falls back to fresh start instead of throwing")
	void resumeConfigWithoutCheckpointFallsBackToFreshStart() throws Exception {
		StateGraph graph = new StateGraph();
		graph.addNode("nodeA", node_async((state, config) -> Map.<String, Object>of()));
		graph.addEdge(START, "nodeA");

		CompiledGraph compiledGraph = graph.compile();
		OverAllState state = new OverAllState(Map.of());
		RunnableConfig resumeConfig = RunnableConfig.builder()
			.threadId("thread-without-checkpoint")
			.addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, "placeholder")
			.build();

		assertThatCode(() -> new GraphRunnerContext(state, resumeConfig, compiledGraph))
			.doesNotThrowAnyException();
	}

}
