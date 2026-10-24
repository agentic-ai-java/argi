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

import io.github.agentic.ai.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchSpec;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchValidator;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for strict-mode update-unit admission.
 */
class StrictPatchAdmissionTest {

	@Test
	void failedResultIsDiscardedEvenWhenPolicyKeepsFailedPatches() {
		var policy = ToolContextPatchPolicy.builder().discardPatchOnFailedResult(false).build();
		var unit = ToolUpdateUnit.failure("search", Map.of("search.nextPageToken", "t1"));
		assertThat(StrictPatchAdmission.admit(policy, unit)).isFalse();
	}

	@Test
	void failedResultIsDiscardedWithoutAnyPolicy() {
		var unit = ToolUpdateUnit.failure("search", Map.of("search.nextPageToken", "t1"));
		assertThat(StrictPatchAdmission.admit(null, unit)).isFalse();
	}

	@Test
	void successfulUnitPassesWithNullPolicy() {
		var unit = ToolUpdateUnit.success("search", Map.of("search.nextPageToken", "t1"));
		assertThat(StrictPatchAdmission.admit(null, unit)).isTrue();
	}

	@Test
	void emptyPatchIsAlwaysAdmitted() {
		var unit = ToolUpdateUnit.failure("search", null);
		assertThat(StrictPatchAdmission.admit(null, unit)).isTrue();
	}

	@Test
	void reservedKeyIsDiscardedUnderDiscardPolicy() {
		var policy = ToolContextPatchPolicy.builder().build();
		var unit = ToolUpdateUnit.success("tool", Map.of("messages", List.of()));
		assertThat(StrictPatchAdmission.admit(policy, unit)).isFalse();
	}

	@Test
	void reservedKeyAbortsUnderAbortPolicy() {
		var policy = ToolContextPatchPolicy.builder()
			.onViolation(ToolContextPatchPolicy.ViolationPolicy.ABORT_RUN)
			.build();
		var unit = ToolUpdateUnit.success("tool", Map.of("argi.lease", "lease-1"));
		assertThatThrownBy(() -> StrictPatchAdmission.admit(policy, unit))
			.isInstanceOf(ToolContextPatchViolationException.class)
			.extracting(e -> ((ToolContextPatchViolationException) e).failure().errorCode())
			.isEqualTo(ToolContextPatchValidator.RESERVED_KEY);
	}

	@Test
	void undeclaredKeyIsDiscardedAgainstSpec() {
		var spec = ToolContextPatchSpec.builder().writableKey("search.nextPageToken").build();
		var policy = ToolContextPatchPolicy.builder().specFor("search", spec).build();
		var unit = ToolUpdateUnit.success("search", Map.of("search.otherKey", "value"));
		assertThat(StrictPatchAdmission.admit(policy, unit)).isFalse();
	}

	@Test
	void declaredKeyWithMatchingTypeIsAdmitted() {
		var spec = ToolContextPatchSpec.builder()
			.writableKey("search.visitedUrls")
			.keyType("search.visitedUrls", List.class)
			.build();
		var policy = ToolContextPatchPolicy.builder().specFor("search", spec).build();
		var unit = ToolUpdateUnit.success("search", Map.of("search.visitedUrls", List.of("a")));
		assertThat(StrictPatchAdmission.admit(policy, unit)).isTrue();
	}

}
