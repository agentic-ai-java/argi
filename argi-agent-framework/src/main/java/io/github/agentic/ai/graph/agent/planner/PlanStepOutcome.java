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
package io.github.agentic.ai.graph.agent.planner;

import java.util.Objects;

import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolOutcome;

/**
 * Structured result of one step attempt. A step is completed only when the outcome is
 * {@link Kind#SUCCEEDED} and its result validates against the step output schema; a
 * missing result is never treated as success.
 *
 * @param kind whether the attempt succeeded, failed or needs external input
 * @param result JSON-encoded output for a successful attempt
 * @param outcome failure status using the {@link ToolOutcome} vocabulary
 * @param error diagnostic text for a failure, or the prompt for {@link Kind#NEED_INPUT}
 * @param effectReference optional reference (receipt or journal ID) of an external effect
 */
public record PlanStepOutcome(Kind kind, String result, String outcome, String error, String effectReference) {

	public PlanStepOutcome {
		Objects.requireNonNull(kind, "kind");
		if (kind == Kind.SUCCEEDED) {
			outcome = ToolOutcome.SUCCESS;
		}
		else if (kind == Kind.FAILED && (outcome == null || outcome.isBlank() || ToolOutcome.SUCCESS.equals(outcome))) {
			outcome = ToolOutcome.ERROR;
		}
	}

	public static PlanStepOutcome succeeded(String result) {
		return new PlanStepOutcome(Kind.SUCCEEDED, result, null, null, null);
	}

	public static PlanStepOutcome succeeded(String result, String effectReference) {
		return new PlanStepOutcome(Kind.SUCCEEDED, result, null, null, effectReference);
	}

	public static PlanStepOutcome failed(String outcome, String error) {
		return new PlanStepOutcome(Kind.FAILED, null, outcome, error, null);
	}

	public static PlanStepOutcome failed(String outcome, String error, String effectReference) {
		return new PlanStepOutcome(Kind.FAILED, null, outcome, error, effectReference);
	}

	public static PlanStepOutcome needInput(String prompt) {
		return new PlanStepOutcome(Kind.NEED_INPUT, null, null, prompt, null);
	}

	/**
	 * Map a tool response produced by the existing tool path, preserving its outcome
	 * status. The generic {@code error} status is refined by its failure kind so that
	 * timeouts and cancellations keep their unknown-effect meaning.
	 * @param response the tool response
	 * @return the equivalent step outcome
	 */
	public static PlanStepOutcome fromToolCallResponse(ToolCallResponse response) {
		Objects.requireNonNull(response, "response");
		String status = response.getStatus();
		if (ToolOutcome.SUCCESS.equals(status)) {
			return succeeded(response.getResult());
		}
		if (status == null || ToolOutcome.ERROR.equals(status)) {
			String kind = response.getFailureKind().orElse("");
			if (ToolCallResponse.FAILURE_KIND_TIMEOUT.equals(kind)) {
				status = ToolOutcome.TIMEOUT;
			}
			else if (ToolCallResponse.FAILURE_KIND_CANCELLED.equals(kind)) {
				status = ToolOutcome.CANCELLED;
			}
			else {
				status = ToolOutcome.ERROR;
			}
		}
		return failed(status, response.getResult());
	}

	/** Attempt result kind. */
	public enum Kind {

		SUCCEEDED, FAILED, NEED_INPUT

	}

}
