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
import java.util.Optional;

/**
 * Backend-neutral adapter that exposes one source of tools to a {@link ToolCatalog}.
 *
 * <p>
 * A provider may be backed by an in-process registry, a remote tool server, a database
 * or a vector index; the catalog only depends on this contract. Implementations must
 * follow these rules:
 * </p>
 * <ul>
 * <li>Every summary returned by {@link #summaries()} uses this provider's
 * {@link #providerId()} as its {@link ToolId#provider() provider} segment and appears at
 * most once. Summaries that claim another provider are discarded by the catalog.</li>
 * <li>{@link #resolve(ToolId, String)} returns exactly the requested version or empty;
 * it never substitutes a different (for example, the latest) version.</li>
 * <li>Providers do not decide authorization. The catalog filters every summary and every
 * schema request through its {@link ToolCatalogAccessPolicy}.</li>
 * </ul>
 */
public interface ToolCatalogProvider {

	/**
	 * Stable provider id used as the {@link ToolId#provider()} segment.
	 * @return the provider id, matching {@code [A-Za-z0-9-]+}
	 */
	String providerId();

	/**
	 * Lightweight summaries of the tools this provider currently offers, one per tool.
	 * @return the current summaries, never {@code null}
	 */
	List<ToolSummary> summaries();

	/**
	 * Resolves the complete definition and executable binding of a pinned tool version.
	 * @param id the qualified tool id
	 * @param version the exact version to resolve
	 * @return the entry, or empty when the tool or that version is no longer available
	 */
	Optional<ToolCatalogEntry> resolve(ToolId id, String version);

	/**
	 * Optional revision of the provider's summary set. When present, the catalog may
	 * reuse cached summaries until the revision changes; when empty, summaries are
	 * fetched on every search. Authorization results are never cached.
	 * @return the current revision, or empty when the provider does not track revisions
	 */
	default Optional<String> revision() {
		return Optional.empty();
	}

}
