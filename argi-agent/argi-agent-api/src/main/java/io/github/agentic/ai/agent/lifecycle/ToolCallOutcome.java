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
package io.github.agentic.ai.agent.lifecycle;

import java.util.Objects;

/**
 * The result of a tool call in the neutral lifecycle contract.
 *
 * <p>
 * A tool call either succeeds with a result or fails with an error description.
 * Exactly one of {@code result} and {@code error} is present.
 *
 * @param toolCall the tool call identity, never null
 * @param result the tool result, nullable
 * @param error the failure description, nullable
 */
public record ToolCallOutcome(ToolCallId toolCall, String result, String error) {

	/**
	 * Creates a tool call outcome.
	 * @param toolCall the tool call identity, must not be null
	 * @param result the tool result, nullable
	 * @param error the failure description, nullable
	 */
	public ToolCallOutcome {
		Objects.requireNonNull(toolCall, "toolCall must not be null");
		if ((result == null) == (error == null)) {
			throw new IllegalArgumentException("exactly one of result and error must be present");
		}
	}

	/**
	 * Creates a successful outcome.
	 * @param toolCall the tool call identity, must not be null
	 * @param result the tool result, must not be null
	 * @return the successful outcome
	 */
	public static ToolCallOutcome success(ToolCallId toolCall, String result) {
		return new ToolCallOutcome(toolCall, result, null);
	}

	/**
	 * Creates a failed outcome.
	 * @param toolCall the tool call identity, must not be null
	 * @param error the failure description, must not be null
	 * @return the failed outcome
	 */
	public static ToolCallOutcome failure(ToolCallId toolCall, String error) {
		return new ToolCallOutcome(toolCall, null, error);
	}

	/**
	 * Whether the call succeeded.
	 * @return true when a result is present
	 */
	public boolean isSuccess() {
		return result != null;
	}

}
