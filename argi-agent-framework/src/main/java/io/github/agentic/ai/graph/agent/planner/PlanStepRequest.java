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
import java.util.Map;

/**
 * Input handed to a {@link PlanStepExecutor} for one attempt of a ready step.
 *
 * @param objective the plan objective
 * @param revision the plan revision that scheduled this attempt
 * @param step the step definition; its status is always {@code PENDING}
 * @param stepKey a stable identity derived from the step ID and definition. It does not
 * change across retries of the same step and always changes when the definition changes,
 * so it is suitable as an idempotency key for external effects
 * @param attempt the 1-based attempt number of this step identity
 * @param dependencyResults JSON results of the completed dependencies, keyed by step ID
 * @param input JSON input supplied after a {@code NEED_INPUT} interruption, or
 * {@code null}
 */
public record PlanStepRequest(String objective, long revision, PlanStep step, String stepKey, int attempt,
		Map<String, String> dependencyResults, String input) {

	public PlanStepRequest {
		dependencyResults = dependencyResults == null ? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(dependencyResults));
	}

}
