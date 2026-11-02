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
package io.github.agentic.ai.agent.budget;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validates that the tool calls of a conversation and their results stay paired.
 *
 * <p>
 * Compression may remove a message. When it removes a tool result, the assistant
 * message which requested the tool call is left with an unanswered call; when it
 * removes the assistant message, the tool result is left without a call. Both break
 * the conversation, so the budget gate refuses to dispatch such a request.
 */
public final class ToolCallPairingValidator {

	private ToolCallPairingValidator() {
	}

	/**
	 * The kind of a pairing violation.
	 */
	public enum Kind {

		/**
		 * A tool call has no matching tool result.
		 */
		MISSING_TOOL_RESULT,

		/**
		 * A tool result has no matching tool call.
		 */
		UNKNOWN_TOOL_RESULT,

		/**
		 * The same tool call appears more than once.
		 */
		DUPLICATE_TOOL_CALL
	}

	/**
	 * A pairing violation.
	 *
	 * @param kind the kind of the violation, never null
	 * @param toolCallId the identifier of the affected tool call, never null
	 */
	public record Violation(Kind kind, String toolCallId) {

		/**
		 * Creates a pairing violation.
		 * @param kind the kind of the violation, must not be null
		 * @param toolCallId the identifier of the affected tool call, must not be null
		 */
		public Violation {
			Objects.requireNonNull(kind, "kind must not be null");
			Objects.requireNonNull(toolCallId, "toolCallId must not be null");
		}
	}

	/**
	 * Validates the pairing of the tool calls and the tool results of a
	 * conversation.
	 * @param messages the conversation, must not be null
	 * @return the violation, or empty when the conversation is paired
	 */
	public static Optional<Violation> validate(List<ChatMessage> messages) {
		Objects.requireNonNull(messages, "messages must not be null");
		Map<String, Integer> callCounts = new HashMap<>();
		Set<String> calls = new LinkedHashSet<>();
		for (ChatMessage message : messages) {
			for (ToolCall call : message.toolCalls()) {
				if (call.id() == null) {
					continue;
				}
				callCounts.merge(call.id(), 1, Integer::sum);
				calls.add(call.id());
			}
		}
		for (Map.Entry<String, Integer> call : callCounts.entrySet()) {
			if (call.getValue() > 1) {
				return Optional.of(new Violation(Kind.DUPLICATE_TOOL_CALL, call.getKey()));
			}
		}
		Set<String> results = new LinkedHashSet<>();
		for (ChatMessage message : messages) {
			if (message.role() == ChatRole.TOOL && message.toolCallId() != null) {
				results.add(message.toolCallId());
			}
		}
		for (String result : results) {
			if (!calls.contains(result)) {
				return Optional.of(new Violation(Kind.UNKNOWN_TOOL_RESULT, result));
			}
		}
		for (String call : calls) {
			if (!results.contains(call)) {
				return Optional.of(new Violation(Kind.MISSING_TOOL_RESULT, call));
			}
		}
		return Optional.empty();
	}

	/**
	 * Thrown when a request which is about to be dispatched has a broken pairing.
	 */
	public static final class PairingException extends RuntimeException {

		private final Violation violation;

		/**
		 * Creates the exception.
		 * @param violation the violation, must not be null
		 */
		public PairingException(Violation violation) {
			super("tool call pairing is broken: " + violation.kind() + " (" + violation.toolCallId() + ")");
			this.violation = violation;
		}

		/**
		 * The classified violation.
		 * @return the violation, never null
		 */
		public Violation violation() {
			return violation;
		}
	}

}
