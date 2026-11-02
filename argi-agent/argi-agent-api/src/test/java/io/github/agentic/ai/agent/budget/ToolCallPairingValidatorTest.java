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
import io.github.agentic.ai.api.chat.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Position-aware pairing validation: a result must follow the assistant message
 * which requested the call, and neither calls nor results may repeat (issue #137
 * review).
 */
class ToolCallPairingValidatorTest {

	@Test
	void aWellPairedConversationPasses() {
		List<ChatMessage> messages = List.of(
				ChatMessage.assistant("use", List.of(new ToolCall("call-1", "lookup", "{}"))),
				ChatMessage.tool("call-1", "lookup", "lookup result"));

		assertThat(ToolCallPairingValidator.validate(messages)).isEmpty();
	}

	@Test
	void aResultBeforeItsCallIsRejected() {
		List<ChatMessage> messages = List.of(
				ChatMessage.tool("call-1", "lookup", "lookup result"),
				ChatMessage.assistant("use", List.of(new ToolCall("call-1", "lookup", "{}"))));

		assertThat(ToolCallPairingValidator.validate(messages))
				.contains(new ToolCallPairingValidator.Violation(
						ToolCallPairingValidator.Kind.RESULT_BEFORE_CALL, "call-1"));
	}

	@Test
	void aDuplicatedResultIsRejected() {
		List<ChatMessage> messages = List.of(
				ChatMessage.assistant("use", List.of(new ToolCall("call-1", "lookup", "{}"))),
				ChatMessage.tool("call-1", "lookup", "lookup result"),
				ChatMessage.tool("call-1", "lookup", "lookup result"));

		assertThat(ToolCallPairingValidator.validate(messages))
				.contains(new ToolCallPairingValidator.Violation(
						ToolCallPairingValidator.Kind.DUPLICATE_TOOL_RESULT, "call-1"));
	}

	@Test
	void aMissingResultIsStillRejected() {
		List<ChatMessage> messages = List.of(
				ChatMessage.assistant("use", List.of(new ToolCall("call-1", "lookup", "{}"))));

		assertThat(ToolCallPairingValidator.validate(messages))
				.contains(new ToolCallPairingValidator.Violation(
						ToolCallPairingValidator.Kind.MISSING_TOOL_RESULT, "call-1"));
	}

	@Test
	void anOrphanedResultIsStillRejected() {
		List<ChatMessage> messages = List.of(
				ChatMessage.tool("call-1", "lookup", "lookup result"));

		assertThat(ToolCallPairingValidator.validate(messages))
				.contains(new ToolCallPairingValidator.Violation(
						ToolCallPairingValidator.Kind.UNKNOWN_TOOL_RESULT, "call-1"));
	}

}
