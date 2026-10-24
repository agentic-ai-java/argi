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

}
