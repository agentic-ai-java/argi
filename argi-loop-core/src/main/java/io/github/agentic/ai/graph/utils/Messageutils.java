/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.utils;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.LegacyChatRecordSupport;
import java.util.Locale;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Messageutils {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Generic method to convert various types of messages to ChatMessage list Supported
	 * types: 1. String - converted to ChatMessage 2. Map (e.g. {"role": "user", "text":
	 * "test"}) - create corresponding ChatMessage based on role 3. Neutral ChatMessage -
	 * use directly 4. List - recursively process each element in the list
	 */
	public static List<ChatMessage> convertToMessages(Object value) {
		List<ChatMessage> result = new ArrayList<>();

		if (value == null) {
			return result;
		}

		if (value instanceof List<?>) {
			List<?> list = (List<?>) value;
			if (list.isEmpty()) {
				return result;
			}

			for (Object item : list) {
				result.addAll(convertToMessages(item));
			}
		}
		else if (value instanceof ChatMessage) {
			// Preserve an existing neutral message without rebuilding its content.
			result.add((ChatMessage) value);
		}
		else if (value instanceof String) {
			// If it's a string, convert to ChatMessage
			String text = (String) value;
			if (StringUtils.hasLength(text)) {
				result.add(ChatMessage.user(text));
			}
		}
		else if (value instanceof Map) {
			// If it's a Map, attempt to parse as ChatMessage
			Map<?, ?> map = (Map<?, ?>) value;
			ChatMessage message = convertMapToMessage(map);
			if (message != null) {
				result.add(message);
			}
		}
		else {
			// For other types, attempt conversion through JSON
			// serialization/deserialization
			JsonNode jsonNode;
			try {
				String json = objectMapper.writeValueAsString(value);
				jsonNode = objectMapper.readTree(json);
			}
			catch (com.fasterxml.jackson.core.JsonProcessingException e) {
				// If conversion fails, treat it as a string
				String text = value.toString();
				if (StringUtils.hasLength(text)) {
					result.add(ChatMessage.user(text));
				}
				return result;
			}
			// Invalid roles and legacy markers must not silently become user text.
			if (jsonNode.isObject()) {
				ChatMessage message = convertJsonNodeToMessage(jsonNode);
				if (message != null) {
					result.add(message);
				}
			}
		}

		return result;
	}

	/**
	 * Convert Map to ChatMessage
	 */
	public static ChatMessage convertMapToMessage(Map<?, ?> map) {
		if (map == null || map.isEmpty()) {
			return null;
		}

		for (String marker : List.of("@class", "@type", "@typeHint")) {
			Object type = map.get(marker);
			if (type instanceof String name && LegacyChatRecordSupport.isLegacyType(name)) {
				throw new IllegalArgumentException(
						"Read legacy messages with ArgiJacksonStateSerializer before conversion");
			}
		}
		ChatRole role = ChatRole
			.valueOf(map.get("role") == null ? "USER" : map.get("role").toString().toUpperCase(Locale.ROOT));
		Object content = map.get("content") != null ? map.get("content") : map.get("text");
		if (content == null && role != ChatRole.TOOL && !map.containsKey("toolCalls")) {
			return null;
		}
		var builder = ChatMessage.builder(role);
		if (content instanceof String text) {
			builder.text(text);
		}
		else if (content instanceof List<?> parts) {
			builder.content(parts.stream().map(part -> objectMapper.convertValue(part, ContentPart.class)).toList());
		}
		else if (content != null) {
			throw new IllegalArgumentException("Message content must be text or a list of content parts");
		}
		if (map.get("toolCalls") != null) {
			builder.toolCalls(List.of(objectMapper.convertValue(map.get("toolCalls"), ToolCall[].class)));
		}
		if (map.get("metadata") != null) {
			if (!(map.get("metadata") instanceof Map<?, ?> properties)
					|| properties.keySet().stream().anyMatch(key -> !(key instanceof String))) {
				throw new IllegalArgumentException("Message metadata must be an object with string keys");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");
			builder.metadata(metadata);
		}
		if (role == ChatRole.TOOL) {
			builder.toolResult((String) map.get("toolCallId"), (String) map.get("toolName"));
		}
		return builder.build();
	}

	/**
	 * Convert JsonNode to ChatMessage
	 */
	public static ChatMessage convertJsonNodeToMessage(JsonNode jsonNode) {
		if (jsonNode == null || !jsonNode.isObject()) {
			return null;
		}

		return convertMapToMessage(objectMapper.convertValue(jsonNode, Map.class));
	}

}
