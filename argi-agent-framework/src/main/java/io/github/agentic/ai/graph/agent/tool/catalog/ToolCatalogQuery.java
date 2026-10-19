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
import java.util.Set;
import java.util.TreeSet;

/**
 * A catalog retrieval request.
 *
 * <p>
 * {@code domains} and {@code tags} are rule filters: when non-empty, a candidate must
 * match at least one domain and at least one tag. {@code text} is free text ranked by the
 * configured {@link ToolCatalogSearchStrategy}. A query with neither text nor filters
 * matches nothing, so an empty query can never be used to enumerate the catalog.
 * </p>
 *
 * @param text free-text query, may be empty
 * @param domains accepted domains, empty for any
 * @param tags accepted tags, empty for any
 * @param topK requested maximum number of results; the catalog applies its own upper
 * bound as well
 */
public record ToolCatalogQuery(String text, Set<String> domains, Set<String> tags, int topK) {

	public ToolCatalogQuery {
		text = text == null ? "" : text;
		domains = copy(domains, "domains");
		tags = copy(tags, "tags");
		if (topK <= 0) {
			throw new IllegalArgumentException("topK must be > 0");
		}
	}

	public static ToolCatalogQuery text(String text, int topK) {
		return new ToolCatalogQuery(text, Set.of(), Set.of(), topK);
	}

	/** Whether the query carries no text and no rule filter. */
	public boolean isUnconstrained() {
		return this.text.isBlank() && this.domains.isEmpty() && this.tags.isEmpty();
	}

	private static Set<String> copy(Set<String> values, String name) {
		TreeSet<String> copy = new TreeSet<>();
		if (values != null) {
			for (String value : values) {
				if (value == null || value.isBlank()) {
					throw new IllegalArgumentException(name + " must not contain blank values");
				}
				copy.add(value);
			}
		}
		return Collections.unmodifiableSet(copy);
	}

}
