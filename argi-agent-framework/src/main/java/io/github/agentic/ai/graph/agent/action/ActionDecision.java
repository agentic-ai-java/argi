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

package io.github.agentic.ai.graph.agent.action;

import java.util.Objects;

/**
 * An explicit routing decision: the {@link ActionType} to take, a short reason code and
 * an optional operational detail.
 *
 * <p>Decisions never carry private chain-of-thought; the detail is limited to a short
 * operational description. Opt-in components may publish a decision under
 * {@link #CONTEXT_KEY} in the model request context so downstream consumers can observe
 * why a run proceeded without tools or stopped.</p>
 *
 * @param type the action to take
 * @param reasonCode a short stable reason label, see {@link ActionReasonCodes}
 * @param detail an optional short operational description, may be {@code null}
 */
public record ActionDecision(ActionType type, String reasonCode, String detail) {

	/**
	 * Context key under which opt-in interceptors publish the current decision in the
	 * model request context.
	 */
	public static final String CONTEXT_KEY = "argi.actionDecision";

	public ActionDecision {
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(reasonCode, "reasonCode must not be null");
	}

	/**
	 * Creates a decision without operational detail.
	 * @param type the action to take
	 * @param reasonCode a short stable reason label
	 * @return the decision
	 */
	public static ActionDecision of(ActionType type, String reasonCode) {
		return new ActionDecision(type, reasonCode, null);
	}

	/**
	 * Creates a decision with operational detail.
	 * @param type the action to take
	 * @param reasonCode a short stable reason label
	 * @param detail a short operational description, may be {@code null}
	 * @return the decision
	 */
	public static ActionDecision of(ActionType type, String reasonCode, String detail) {
		return new ActionDecision(type, reasonCode, detail);
	}

}
