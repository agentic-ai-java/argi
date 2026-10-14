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
package io.github.agentic.ai.contract;

import java.util.Map;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;

final class GraphApiFixture {

	private GraphApiFixture() {
	}

	static void compileOnly() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();
		StateGraph graph = new StateGraph(() -> Map.of("messages", KeyStrategy.APPEND))
			.addNode("node", node_async(state -> Map.of("messages", "done")))
			.addEdge(StateGraph.START, "node")
			.addEdge("node", StateGraph.END);
		CompileConfig compileConfig = CompileConfig.builder()
			.saverConfig(SaverConfig.builder().register(saver).build())
			.build();
		RunnableConfig runnableConfig = RunnableConfig.builder().threadId("fixture").build();
		CompiledGraph compiled = graph.compile(compileConfig);

		compiled.stream(Map.of(), RunnableConfig.builder().threadId("fixture").build());
		compiled.invoke(Map.of(), runnableConfig);
		compiled.getStateHistory(runnableConfig);
		compiled.updateState(runnableConfig, Map.of("messages", "updated"));
	}

}
