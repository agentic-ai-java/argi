/*
 * Copyright 2024-2026 the original author or authors.
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

package io.github.agentic.ai.graph.internal.node;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.SubGraphNode;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;

import java.util.Map;
import java.util.Objects;

import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.outputKeyToParent;

public class SubCompiledGraphNode extends Node implements SubGraphNode {
	private final CompiledGraph subGraph;
	private final String id;

	public SubCompiledGraphNode(String id, CompiledGraph subGraph) {
		super(Objects.requireNonNull(id, "id cannot be null"),
				(config) -> new SubCompiledGraphNodeAction(id, config, subGraph));
		this.subGraph = subGraph;
		this.id = id;
	}

	public StateGraph subGraph() {
		return subGraph.stateGraph;
	}

	@Override
	public Map<String, KeyStrategy> keyStrategies() {
		return Map.of(outputKeyToParent(id), new ReplaceStrategy());
	}

}
