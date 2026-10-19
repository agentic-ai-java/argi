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

import io.github.agentic.ai.graph.agent.interceptor.ToolOutcome;

/**
 * Decides how a failed step attempt continues. Decisions are bounded by
 * {@link PlanExecutionLimits}: a {@link Action#RETRY} beyond the attempt limit becomes a
 * {@link Action#REPLAN}, and replanning is limited by count, deadline and budget.
 */
@FunctionalInterface
public interface PlanFailurePolicy {

	Action decide(Failure failure);

	/**
	 * Default policy: retry transient failures within the attempt limit; stop when the
	 * effect is unknown (timeout, cancellation, uncertain) or authorization was denied, so
	 * replanning cannot repeat an external effect or widen authority; replan otherwise.
	 * @return the default policy
	 */
	static PlanFailurePolicy defaults() {
		return failure -> switch (failure.outcome()) {
			case ToolOutcome.TRANSIENT -> failure.attempt() < failure.maxAttempts() ? Action.RETRY : Action.REPLAN;
			case ToolOutcome.TIMEOUT, ToolOutcome.CANCELLED, ToolOutcome.UNCERTAIN, ToolOutcome.AUTH_DENIED ->
				Action.STOP;
			default -> Action.REPLAN;
		};
	}

	/** Continuation of a failed step. */
	enum Action {

		/** Run the same step identity again; never used for unknown effects by default. */
		RETRY,

		/** Ask the replanner for a new revision that keeps completed steps. */
		REPLAN,

		/** Suspend the run until external input for the step is supplied. */
		NEED_INPUT,

		/** Terminate the run as failed. */
		STOP

	}

	/**
	 * A failed attempt.
	 *
	 * @param step the failed step
	 * @param outcome the failure status in the {@link ToolOutcome} vocabulary
	 * @param error diagnostic text
	 * @param attempt the 1-based attempt number that failed
	 * @param maxAttempts the configured attempt limit per step identity
	 */
	record Failure(PlanStep step, String outcome, String error, int attempt, int maxAttempts) {

	}

}
