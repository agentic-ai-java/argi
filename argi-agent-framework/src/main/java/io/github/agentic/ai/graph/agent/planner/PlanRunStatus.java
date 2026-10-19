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
 * Lifecycle of a plan run. Only {@link #COMPLETED} is success, and it requires every step
 * of the current revision to be completed with a schema-valid result.
 */
public enum PlanRunStatus {

	/** Ready steps remain or a replan is pending. */
	RUNNING,

	/** Suspended until input for the awaited step is supplied; resumable. */
	NEED_INPUT,

	/** Every step of the current revision completed. */
	COMPLETED,

	/** A step failure stopped the run, or no proposal could be obtained. */
	FAILED,

	/** A proposed revision violated the plan or replanning contract. */
	INVALID_PLAN,

	/** A proposal was empty or repeated work that already failed. */
	REPLAN_REJECTED,

	/** The replanning limit was reached. */
	REPLAN_LIMIT_EXCEEDED,

	/** The step execution budget was exhausted. */
	BUDGET_EXHAUSTED,

	/** The run deadline passed. */
	DEADLINE_EXCEEDED;

	public boolean isTerminal() {
		return this != RUNNING && this != NEED_INPUT;
	}

}
