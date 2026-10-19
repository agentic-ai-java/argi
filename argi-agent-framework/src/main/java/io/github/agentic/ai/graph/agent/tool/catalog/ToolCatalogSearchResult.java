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

import java.util.List;
import java.util.Objects;

/**
 * Bounded, authorized result of {@link ToolCatalog#search}.
 *
 * @param status the search outcome
 * @param tools authorized matches, most relevant first; empty unless
 * {@link ToolCatalogSearchStatus#MATCHED}
 * @param failedProviders ids of providers whose summaries could not be read; a partial
 * provider failure does not widen the result
 */
public record ToolCatalogSearchResult(ToolCatalogSearchStatus status, List<ToolSummary> tools,
		List<String> failedProviders) {

	public ToolCatalogSearchResult {
		Objects.requireNonNull(status, "status must not be null");
		tools = List.copyOf(tools);
		failedProviders = List.copyOf(failedProviders);
		if (status != ToolCatalogSearchStatus.MATCHED && !tools.isEmpty()) {
			throw new IllegalArgumentException("Only a MATCHED result may carry tools");
		}
		if (status == ToolCatalogSearchStatus.MATCHED && tools.isEmpty()) {
			throw new IllegalArgumentException("A MATCHED result must carry tools");
		}
	}

	static ToolCatalogSearchResult of(ToolCatalogSearchStatus status, List<String> failedProviders) {
		return new ToolCatalogSearchResult(status, List.of(), failedProviders);
	}

	public boolean matched() {
		return this.status == ToolCatalogSearchStatus.MATCHED;
	}

}
