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

import java.util.List;
import java.util.Objects;

/** A closed expression vocabulary for workflow data mapping and routing. */
public sealed interface WorkflowValue
		permits WorkflowValue.Literal, WorkflowValue.Input, WorkflowValue.State, WorkflowValue.StepOutput,
		WorkflowValue.Compare, WorkflowValue.All, WorkflowValue.Any, WorkflowValue.Not {

	record Literal(Object value) implements WorkflowValue {
	}

	record Input(String path) implements WorkflowValue {
		public Input {
			WorkflowValidation.path(path, "input path");
		}
	}

	record State(String path) implements WorkflowValue {
		public State {
			WorkflowValidation.path(path, "state path");
		}
	}

	record StepOutput(String stepId, String path) implements WorkflowValue {
		public StepOutput {
			WorkflowValidation.identifier(stepId, "stepId");
			WorkflowValidation.path(path, "step output path");
		}
	}

	record Compare(Operator operator, WorkflowValue left, WorkflowValue right) implements WorkflowValue {
		public Compare {
			Objects.requireNonNull(operator, "operator must not be null");
			Objects.requireNonNull(left, "left must not be null");
			Objects.requireNonNull(right, "right must not be null");
		}
	}

	record All(List<WorkflowValue> values) implements WorkflowValue {
		public All {
			values = List.copyOf(values);
			if (values.isEmpty()) {
				throw new IllegalArgumentException("all values must not be empty");
			}
		}
	}

	record Any(List<WorkflowValue> values) implements WorkflowValue {
		public Any {
			values = List.copyOf(values);
			if (values.isEmpty()) {
				throw new IllegalArgumentException("any values must not be empty");
			}
		}
	}

	record Not(WorkflowValue value) implements WorkflowValue {
		public Not {
			Objects.requireNonNull(value, "value must not be null");
		}
	}

	enum Operator {

		EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL

	}

	static Literal literal(Object value) {
		return new Literal(value);
	}

	static Input input(String path) {
		return new Input(path);
	}

	static State state(String path) {
		return new State(path);
	}

}
