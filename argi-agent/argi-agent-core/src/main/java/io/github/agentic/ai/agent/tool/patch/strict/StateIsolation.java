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
package io.github.agentic.ai.agent.tool.patch.strict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds read-only views of tool-visible state for strict mode.
 *
 * <p>
 * Tools historically received the shared state (or a shallow snapshot of it), so a tool
 * could mutate a shared {@code List} or {@code Map} value directly and bypass the patch
 * channel — the hazard the graph runtime only documents as advice. Strict mode replaces
 * that advice with a structural guarantee: the view handed to a tool rejects every
 * mutation, one level deep, so state writes can escape only through the tool's patch.
 * </p>
 *
 * <p>
 * Collection values are defensively copied before wrapping, so later structural changes
 * to the underlying state cannot surface through an outstanding view mid-execution.
 * Deeper nesting is intentionally left untouched: patches are validated as whole units
 * at the merge boundary, not per-element.
 * </p>
 */
public final class StateIsolation {

	private StateIsolation() {
	}

	/**
	 * Returns an unmodifiable view of the given state whose {@code List} and {@code Map}
	 * values are unmodifiable as well.
	 * @param state the state a tool may read, or {@code null}
	 * @return an unmodifiable, one-level-deep read view; empty when the input is null or
	 * empty
	 */
	public static Map<String, Object> readOnlyView(Map<String, Object> state) {
		if (state == null || state.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> view = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : state.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof List<?> list) {
				view.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(list)));
			}
			else if (value instanceof Map<?, ?> map) {
				view.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(map)));
			}
			else {
				view.put(entry.getKey(), value);
			}
		}
		return Collections.unmodifiableMap(view);
	}

}
