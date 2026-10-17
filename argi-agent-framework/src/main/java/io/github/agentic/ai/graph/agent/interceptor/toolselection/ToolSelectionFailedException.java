/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.agentic.ai.graph.agent.interceptor.toolselection;

import io.github.agentic.ai.graph.agent.action.ActionDecision;
import io.github.agentic.ai.graph.agent.exception.AgentException;

/**
 * Thrown by {@link ToolSelectionInterceptor} when tool selection fails under
 * {@link ToolSelectionFailurePolicy#FAIL_CLOSED_THROW}.
 *
 * <p>The carried {@link ActionDecision} always uses the
 * {@link io.github.agentic.ai.graph.agent.action.ActionType#STOP} action; its reason code
 * distinguishes a malformed selection response from a selection model failure.</p>
 */
public class ToolSelectionFailedException extends AgentException {

	private final ActionDecision decision;

	/**
	 * Creates the exception for a failed tool selection.
	 * @param decision the recorded decision explaining why the run stopped
	 * @param cause the original selection failure, may be {@code null}
	 */
	public ToolSelectionFailedException(ActionDecision decision, Throwable cause) {
		super(message(decision), cause);
		this.decision = decision;
	}

	private static String message(ActionDecision decision) {
		final String detail = decision.detail();
		return detail == null ? "Tool selection failed: " + decision.reasonCode()
				: "Tool selection failed: " + decision.reasonCode() + " (" + detail + ")";
	}

	/**
	 * Returns the recorded decision explaining why the run stopped.
	 * @return the decision, never {@code null}
	 */
	public ActionDecision getDecision() {
		return this.decision;
	}

}
