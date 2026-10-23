/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.workflow;

import java.util.HashSet;
import java.util.Set;

/** Structural validation performed before a definition can be published. */
public final class WorkflowDefinitionValidator {

	private WorkflowDefinitionValidator() {
	}

	public static void validate(WorkflowDefinition definition) {
		if (definition.inputSchema() != null && definition.inputSchema().isBlank()) {
			throw new IllegalArgumentException("inputSchema must not be blank");
		}
		if (definition.outputSchema() != null && definition.outputSchema().isBlank()) {
			throw new IllegalArgumentException("outputSchema must not be blank");
		}
		Set<String> ids = new HashSet<>();
		definition.steps().forEach(step -> validateStep(step, ids));
	}

	private static void validateStep(WorkflowStep step, Set<String> ids) {
		if (!ids.add(step.id())) {
			throw new IllegalArgumentException("duplicate workflow step id: " + step.id());
		}
		if (step instanceof WorkflowStep.Sequence sequence) {
			sequence.steps().forEach(child -> validateStep(child, ids));
		}
		else if (step instanceof WorkflowStep.Condition condition) {
			validateStep(condition.whenTrue(), ids);
			if (condition.whenFalse() != null) {
				validateStep(condition.whenFalse(), ids);
			}
		}
		else if (step instanceof WorkflowStep.Parallel parallel) {
			parallel.branches().forEach(child -> validateStep(child, ids));
		}
		else if (step instanceof WorkflowStep.Loop loop) {
			validateStep(loop.body(), ids);
		}
	}

}
