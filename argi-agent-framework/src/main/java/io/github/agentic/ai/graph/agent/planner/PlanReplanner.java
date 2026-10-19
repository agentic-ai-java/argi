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

/**
 * Produces the next plan revision after a step failure, for example through a model call
 * or application rules. The proposal is untrusted data and is rejected unless it:
 * <ul>
 * <li>passes {@link PlanValidator#validateGenerated(PlanSpec)}: every step is
 * {@code PENDING} without result or error, so completed outcomes cannot be claimed;</li>
 * <li>uses exactly {@link Request#nextRevision()} and the same objective;</li>
 * <li>retains every completed step with an unchanged definition (the executor restores
 * their outcomes from its own ledger, so they never run again);</li>
 * <li>never reuses the ID of a failed or retired step, nor changes the definition behind
 * a retained ID: changed parameters require a new step identity;</li>
 * <li>contains new work, and does not repeat a definition that already failed.</li>
 * </ul>
 */
@FunctionalInterface
public interface PlanReplanner {

	/**
	 * Propose the next revision. Returning {@code null} is an empty decision that
	 * terminates the run.
	 * @param request the current progress and failure
	 * @return the proposed plan
	 * @throws Exception when no proposal could be produced
	 */
	PlanSpec replan(Request request) throws Exception;

	/**
	 * Replanning input.
	 *
	 * @param currentPlan the current revision including step statuses, results and errors
	 * @param failedStepIds the steps whose failure requested replanning
	 * @param completedResults JSON results of completed steps, keyed by step ID
	 * @param nextRevision the revision the proposal must carry
	 * @param replanAttempt the 1-based replanning attempt
	 */
	record Request(PlanSpec currentPlan, List<String> failedStepIds, Map<String, String> completedResults,
			long nextRevision, int replanAttempt) {

		public Request {
			failedStepIds = failedStepIds == null ? List.of() : List.copyOf(failedStepIds);
			completedResults = completedResults == null ? Map.of()
					: Collections.unmodifiableMap(new LinkedHashMap<>(completedResults));
		}

	}

}
