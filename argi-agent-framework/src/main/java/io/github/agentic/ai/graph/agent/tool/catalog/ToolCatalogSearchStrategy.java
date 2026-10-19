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

/**
 * Pluggable ranking strategy used by {@link ToolCatalog}.
 *
 * <p>
 * The strategy only ever sees summaries that already passed the catalog's
 * {@link ToolCatalogAccess#DISCOVER} check, so it cannot leak an unauthorized tool even
 * when it is backed by an external index. Keyword and rule matching is provided by
 * {@link KeywordToolCatalogSearchStrategy}; embedding or LLM based strategies can be
 * plugged in by applications or extension modules without changing the catalog. Results
 * that are not among the given candidates are discarded by the catalog, and the catalog
 * enforces the result bound.
 * </p>
 */
@FunctionalInterface
public interface ToolCatalogSearchStrategy {

	/**
	 * Selects and orders matching candidates, most relevant first.
	 * @param query the retrieval request
	 * @param candidates authorized candidates in catalog order
	 * @return the matches, never {@code null}; may be empty
	 */
	List<ToolSummary> search(ToolCatalogQuery query, List<ToolSummary> candidates);

}
