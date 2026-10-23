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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Declarative workflow nodes. No node contains executable Java or script expressions. */
public sealed interface WorkflowStep
		permits WorkflowStep.Agent, WorkflowStep.Tool, WorkflowStep.Sequence, WorkflowStep.Condition,
		WorkflowStep.Parallel, WorkflowStep.Loop, WorkflowStep.Subflow {

	String id();

	record Agent(String id, String agentRef, Map<String, WorkflowValue> inputs, String outputKey, RetryPolicy retry)
			implements WorkflowStep {
		public Agent {
			WorkflowValidation.identifier(id, "step id");
			WorkflowValidation.identifier(agentRef, "agentRef");
			inputs = Map.copyOf(inputs);
			WorkflowValidation.optionalPath(outputKey, "outputKey");
			retry = RetryPolicy.normalize(retry);
		}

		public Agent(String id, String agentRef) {
			this(id, agentRef, Map.of(), id, RetryPolicy.none());
		}
	}

	record Tool(String id, String toolName, Map<String, WorkflowValue> arguments, String outputKey,
			RetryPolicy retry) implements WorkflowStep {
		public Tool {
			WorkflowValidation.identifier(id, "step id");
			WorkflowValidation.identifier(toolName, "toolName");
			arguments = Map.copyOf(arguments);
			WorkflowValidation.optionalPath(outputKey, "outputKey");
			retry = RetryPolicy.normalize(retry);
		}

		public Tool(String id, String toolName, Map<String, WorkflowValue> arguments) {
			this(id, toolName, arguments, id, RetryPolicy.none());
		}
	}

	record Sequence(String id, List<WorkflowStep> steps) implements WorkflowStep {
		public Sequence {
			WorkflowValidation.identifier(id, "step id");
			steps = List.copyOf(steps);
			if (steps.isEmpty()) {
				throw new IllegalArgumentException("sequence must contain at least one step");
			}
		}
	}

	record Condition(String id, WorkflowValue condition, WorkflowStep whenTrue, WorkflowStep whenFalse)
			implements WorkflowStep {
		public Condition {
			WorkflowValidation.identifier(id, "step id");
			Objects.requireNonNull(condition, "condition must not be null");
			Objects.requireNonNull(whenTrue, "whenTrue must not be null");
		}
	}

	record Parallel(String id, List<WorkflowStep> branches, int maxConcurrency) implements WorkflowStep {
		public Parallel {
			WorkflowValidation.identifier(id, "step id");
			branches = List.copyOf(branches);
			if (branches.isEmpty()) {
				throw new IllegalArgumentException("parallel must contain at least one branch");
			}
			if (maxConcurrency < 1) {
				throw new IllegalArgumentException("maxConcurrency must be positive");
			}
		}

		public Parallel(String id, List<WorkflowStep> branches) {
			this(id, branches, branches.size());
		}
	}

	record Loop(String id, WorkflowValue condition, WorkflowStep body, int maxIterations) implements WorkflowStep {
		public Loop {
			WorkflowValidation.identifier(id, "step id");
			Objects.requireNonNull(condition, "condition must not be null");
			Objects.requireNonNull(body, "body must not be null");
			if (maxIterations < 1) {
				throw new IllegalArgumentException("maxIterations must be positive");
			}
		}
	}

	record Subflow(String id, String workflowId, String version) implements WorkflowStep {
		public Subflow {
			WorkflowValidation.identifier(id, "step id");
			WorkflowValidation.identifier(workflowId, "workflowId");
			WorkflowValidation.version(version, "version");
		}
	}

	record RetryPolicy(int maxAttempts, Duration backoff) {
		public RetryPolicy {
			if (maxAttempts < 1) {
				throw new IllegalArgumentException("maxAttempts must be positive");
			}
			backoff = Objects.requireNonNull(backoff, "backoff must not be null");
			if (backoff.isNegative()) {
				throw new IllegalArgumentException("backoff must not be negative");
			}
		}

		static RetryPolicy none() {
			return new RetryPolicy(1, Duration.ZERO);
		}

		static RetryPolicy normalize(RetryPolicy retry) {
			return retry == null ? none() : retry;
		}
	}

}
