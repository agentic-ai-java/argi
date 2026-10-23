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

package io.github.agentic.ai.graph.execution.run;

import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.TrustedExecutionContextReference;

import static java.util.Objects.requireNonNull;

/** Versioned task data selecting registered task code, not a Supplier or Future. */
public record TaskSpec(int schemaVersion, String taskId, String runId, String taskType, String taskVersion,
		RunPayload input, TrustedExecutionContextReference context) {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	public TaskSpec {
		RunValidation.version(schemaVersion);
		RunValidation.text(taskId, "taskId");
		RunValidation.text(runId, "runId");
		RunValidation.text(taskType, "taskType");
		RunValidation.text(taskVersion, "taskVersion");
		requireNonNull(input, "input cannot be null");
		RunValidation.context(context);
	}

	public String inputHash() {
		return input.inputHash();
	}

	public Long deadlineEpochMillis() {
		return context.deadlineEpochMillis();
	}

	public ExecutionBudget budget() {
		return new ExecutionBudget(context.budgetLimits());
	}

	/**
	 * A task may use a child context, but cannot widen the parent identity or
	 * constraints.
	 */
	public void validateFor(RunSpec run) {
		TrustedExecutionContextReference parent = run.context();
		if (!runId.equals(run.runId()) || !context.tenant().equals(parent.tenant())
				|| !context.principal().equals(parent.principal())
				|| !context.correlationId().equals(parent.correlationId())
				|| !parent.delegationPermissions().containsAll(context.delegationPermissions())) {
			throw new IllegalArgumentException("task identity or permissions exceed the parent run");
		}
		if (parent.deadlineEpochMillis() != null && (context.deadlineEpochMillis() == null
				|| context.deadlineEpochMillis() > parent.deadlineEpochMillis())) {
			throw new IllegalArgumentException("task deadline exceeds the parent run");
		}
		ExecutionBudget child = budget();
		if (!run.budget().narrowTo(child).equals(child)) {
			throw new IllegalArgumentException("task budget omits parent limits");
		}
	}
}
