/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;

import java.util.Map;

/**
 * Application boundary for pausing a ReAct run when a human decision is required.
 * Durable storage, token authentication and replay protection remain application
 * responsibilities; this interface does not turn a token into a credential.
 */
public interface HumanInTheLoop {

	/**
	 * Validates an opaque continuation before the run consumes resumed inputs.
	 * Implementations should reject stale, replayed or cross-tenant tokens.
	 */
	default void validateResume(AgentRequest request, AgentExecutionContext context) {
	}

	/** Creates the application-owned continuation returned to the caller. */
	AgentOutcome.Suspension suspend(HumanRequest request, AgentExecutionContext context);

	record HumanRequest(String reason, String toolName, String toolCallId, Map<String, Object> details) {

		public HumanRequest {
			reason = reason == null ? "Human approval is required" : reason;
			details = details == null ? Map.of() : Map.copyOf(details);
		}

	}

}
