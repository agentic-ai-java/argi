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

import java.time.Duration;

/**
 * Bounds of one plan run. Every run is bounded by attempts, replans and the total step
 * execution budget; the wall-clock deadline is optional and measured from the start of
 * the run, including time spent waiting for external input.
 *
 * @param maxParallelism maximum ready steps dispatched together in one local batch
 * @param maxStepAttempts maximum attempts of one step identity, including the first
 * @param maxReplans maximum replanning attempts, valid or not
 * @param maxStepExecutions total step dispatches allowed in the run (the budget)
 * @param deadline optional wall-clock deadline of the run, or {@code null}
 */
public record PlanExecutionLimits(int maxParallelism, int maxStepAttempts, int maxReplans, int maxStepExecutions,
		Duration deadline) {

	public static final int DEFAULT_MAX_PARALLELISM = 4;

	public static final int DEFAULT_MAX_STEP_ATTEMPTS = 2;

	public static final int DEFAULT_MAX_REPLANS = 2;

	public static final int DEFAULT_MAX_STEP_EXECUTIONS = 64;

	public PlanExecutionLimits {
		PlanValidator.require(maxParallelism > 0 && maxStepAttempts > 0 && maxReplans >= 0 && maxStepExecutions > 0,
				"Plan execution limits must be positive");
		PlanValidator.require(deadline == null || (!deadline.isNegative() && !deadline.isZero()),
				"Plan deadline must be positive");
	}

	public static PlanExecutionLimits defaults() {
		return new PlanExecutionLimits(DEFAULT_MAX_PARALLELISM, DEFAULT_MAX_STEP_ATTEMPTS, DEFAULT_MAX_REPLANS,
				DEFAULT_MAX_STEP_EXECUTIONS, null);
	}

	public PlanExecutionLimits withDeadline(Duration deadline) {
		return new PlanExecutionLimits(this.maxParallelism, this.maxStepAttempts, this.maxReplans,
				this.maxStepExecutions, deadline);
	}

}
