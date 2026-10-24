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

import java.util.Objects;

import io.github.agentic.ai.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchValidator;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchViolationException;

/**
 * Strict-mode admission for one {@link ToolUpdateUnit} at the merge boundary.
 *
 * <p>
 * This is the single implementation of the slice-1 gate, shared by every execution path:
 * a failed call's patch is discarded outright (a hard rule in strict mode, independent
 * of {@link ToolContextPatchPolicy#discardPatchOnFailedResult()}), then the patch is
 * validated against the reserved namespace and the tool's declared spec. A violation
 * either discards the whole patch or aborts the run with
 * {@link ToolContextPatchViolationException}, exactly as the policy configures. The
 * failure branch never escalates to an abort — stray writes of an aborted call are
 * dropped, not treated as hostile.
 * </p>
 */
public final class StrictPatchAdmission {

	private StrictPatchAdmission() {
	}

	/**
	 * Decides whether a unit's patch may be merged into the next turn's state.
	 * @param policy the configured patch policy, or {@code null} when only the strict
	 * hard rules apply
	 * @param unit the tool call's update unit
	 * @return {@code true} when the patch may be merged
	 * @throws ToolContextPatchViolationException when the patch violates validation and
	 * the policy is {@link ToolContextPatchPolicy.ViolationPolicy#ABORT_RUN}
	 */
	public static boolean admit(ToolContextPatchPolicy policy, ToolUpdateUnit unit) {
		Objects.requireNonNull(unit, "unit must not be null");
		if (unit.patch().isEmpty()) {
			return true;
		}
		if (!unit.resultOk()) {
			return false;
		}
		if (policy == null) {
			return true;
		}
		var failure = ToolContextPatchValidator.validate(policy.specFor(unit.toolName()), unit.toolName(),
				unit.patch());
		if (failure.isPresent()) {
			if (policy.violationPolicy() == ToolContextPatchPolicy.ViolationPolicy.ABORT_RUN) {
				throw new ToolContextPatchViolationException(failure.get());
			}
			return false;
		}
		return true;
	}

}
