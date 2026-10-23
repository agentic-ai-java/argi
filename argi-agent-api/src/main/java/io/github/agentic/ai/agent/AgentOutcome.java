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
package io.github.agentic.ai.agent;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Successful execution boundary: either completed output or suspended work awaiting
 * application input. Unlike the legacy strict-output AgentResult, this is not a
 * schema-validation result and does not require a final assistant message. Errors and
 * cancellation terminate the stream exceptionally. Messages and outputs are authoritative
 * snapshots, not deltas to merge into graph state. Usage is cumulative for this
 * invocation, including all its model turns, not lifetime run usage.
 */
public record AgentOutcome(Status status, List<ChatMessage> messages, Map<String, Object> outputs, Usage usage,
		Suspension suspension) {

	public enum Status {

		COMPLETED, SUSPENDED

	}

	public AgentOutcome {
		Objects.requireNonNull(status, "status must not be null");
		messages = List.copyOf(messages);
		outputs = Map.copyOf(outputs);
		Objects.requireNonNull(usage, "usage must not be null");
		if ((status == Status.SUSPENDED) != (suspension != null)) {
			throw new IllegalArgumentException("Only suspended outcomes require suspension data");
		}
	}

	public static AgentOutcome completed(List<ChatMessage> messages, Map<String, Object> outputs, Usage usage) {
		return new AgentOutcome(Status.COMPLETED, messages, outputs, usage, null);
	}

	/**
	 * Opaque continuation and application-visible prompts; neither authenticates a
	 * caller.
	 */
	public record Suspension(String resumeToken, Map<String, Object> prompts) {
		public Suspension {
			Objects.requireNonNull(resumeToken, "resumeToken must not be null");
			if (resumeToken.isBlank()) {
				throw new IllegalArgumentException("resumeToken must not be blank");
			}
			prompts = Map.copyOf(prompts);
		}
	}

}
