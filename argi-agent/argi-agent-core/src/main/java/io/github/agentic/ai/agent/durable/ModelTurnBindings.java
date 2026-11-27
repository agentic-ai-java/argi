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
package io.github.agentic.ai.agent.durable;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Application-registered, immutable bindings for a recoverable agent. Versions cover
 * executable implementations as well as configuration. The pipeline version declares
 * deterministic single-call interceptors/hooks; hidden model calls are unsupported. Model
 * configuration includes native defaults and semantic invocation attributes.
 */
public record ModelTurnBindings(String modelId, String modelVersion, String modelConfigurationDigest, ChatClient client,
		String toolCatalogId, String toolCatalogVersion, ToolRegistry toolCatalog, String pipelineVersion,
		Set<String> replayableTools) {

	public ModelTurnBindings {
		for (String value : List.of(modelId, modelVersion, modelConfigurationDigest, toolCatalogId, toolCatalogVersion,
				pipelineVersion)) {
			if (value.isBlank()) {
				throw new IllegalArgumentException("Binding identities and versions must not be blank");
			}
		}
		Objects.requireNonNull(client, "client cannot be null");
		Objects.requireNonNull(toolCatalog, "toolCatalog cannot be null");
		replayableTools = Set.copyOf(replayableTools);
	}

	/**
	 * Only persisted declarations select executable code; no executable object is
	 * serialized.
	 */
	public ToolRegistry resolveTools(List<ToolDefinition> selection) {
		return new ToolRegistry(selection.stream().map(definition -> {
			var tool = toolCatalog.find(definition.name())
				.orElseThrow(() -> new ModelTurnJournalException("Missing registered tool: " + definition.name()));
			if (!definition.equals(tool.definition())) {
				throw new ModelTurnJournalException("Tool definition changed: " + definition.name());
			}
			return tool;
		}).toList());
	}

	public Identity identity() {
		return new Identity(modelId, modelVersion, modelConfigurationDigest, toolCatalogId, toolCatalogVersion,
				pipelineVersion, replayableTools);
	}

	/**
	 * Data-only identity; model credentials and executable tools are never stored here.
	 */
	public record Identity(String modelId, String modelVersion, String modelConfigurationDigest, String toolCatalogId,
			String toolCatalogVersion, String pipelineVersion, Set<String> replayableTools) {
		public Identity {
			replayableTools = Set.copyOf(replayableTools);
		}
	}

}
