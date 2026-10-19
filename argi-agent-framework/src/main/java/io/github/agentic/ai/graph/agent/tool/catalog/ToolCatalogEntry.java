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

package io.github.agentic.ai.graph.agent.tool.catalog;

import io.github.agentic.ai.api.tool.Tool;

import java.util.Objects;

/**
 * A fully resolved catalog tool version: its summary plus the executable binding whose
 * {@link Tool#definition() definition} carries the complete input schema.
 *
 * <p>
 * Entries are returned by {@link ToolCatalogProvider#resolve(ToolId, String)} only and
 * are never cached by {@link ToolCatalog}; each resolution goes back to the provider.
 * </p>
 *
 * @param summary the summary of the resolved version
 * @param tool the executable binding for exactly that version
 */
public record ToolCatalogEntry(ToolSummary summary, Tool tool) {

	public ToolCatalogEntry {
		Objects.requireNonNull(summary, "summary must not be null");
		Objects.requireNonNull(tool, "tool must not be null");
		Objects.requireNonNull(tool.definition(), "tool definition must not be null");
	}

}
