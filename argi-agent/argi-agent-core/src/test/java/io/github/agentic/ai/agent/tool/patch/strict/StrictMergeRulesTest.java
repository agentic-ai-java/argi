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
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the strict-mode undeclared-conflict merge rule.
 */
class StrictMergeRulesTest {

	@Test
	void twoWritersWithoutStrategyConflict() {
		var failure = StrictMergeRules
			.checkUndeclaredConflicts(List.of(Map.of("notes", "a"), Map.of("notes", "b")), Set.of())
			.orElseThrow();
		assertThat(failure.errorCode()).isEqualTo(StrictMergeRules.UNDECLARED_CONFLICT);
		assertThat(failure.key()).isEqualTo("notes");
	}

	@Test
	void twoWritersWithStrategyPass() {
		var result = StrictMergeRules.checkUndeclaredConflicts(List.of(Map.of("notes", "a"), Map.of("notes", "b")),
				Set.of("notes"));
		assertThat(result).isEmpty();
	}

	@Test
	void singleWriterWithoutStrategyPasses() {
		var result = StrictMergeRules.checkUndeclaredConflicts(List.of(Map.of("notes", "a")), Set.of());
		assertThat(result).isEmpty();
	}

	@Test
	void firstConflictIsReportedInSortedKeyOrder() {
		var failure = StrictMergeRules
			.checkUndeclaredConflicts(
					List.of(Map.of("zKey", 1, "aKey", 1), Map.of("zKey", 2, "aKey", 2, "mKey", 2)),
					Set.of("mKey"))
			.orElseThrow();
		assertThat(failure.key()).isEqualTo("aKey");
	}

	@Test
	void nullAndEmptyPatchesAreSkipped() {
		var result = StrictMergeRules.checkUndeclaredConflicts(
				java.util.Arrays.asList(null, Map.of(), Map.of("notes", "a")), null);
		assertThat(result).isEmpty();
	}

}
