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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the strict-mode read-only state view.
 */
class StateIsolationTest {

	@Test
	void topLevelPutIsRejected() {
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("todos", "pending"));
		assertThatThrownBy(() -> view.put("newKey", "value")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void listValueMutationIsRejected() {
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("visitedUrls", List.of("a", "b")));
		@SuppressWarnings("unchecked")
		List<String> urls = (List<String>) view.get("visitedUrls");
		assertThatThrownBy(() -> urls.add("c")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void mapValueMutationIsRejected() {
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("counters", Map.of("searches", 1)));
		@SuppressWarnings("unchecked")
		Map<String, Object> counters = (Map<String, Object>) view.get("counters");
		assertThatThrownBy(() -> counters.put("searches", 2)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void listValueIsDecoupledFromTheUnderlyingState() {
		List<String> shared = new java.util.ArrayList<>(List.of("a"));
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("visitedUrls", shared));
		shared.add("mutated-after-view");
		@SuppressWarnings("unchecked")
		List<String> urls = (List<String>) view.get("visitedUrls");
		assertThat(urls).containsExactly("a");
	}

	@Test
	void scalarValuesPassThrough() {
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("token", "abc", "count", 3));
		assertThat(view).containsEntry("token", "abc").containsEntry("count", 3);
	}

	@Test
	void nullAndEmptyStatesYieldAnEmptyView() {
		assertThat(StateIsolation.readOnlyView(null)).isEmpty();
		assertThat(StateIsolation.readOnlyView(Map.of())).isEmpty();
	}

	// === nested containers: mutation is rejected at any depth ===

	@Test
	void nestedMapInsideListRejectsMutation() {
		Map<String, Object> record = new java.util.HashMap<>(Map.of("approved", false));
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("records", List.of(record)));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> records = (List<Map<String, Object>>) view.get("records");
		assertThatThrownBy(() -> records.get(0).put("approved", true))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(record).containsEntry("approved", false);
	}

	@Test
	void nestedListInsideMapRejectsMutation() {
		List<Object> items = new java.util.ArrayList<>(List.of("a"));
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("groups", Map.of("items", items)));
		@SuppressWarnings("unchecked")
		Map<String, List<Object>> groups = (Map<String, List<Object>>) view.get("groups");
		assertThatThrownBy(() -> groups.get("items").add("b")).isInstanceOf(UnsupportedOperationException.class);
		assertThat(items).containsExactly("a");
	}

	@Test
	void multiLevelNestingRejectsMutationAtEveryDepth() {
		Map<String, Object> leaf = new java.util.HashMap<>(Map.of("approved", false));
		List<Object> middle = new java.util.ArrayList<>(List.of(leaf));
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("matrix", List.of(middle)));
		@SuppressWarnings("unchecked")
		List<List<Map<String, Object>>> matrix = (List<List<Map<String, Object>>>) view.get("matrix");
		assertThatThrownBy(() -> matrix.get(0).add(Map.of("extra", true)))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> matrix.get(0).get(0).put("approved", true))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(leaf).containsEntry("approved", false);
		assertThat(middle).hasSize(1);
	}

	@Test
	void cyclicStructureIsWrappedWithoutRecursionOverflow() {
		List<Object> cycle = new java.util.ArrayList<>();
		cycle.add(cycle);
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("cycle", cycle));
		@SuppressWarnings("unchecked")
		List<Object> wrapped = (List<Object>) view.get("cycle");
		assertThatThrownBy(() -> wrapped.add("x")).isInstanceOf(UnsupportedOperationException.class);
		assertThat(wrapped.get(0)).isSameAs(wrapped);
	}

	@Test
	void sharedNestedContainerIsWrappedOnce() {
		Map<String, Object> shared = new java.util.HashMap<>(Map.of("k", 1));
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("left", shared, "right", shared));
		assertThat(view.get("left")).isSameAs(view.get("right"));
	}

	@Test
	void nonContainerMutableValuesPassThroughUnwrapped() {
		// Declared boundary: strict mode protects List/Map containers; other mutable
		// types (arrays, business objects) keep their existing pass-through behavior.
		String[] array = { "a" };
		StringBuilder builder = new StringBuilder("b");
		Map<String, Object> view = StateIsolation.readOnlyView(Map.of("array", array, "builder", builder));
		assertThat(view.get("array")).isSameAs(array);
		assertThat(view.get("builder")).isSameAs(builder);
	}

}
