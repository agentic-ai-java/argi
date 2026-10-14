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

import java.util.Map;
import java.util.Objects;

/** A normalized event emitted by a streaming chat client. */
public record ChatEvent(Type type, String text, ToolCall toolCall, ChatResponse response, Usage usage,
		Map<String, Object> metadata) {

	public enum Type {

		TEXT_DELTA, TOOL_CALL, RESPONSE, USAGE,

	}

	public ChatEvent {
		Objects.requireNonNull(type, "type must not be null");
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	public static ChatEvent textDelta(String text) {
		return new ChatEvent(Type.TEXT_DELTA, Objects.requireNonNull(text), null, null, null, Map.of());
	}

	public static ChatEvent toolCall(ToolCall toolCall) {
		return new ChatEvent(Type.TOOL_CALL, null, Objects.requireNonNull(toolCall), null, null, Map.of());
	}

	public static ChatEvent response(ChatResponse response) {
		return new ChatEvent(Type.RESPONSE, null, null, Objects.requireNonNull(response), null, Map.of());
	}

	public static ChatEvent usage(Usage usage) {
		return new ChatEvent(Type.USAGE, null, null, null, Objects.requireNonNull(usage), Map.of());
	}
}
