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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.agentic.ai.graph.agent.action.ActionDecision;
import io.github.agentic.ai.graph.agent.action.ActionType;

/**
 * Authoritative, JSON-serializable progress ledger of a plan run. It is stored as a JSON
 * string under {@link PlanExecutor#RUN_KEY}, so it survives every checkpoint serializer
 * and never changes existing state keys. Step outcomes in {@link #plan()} are written
 * only by the executor, never copied from planner output.
 *
 * @param status the run status
 * @param reasonCode the latest {@link PlanReasonCodes reason code}
 * @param detail optional short diagnostic text
 * @param plan the current revision with step statuses, results and errors
 * @param revisions every adopted revision in order
 * @param replans replanning attempts made, valid or not
 * @param stepExecutions step dispatches consumed from the budget
 * @param startedAtMillis epoch milliseconds at which the run started
 * @param attempts attempts per step ID of the current revision
 * @param effectReferences effect references reported per step ID, including failed ones
 * @param retiredStepIds step IDs that failed or were dropped and can never be reused
 * @param failedDefinitions definition fingerprints that already failed
 * @param failedStepIds steps awaiting replanning
 * @param awaitingStepId the step waiting for input when {@link PlanRunStatus#NEED_INPUT}
 * @param awaitingPrompt the input request of the awaited step
 * @param inputs supplied JSON inputs per step ID
 */
public record PlanRun(PlanRunStatus status, String reasonCode, String detail, PlanSpec plan, List<Long> revisions,
		int replans, int stepExecutions, long startedAtMillis, Map<String, Integer> attempts,
		Map<String, String> effectReferences, List<String> retiredStepIds, List<String> failedDefinitions,
		List<String> failedStepIds, String awaitingStepId, String awaitingPrompt, Map<String, String> inputs) {

	public PlanRun {
		revisions = revisions == null ? List.of() : List.copyOf(revisions);
		attempts = copy(attempts);
		effectReferences = copy(effectReferences);
		retiredStepIds = retiredStepIds == null ? List.of() : List.copyOf(retiredStepIds);
		failedDefinitions = failedDefinitions == null ? List.of() : List.copyOf(failedDefinitions);
		failedStepIds = failedStepIds == null ? List.of() : List.copyOf(failedStepIds);
		inputs = copy(inputs);
	}

	/** @return {@code true} when the run can no longer make progress */
	public boolean terminal() {
		return this.status.isTerminal();
	}

	/** @return JSON results of completed steps in plan order, keyed by step ID */
	public Map<String, String> results() {
		Map<String, String> results = new LinkedHashMap<>();
		for (PlanStep step : this.plan.steps()) {
			if (step.status() == PlanStep.Status.COMPLETED) {
				results.put(step.stepId(), step.result());
			}
		}
		return Collections.unmodifiableMap(results);
	}

	/**
	 * Express the run state with the shared action vocabulary: completion is a final
	 * response, a pending replan or input request maps to {@code REPLAN}/{@code NEED_INPUT},
	 * every other terminal state is a bounded {@code STOP}.
	 * @return the decision, or {@code null} while steps are still running
	 */
	public ActionDecision decision() {
		return switch (this.status) {
			case COMPLETED -> ActionDecision.of(ActionType.FINAL_RESPONSE, this.reasonCode, this.detail);
			case NEED_INPUT -> ActionDecision.of(ActionType.NEED_INPUT, this.reasonCode, this.detail);
			case RUNNING -> this.failedStepIds.isEmpty() ? null
					: ActionDecision.of(ActionType.REPLAN, this.reasonCode, this.detail);
			default -> ActionDecision.of(ActionType.STOP, this.reasonCode, this.detail);
		};
	}

	private static <V> Map<String, V> copy(Map<String, V> map) {
		return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
	}

}
