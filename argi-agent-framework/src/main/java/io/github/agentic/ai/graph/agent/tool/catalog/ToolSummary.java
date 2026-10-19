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

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lightweight, cacheable description of one catalog tool version.
 *
 * <p>
 * A summary deliberately carries no input schema and no executable binding: it is what
 * retrieval strategies rank and what may be cached between searches. Holding a summary
 * grants nothing; the full schema is only released through
 * {@link ToolCatalog#offer(java.util.Collection, io.github.agentic.ai.graph.execution.TrustedExecutionContext)},
 * which re-checks access.
 * </p>
 *
 * @param id the qualified tool identity
 * @param version the tool definition version this summary describes
 * @param summary short human readable description, may be empty
 * @param domain optional functional domain such as {@code payments}, may be {@code null}
 * @param tags optional free-form tags used by rule and keyword retrieval
 */
public record ToolSummary(ToolId id, String version, String summary, String domain, Set<String> tags) {

	public ToolSummary {
		Objects.requireNonNull(id, "id must not be null");
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("version must not be blank");
		}
		summary = summary == null ? "" : summary;
		domain = domain == null || domain.isBlank() ? null : domain;
		TreeSet<String> copy = new TreeSet<>();
		if (tags != null) {
			for (String tag : tags) {
				if (tag == null || tag.isBlank()) {
					throw new IllegalArgumentException("tags must not contain blank values");
				}
				copy.add(tag);
			}
		}
		tags = Collections.unmodifiableSet(copy);
	}

	public static ToolSummary of(ToolId id, String version, String summary) {
		return new ToolSummary(id, version, summary, null, Set.of());
	}

}
