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
 * Application-owned bridge from a validated plan step to an already authorized
 * capability. The executor resolves {@link PlanStep#executionTarget()} itself (for
 * example through an existing tool, agent or sub-agent call) and keeps the existing
 * authorization, validation and interception gates on that path. Planner output is never
 * authorization.
 *
 * <p>
 * Implementations should use {@link PlanStepRequest#stepKey()} as the idempotency key for
 * external effects and must report {@link io.github.agentic.ai.graph.agent.interceptor.ToolOutcome#UNCERTAIN}
 * or {@link io.github.agentic.ai.graph.agent.interceptor.ToolOutcome#TIMEOUT} when the
 * effect of a failed attempt is unknown, so that the default policy stops instead of
 * repeating the effect. Thrown exceptions are recorded as generic failures.
 * </p>
 */
@FunctionalInterface
public interface PlanStepExecutor {

	/**
	 * Execute one ready step. A {@code null} outcome is a failure, never a success.
	 * @param request the step and its trusted execution inputs
	 * @return the step outcome
	 * @throws Exception when the capability fails without a structured outcome
	 */
	PlanStepOutcome execute(PlanStepRequest request) throws Exception;

}
