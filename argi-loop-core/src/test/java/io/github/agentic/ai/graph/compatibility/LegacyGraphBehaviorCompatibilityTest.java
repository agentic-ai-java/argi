/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.compatibility;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.KeyStrategyFactory;
import io.github.agentic.ai.graph.KeyStrategyFactoryBuilder;
import io.github.agentic.ai.graph.NodeAggregationStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.state.StateSnapshot;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.utils.EdgeMappings;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncEdgeAction.edge_async;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig.node_async;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyGraphBehaviorCompatibilityTest {

	@Test
	void sequentialStreamKeepsStartNodeOrderAndReplacesStateValues() throws Exception {
		CompiledGraph graph = new StateGraph(replaceKeys("value"))
			.addNode("first", node_async(state -> Map.of("value", "first")))
			.addNode("second", node_async(state -> Map.of("value", "second")))
			.addEdge(START, "first")
			.addEdge("first", "second")
			.addEdge("second", END)
			.compile();

		List<NodeOutput> outputs = graph.stream(Map.of("value", "input"), RunnableConfig.builder().build())
			.collectList()
			.block(Duration.ofSeconds(2));

		assertThat(outputs).isNotNull();
		assertThat(outputs).extracting(NodeOutput::node).containsExactly(START, "first", "second", END);
		assertThat(outputs.get(outputs.size() - 1).state().data()).containsEntry("value", "second");
	}

	@Test
	void allOfParallelMergeExposesEveryBranchResultBeforeMerge() throws Exception {
		CompiledGraph graph = new StateGraph(appendMessages())
			.addNode("left", node_async(state -> Map.of("messages", "left")))
			.addNode("right", node_async(state -> Map.of("messages", "right")))
			.addNode("merge", node_async(state -> Map.of("messages", "merge")))
			.addEdge(START, "left")
			.addEdge(START, "right")
			.addEdge("left", "merge")
			.addEdge("right", "merge")
			.addEdge("merge", END)
			.compile();

		List<NodeOutput> outputs = graph.stream(Map.of(), RunnableConfig.builder()
				.addParallelNodeAggregationStrategy("merge", NodeAggregationStrategy.ALL_OF)
				.build())
			.collectList()
			.block(Duration.ofSeconds(2));

		assertThat(outputs).isNotNull();
		assertThat(outputs).extracting(NodeOutput::node).containsExactly(START, "__PARALLEL__(__START__)", "merge", END);
		assertThat(outputs.get(1).state().value("messages")).hasValue(List.of("left", "right"));
		assertThat(outputs.get(2).state().value("messages"))
			.hasValue(List.of("left", "right", "merge"));
	}

	@Test
	void interruptAfterEdgeDuplicatesCurrentNodeAndResumeContinuesFromSuccessor() throws Exception {
		CompiledGraph graph = interruptibleGraph(new MemorySaver());
		RunnableConfig config = RunnableConfig.builder().threadId("legacy-interrupt").build();

		List<NodeOutput> interrupted = graph.stream(Map.of(), config)
			.collectList()
			.block(Duration.ofSeconds(2));

		assertThat(interrupted).isNotNull();
		assertThat(interrupted).extracting(NodeOutput::node).containsExactly(START, "A", "B", "B");
		assertThat(interrupted.get(interrupted.size() - 1)).isInstanceOf(InterruptionMetadata.class);

		List<NodeOutput> resumed = graph.stream(null, RunnableConfig.builder(config).resume().build())
			.collectList()
			.block(Duration.ofSeconds(2));

		assertThat(resumed).isNotNull();
		assertThat(resumed).extracting(NodeOutput::node).containsExactly("D", END);
		assertThat(resumed.get(resumed.size() - 1).state().data()).containsEntry("messages", "D");
	}

	@Test
	void historicalCheckpointResumeStartsAtStoredSuccessorAndRetainsOldState() throws Exception {
		CompiledGraph graph = new StateGraph(replaceKeys("k1", "k2"))
			.addNode("node1", node_async(state -> Map.of("k1", "v1")))
			.addNode("node2", node_async(state -> Map.of("k2", "v2")))
			.addEdge(START, "node1")
			.addEdge("node1", "node2")
			.addEdge("node2", END)
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
				.build());
		RunnableConfig config = RunnableConfig.builder().threadId("legacy-history").build();

		graph.invoke(Map.of(), config);
		StateSnapshot node1Snapshot = graph.getStateHistory(config)
			.stream()
			.filter(snapshot -> "node1".equals(snapshot.node()))
			.findFirst()
			.orElseThrow();

		assertThat(node1Snapshot.next()).isEqualTo("node2");
		assertThat(node1Snapshot.state().data()).containsEntry("k1", "v1").doesNotContainKey("k2");

		List<NodeOutput> resumed = graph.stream(Map.of(), RunnableConfig.builder()
				.threadId("legacy-history")
				.checkPointId(node1Snapshot.config().checkPointId().orElseThrow())
				.build())
			.collectList()
			.block(Duration.ofSeconds(2));

		assertThat(resumed).isNotNull();
		assertThat(resumed).extracting(NodeOutput::node).containsExactly("node2", END);
		assertThat(resumed.get(resumed.size() - 1).state().data())
			.containsEntry("k1", "v1")
			.containsEntry("k2", "v2");
	}

	private static CompiledGraph interruptibleGraph(MemorySaver saver) throws Exception {
		return new StateGraph(replaceKeys("messages"))
			.addNode("A", legacyNode("A"))
			.addNode("B", legacyNode("B"))
			.addNode("C", legacyNode("C"))
			.addNode("D", legacyNode("D"))
			.addConditionalEdges("B", edge_async(state -> {
				Object message = state.value("messages").orElse(END);
				return "B".equals(message) ? "D" : message.toString();
			}), EdgeMappings.builder().to("A").to("C").to("D").toEND().build())
			.addEdge(START, "A")
			.addEdge("A", "B")
			.addEdge("C", END)
			.addEdge("D", END)
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(saver).build())
				.interruptAfter("B")
				.build());
	}

	private static AsyncNodeActionWithConfig legacyNode(String id) {
		return node_async((state, config) -> Map.of("messages", id));
	}

	private static KeyStrategyFactory replaceKeys(String... keys) {
		KeyStrategyFactoryBuilder builder = new KeyStrategyFactoryBuilder().defaultStrategy(KeyStrategy.REPLACE);
		for (String key : keys) {
			builder.addStrategy(key);
		}
		return builder.build();
	}

	private static KeyStrategyFactory appendMessages() {
		return () -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("messages", new AppendStrategy());
			return strategies;
		};
	}

}
