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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.tool.ToolRegistry;
import java.util.List;
import io.github.agentic.ai.api.chat.ToolDefinition;

/**
 * Immutable bindings for one model turn, scoped to the run rather than the compiled
 * agent.
 */
record AgentToolBindings(ToolRegistry registry) {

	List<ToolDefinition> definitions() {
		return registry.definitions();
	}

	boolean usesOnly(AgentToolBindings configured) {
		return registry.definitions()
			.stream()
			.allMatch(definition -> registry.find(definition.name())
				.orElseThrow() == configured.registry.find(definition.name()).orElse(null));
	}

	AgentToolBindings select(List<ToolDefinition> selection) {
		var available = definitions();
		if (!available.containsAll(selection)) {
			throw new IllegalStateException("Configured tools no longer match the checkpoint's model turn");
		}
		var names = selection.stream().map(ToolDefinition::name).toList();
		return new AgentToolBindings(new ToolRegistry(registry.definitions()
			.stream()
			.filter(definition -> names.contains(definition.name()))
			.map(definition -> registry.find(definition.name()).orElseThrow())
			.toList()));
	}

}
