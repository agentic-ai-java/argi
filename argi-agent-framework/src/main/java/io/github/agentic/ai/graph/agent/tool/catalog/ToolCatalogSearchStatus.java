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

/**
 * Outcome of a catalog search. Only {@link #MATCHED} carries tools; every other status
 * returns an empty result and must never be interpreted as "expose every tool".
 */
public enum ToolCatalogSearchStatus {

	/** At least one authorized tool matched the query. */
	MATCHED,

	/** The search ran but no authorized tool matched. */
	EMPTY,

	/** The catalog requires a trusted execution context and none was supplied. */
	UNAUTHENTICATED,

	/** Every provider or the search strategy failed; nothing is returned. */
	FAILED

}
