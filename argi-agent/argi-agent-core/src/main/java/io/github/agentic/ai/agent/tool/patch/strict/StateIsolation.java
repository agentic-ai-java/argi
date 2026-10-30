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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds read-only views of tool-visible state for strict mode.
 *
 * <p>
 * Tools historically received the shared state (or a shallow snapshot of it), so a tool
 * could mutate a shared {@code List} or {@code Map} value — at any nesting depth — and
 * bypass the patch channel. Strict mode replaces that hazard with a structural
 * guarantee: every {@code List} and {@code Map} reached through {@code List}/{@code Map}
 * nesting rejects mutation, so state writes can escape only through the tool's patch.
 * </p>
 *
 * <p>
 * Containers are wrapped over a private backing, so later structural changes to the
 * underlying state cannot surface through an outstanding view mid-execution. Elements
 * are wrapped, never duplicated. Repeated or cyclic container references reuse the same
 * wrapper, so wrapping terminates on cyclic structures. Values that are neither
 * {@code List} nor {@code Map} — scalars, business objects, arrays, other collection
 * types — pass through unchanged: their mutability is outside the declared boundary of
 * strict mode, which protects collection containers only.
 * </p>
 */
public final class StateIsolation {

	private StateIsolation() {
	}

	/**
	 * Returns an unmodifiable view of the given state whose {@code List} and {@code Map}
	 * values — nested through further Lists and Maps to any depth — are unmodifiable as
	 * well.
	 * @param state the state a tool may read, or {@code null}
	 * @return an unmodifiable, recursively wrapped read view; empty when the input is
	 * null or empty
	 */
	public static Map<String, Object> readOnlyView(Map<String, Object> state) {
		if (state == null || state.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> view = new LinkedHashMap<>();
		IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();
		for (Map.Entry<String, Object> entry : state.entrySet()) {
			view.put(entry.getKey(), wrap(entry.getValue(), seen));
		}
		return Collections.unmodifiableMap(view);
	}

	/**
	 * Wraps one value: containers get a private backing with wrapped elements behind an
	 * unmodifiable facade, everything else passes through. The wrapper is registered
	 * before recursing so cyclic or shared containers resolve to the same instance.
	 */
	private static Object wrap(Object value, IdentityHashMap<Object, Object> seen) {
		if (value instanceof List<?> list) {
			Object wrapper = seen.get(list);
			if (wrapper != null) {
				return wrapper;
			}
			List<Object> backing = new ArrayList<>(list.size());
			wrapper = Collections.unmodifiableList(backing);
			seen.put(list, wrapper);
			for (Object element : list) {
				backing.add(wrap(element, seen));
			}
			return wrapper;
		}
		if (value instanceof Map<?, ?> map) {
			Object wrapper = seen.get(map);
			if (wrapper != null) {
				return wrapper;
			}
			Map<Object, Object> backing = new LinkedHashMap<>(map.size());
			wrapper = Collections.unmodifiableMap(backing);
			seen.put(map, wrapper);
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				backing.put(entry.getKey(), wrap(entry.getValue(), seen));
			}
			return wrapper;
		}
		return value;
	}

}
