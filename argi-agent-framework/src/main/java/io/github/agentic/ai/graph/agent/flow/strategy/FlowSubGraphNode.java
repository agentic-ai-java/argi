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
package io.github.agentic.ai.graph.agent.flow.strategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction;
import io.github.agentic.ai.graph.internal.node.SubCompiledGraphNode;
import io.github.agentic.ai.graph.internal.node.SubCompiledGraphNodeAction;
import io.github.agentic.ai.graph.state.ReplaceAllWith;

/** Passes the parent's conversation snapshot to a nested flow without appending it twice. */
final class FlowSubGraphNode extends SubCompiledGraphNode {

	private final CompiledGraph flowGraph;

	FlowSubGraphNode(String id, CompiledGraph flowGraph) {
		super(id, flowGraph);
		this.flowGraph = flowGraph;
	}

	@Override
	public ActionFactory actionFactory() {
		return parentConfig -> new SnapshotAction(new SubCompiledGraphNodeAction(id(), parentConfig, flowGraph));
	}

	private record SnapshotAction(SubCompiledGraphNodeAction delegate)
			implements AsyncNodeActionWithConfig, ResumableSubGraphAction {

		@Override
		public String getResumeSubGraphId() {
			return delegate.getResumeSubGraphId();
		}

		@Override
		public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
			Map<String, Object> inputs = new HashMap<>(state.data());
			if (inputs.get("messages") instanceof List<?> messages) {
				inputs.put("messages", ReplaceAllWith.of(new ArrayList<>(messages)));
			}
			OverAllState child = new OverAllState(inputs, state.getStore());
			child.registerKeyAndStrategy(state.keyStrategies());
			return delegate.apply(child, config);
		}

	}

}
