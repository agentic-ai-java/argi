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

import io.github.agentic.ai.graph.action.NodeAction;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Reproduction for the keyStrategy aliasing/overwrite issue reported upstream in
 * alibaba/spring-ai-alibaba#4999 (same code inherited by argi-graph-core):
 *
 * 1. CompiledGraph.cloneState passes the compiled graph's shared keyStrategyMap
 *    into the 2-arg OverAllState constructor by reference, whose unconditional
 *    registerKeyAndStrategy(DEFAULT_INPUT_KEY, new ReplaceStrategy()) then
 *    mutates the shared map on the first checkpoint (GraphRunnerContext.addCheckpoint).
 * 2. Even with a defensive copy, the unconditional default-input registration
 *    still silently replaces a user-registered "input" strategy on every clone
 *    and snapshot (OverAllState.snapShot, used by ParallelNode/ConditionalParallelNode),
 *    so Append/Merge semantics degrade to REPLACE exactly where cloning is involved.
 */
public class CloneStateKeyStrategyPollutionTest {

	@Test
	public void cloneStateMustNotMutateCompiledGraphKeyStrategyMap() throws Exception {
		KeyStrategyFactory keyStrategyFactory = () -> {
			Map<String, KeyStrategy> m = new HashMap<>();
			m.put("input", new AppendStrategy());
			m.put("messages", new AppendStrategy());
			return m;
		};

		NodeAction agent = state -> Map.of("messages", "hello");

		var workflow = new StateGraph(keyStrategyFactory)
			.addNode("agent", node_async(agent))
			.addEdge(START, "agent")
			.addEdge("agent", END);

		// compile() registers a MemorySaver by default, so every node output
		// triggers addCheckpoint -> cloneState
		var app = workflow.compile();

		// right after compile, the user-registered strategy is intact
		assertInstanceOf(AppendStrategy.class, app.getKeyStrategyMap().get("input"));

		app.invoke(Map.of("input", "a"));

		// after the first checkpoint, the shared keyStrategyMap must still hold
		// the user-registered strategy for "input"
		assertInstanceOf(AppendStrategy.class, app.getKeyStrategyMap().get("input"),
				"cloneState must not pollute the CompiledGraph's shared keyStrategyMap");
	}

	@Test
	public void cloneStateAndSnapShotMustPreserveUserRegisteredInputStrategy() throws Exception {
		KeyStrategyFactory keyStrategyFactory = () -> {
			Map<String, KeyStrategy> m = new HashMap<>();
			m.put("input", new AppendStrategy());
			m.put("messages", new AppendStrategy());
			return m;
		};

		NodeAction agent = state -> Map.of("messages", "hello");

		var app = new StateGraph(keyStrategyFactory)
			.addNode("agent", node_async(agent))
			.addEdge(START, "agent")
			.addEdge("agent", END)
			.compile();

		var clone = app.cloneState(Map.of("input", "a"));

		// the clone handed to checkpoints / interrupt hooks must keep the
		// user-registered merge semantics for "input"
		assertInstanceOf(AppendStrategy.class, clone.keyStrategies().get("input"),
				"cloneState must preserve the user-registered 'input' KeyStrategy");

		var snapShot = clone.snapShot().orElseThrow();

		// same for snapshots taken from the clone (ParallelNode/ConditionalParallelNode path)
		assertInstanceOf(AppendStrategy.class, snapShot.keyStrategies().get("input"),
				"snapShot must preserve the user-registered 'input' KeyStrategy");
	}

}
