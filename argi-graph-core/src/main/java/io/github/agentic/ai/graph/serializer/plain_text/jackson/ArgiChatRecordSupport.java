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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restores chat records without falling back to untyped checkpoint payloads. */
final class ArgiChatRecordSupport {

	private static final Set<String> TYPES = Set.of("ARGI_CHAT_MESSAGE", "ARGI_CHAT_RESPONSE", "ARGI_CONTENT_PART",
			"ARGI_TOOL_CALL", "ARGI_USAGE", "ARGI_CHAT_EVENT", "ARGI_DURATION");

	private ArgiChatRecordSupport() {
	}

	static boolean supports(String type) {
		return TYPES.contains(type);
	}

	static Object read(String type, JsonNode node, ObjectMapper mapper, TypeMapper types) throws IOException {
		try {
			return switch (type) {
				case "ARGI_DURATION" -> java.time.Duration.parse(text(node, "value"));
				case "ARGI_CHAT_EVENT" -> new ChatEvent(ChatEvent.Type.valueOf(text(node, "type")), text(node, "text"),
						ToolCall.class.cast(value(node, "toolCall", mapper, types)),
						ChatResponse.class.cast(value(node, "response", mapper, types)),
						Usage.class.cast(value(node, "usage", mapper, types)), metadata(node, mapper, types));
				case "ARGI_CHAT_MESSAGE" -> new ChatMessage(ChatRole.valueOf(text(node, "role")),
						list(node, "content", ContentPart.class, mapper, types),
						list(node, "toolCalls", ToolCall.class, mapper, types), text(node, "toolCallId"),
						text(node, "toolName"), metadata(node, mapper, types));
				case "ARGI_CHAT_RESPONSE" -> new ChatResponse(text(node, "id"), text(node, "model"),
						list(node, "messages", ChatMessage.class, mapper, types),
						list(node, "toolCalls", ToolCall.class, mapper, types),
						text(node, "finishReason") == null ? null : FinishReason.valueOf(text(node, "finishReason")),
						Usage.class.cast(value(node, "usage", mapper, types)), metadata(node, mapper, types));
				case "ARGI_CONTENT_PART" -> new ContentPart(ContentPart.Type.valueOf(text(node, "type")),
						text(node, "text"), text(node, "mimeType"),
						text(node, "data") == null ? null : Base64.getDecoder().decode(text(node, "data")),
						metadata(node, mapper, types));
				case "ARGI_TOOL_CALL" -> new ToolCall(text(node, "id"), text(node, "name"), text(node, "arguments"));
				case "ARGI_USAGE" -> new Usage(count(node, "inputTokens", mapper, types),
						count(node, "outputTokens", mapper, types), count(node, "totalTokens", mapper, types));
				default -> throw new IOException("Unknown ARGI chat record: " + type);
			};
		}
		catch (IllegalArgumentException | NullPointerException | ClassCastException ex) {
			throw new IOException("Invalid ARGI chat record: " + type, ex);
		}
	}

	private static String text(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IOException("Chat record field must be text: " + field);
		}
		return value.textValue();
	}

	private static Object value(JsonNode node, String field, ObjectMapper mapper, TypeMapper types) throws IOException {
		return JacksonDeserializer.valueFromNode(node.get(field), mapper, types);
	}

	private static Long count(JsonNode node, String field, ObjectMapper mapper, TypeMapper types) throws IOException {
		Object value = value(node, field, mapper, types);
		if (value == null) {
			return null;
		}
		if (value instanceof Integer || value instanceof Long) {
			return ((Number) value).longValue();
		}
		throw new IOException("Chat token count must be a 64-bit integer: " + field);
	}

	private static <T> List<T> list(JsonNode node, String field, Class<T> elementType, ObjectMapper mapper,
			TypeMapper types) throws IOException {
		Object value = value(node, field, mapper, types);
		if (!(value instanceof List<?> items) || items.stream().anyMatch(item -> !elementType.isInstance(item))) {
			throw new IOException("Invalid chat record list: " + field);
		}
		return items.stream().map(elementType::cast).toList();
	}

	private static Map<String, Object> metadata(JsonNode node, ObjectMapper mapper, TypeMapper types)
			throws IOException {
		Object value = value(node, "metadata", mapper, types);
		if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
			throw new IOException("Chat metadata must be an object with string keys");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) map;
		return result;
	}

}
