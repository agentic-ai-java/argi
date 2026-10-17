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

package io.github.agentic.ai.graph.agent.hook.taskcompletion;

import io.github.agentic.ai.graph.agent.action.ActionDecision;

/**
 * Thrown by {@link TaskCompletionHook} in {@link TaskCompletionHook.ExitBehavior#ERROR}
 * mode when a bounded exit is reached. The terminal {@link ActionDecision} is available
 * via {@link #getDecision()}.
 */
public class TaskCompletionExceededException extends RuntimeException {

	private final ActionDecision decision;

	/**
	 * Creates the exception carrying the terminal decision.
	 * @param decision the terminal decision, must not be {@code null}
	 */
	public TaskCompletionExceededException(ActionDecision decision) {
		super(decision.reasonCode() + (decision.detail() == null ? "" : ": " + decision.detail()));
		this.decision = decision;
	}

	/**
	 * Returns the terminal decision that triggered this bounded exit.
	 * @return the terminal decision
	 */
	public ActionDecision getDecision() {
		return decision;
	}

}
