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
package io.github.agentic.ai.api.chat;

import java.util.List;
import java.util.Map;

/** Normalized result returned by a chat client. */
public record ChatResponse(String id, String model, List<ChatMessage> messages, List<ToolCall> toolCalls,
		FinishReason finishReason, Usage usage, Map<String, Object> metadata) {

	public ChatResponse {
		messages = messages == null ? List.of() : List.copyOf(messages);
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		usage = usage == null ? Usage.unknown() : usage;
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	public String text() {
		return messages.stream().map(ChatMessage::text).reduce("", String::concat);
	}
}
