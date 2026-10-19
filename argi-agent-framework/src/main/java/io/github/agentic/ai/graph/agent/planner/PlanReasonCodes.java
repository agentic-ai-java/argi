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

/**
 * Short, stable reason codes recorded in {@link PlanRun#reasonCode()}. They are
 * operational labels, never model reasoning.
 */
public final class PlanReasonCodes {

	public static final String STARTED = "PLAN_STARTED";

	public static final String PLAN_COMPLETED = "PLAN_COMPLETED";

	public static final String STEP_FAILED = "PLAN_STEP_FAILED";

	public static final String STEP_RESULT_MISSING = "PLAN_STEP_RESULT_MISSING";

	public static final String STEP_OUTPUT_INVALID = "PLAN_STEP_OUTPUT_INVALID";

	public static final String NEED_INPUT = "PLAN_NEED_INPUT";

	public static final String INPUT_RECEIVED = "PLAN_INPUT_RECEIVED";

	public static final String INPUT_INVALID = "PLAN_INPUT_INVALID";

	public static final String NO_READY_STEPS = "PLAN_NO_READY_STEPS";

	public static final String REPLANNED = "PLAN_REPLANNED";

	public static final String REPLAN_UNAVAILABLE = "PLAN_REPLAN_UNAVAILABLE";

	public static final String REPLAN_FAILED = "PLAN_REPLAN_FAILED";

	public static final String REPLAN_INVALID = "PLAN_REPLAN_INVALID";

	public static final String REPLAN_EMPTY = "PLAN_REPLAN_EMPTY";

	public static final String REPLAN_NO_PROGRESS = "PLAN_REPLAN_NO_PROGRESS";

	public static final String COMPLETED_STEP_DROPPED = "PLAN_COMPLETED_STEP_DROPPED";

	public static final String STEP_IDENTITY_REUSED = "PLAN_STEP_IDENTITY_REUSED";

	public static final String REPLAN_LIMIT_EXCEEDED = "PLAN_REPLAN_LIMIT_EXCEEDED";

	public static final String BUDGET_EXHAUSTED = "PLAN_BUDGET_EXHAUSTED";

	public static final String DEADLINE_EXCEEDED = "PLAN_DEADLINE_EXCEEDED";

	private PlanReasonCodes() {
	}

}
