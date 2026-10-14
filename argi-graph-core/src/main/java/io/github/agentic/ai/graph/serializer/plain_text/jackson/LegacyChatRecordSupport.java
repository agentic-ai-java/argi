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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads historical JSON wire identifiers, not SDK types. No SDK class is loaded. Binary
 * payloads and templated instructions require export using the old runtime.
 */
public final class LegacyChatRecordSupport {

	// These wire literals are the only permitted SDK strings in neutral bytecode.
	private static final String SDK_PREFIX = "org.springframework.ai.";

	private static final Map<String, String> CLASS_TYPES = Map.of("org.springframework.ai.chat.messages.SystemMessage",
			"SYSTEM", "org.springframework.ai.chat.messages.UserMessage", "USER",
			"org.springframework.ai.chat.messages.AssistantMessage", "ASSISTANT",
			"org.springframework.ai.chat.messages.ToolResponseMessage", "TOOL",
			"org.springframework.ai.deepseek.DeepSeekAssistantMessage", "DEEPSEEK_ASSISTANT",
			"org.springframework.ai.zhipuai.ZhiPuAiAssistantMessage", "ZHI_PU_AI_ASSISTANT",
			"io.github.agentic.ai.graph.serializer.AgentInstructionMessage", "TEMPLATED_USER");

	private static final Set<String> TYPES = Set.of("SYSTEM", "USER", "ASSISTANT", "TOOL", "DEEPSEEK_ASSISTANT",
			"ZHI_PU_AI_ASSISTANT", "TEMPLATED_USER");

	private LegacyChatRecordSupport() {
	}

	/**
	 * Identifies legacy class/type markers before generic map fallback can erase roles.
	 */
	public static boolean isLegacyType(String type) {
		return type != null && (TYPES.contains(type) || CLASS_TYPES.containsKey(type) || type.startsWith(SDK_PREFIX));
	}

	static boolean isToolGroup(JsonNode node) {
		return node.isObject() && List.of("@type", "@class", "@typeHint").stream().anyMatch(key -> {
			String marker = node.path(key).asText();
			return "TOOL".equals(CLASS_TYPES.getOrDefault(marker, marker));
		});
	}

	static Object read(String marker, JsonNode node, ObjectMapper mapper, TypeMapper types) throws IOException {
		String type = CLASS_TYPES.getOrDefault(marker, marker);
		try {
			if (!TYPES.contains(type) || "TEMPLATED_USER".equals(type)) {
				throw new IOException("Unsupported legacy chat checkpoint type: " + marker
						+ ". Export resolved messages with the previous runtime before upgrading.");
			}
			if (node.hasNonNull("media") && !node.get("media").isEmpty()) {
				throw new IOException("Legacy checkpoint media requires an explicit offline migration");
			}
			Map<String, Object> metadata = metadata(node, mapper, types);
			if ("TOOL".equals(type)) {
				JsonNode responses = node.get("responses");
				if (responses == null || !responses.isArray() || responses.isEmpty()) {
					throw new IOException("Legacy tool checkpoint must contain nonempty responses");
				}
				List<ChatMessage> results = new ArrayList<>();
				for (JsonNode response : responses) {
					results.add(ChatMessage
						.tool(required(response, "id"), required(response, "name"), required(response, "responseData"))
						.withMetadata(metadata));
				}
				return results;
			}
			ChatRole role = switch (type) {
				case "SYSTEM" -> ChatRole.SYSTEM;
				case "USER" -> ChatRole.USER;
				default -> ChatRole.ASSISTANT;
			};
			var message = ChatMessage.builder(role).text(text(node, "text")).metadata(metadata);
			JsonNode calls = node.get("toolCalls");
			if (calls != null && !calls.isNull()) {
				if (!calls.isArray()) {
					throw new IOException("Legacy toolCalls must be an array");
				}
				List<ToolCall> tools = new ArrayList<>();
				for (JsonNode call : calls) {
					String kind = text(call, "type");
					if (kind != null && !"function".equals(kind)) {
						throw new IOException("Unsupported legacy tool call type: " + kind);
					}
					tools.add(new ToolCall(required(call, "id"), required(call, "name"), required(call, "arguments")));
				}
				message.toolCalls(tools);
			}
			return message.build();
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new IOException("Invalid legacy chat checkpoint: " + marker, ex);
		}
	}

	private static Map<String, Object> metadata(JsonNode node, ObjectMapper mapper, TypeMapper types)
			throws IOException {
		Map<String, Object> result = new LinkedHashMap<>();
		Object value = JacksonDeserializer.valueFromNode(node.get("metadata"), mapper, types);
		if (value != null) {
			if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
				throw new IOException("Legacy message metadata must be an object with string keys");
			}
			for (var entry : map.entrySet()) {
				result.put((String) entry.getKey(), entry.getValue());
			}
		}
		Object nativeReasoning = result.remove("reasoningContent");
		String reasoning = text(node, "reasoningContent");
		if (reasoning != null || nativeReasoning != null) {
			result.put(ChatMetadataKeys.REASONING, reasoning == null ? nativeReasoning : reasoning);
		}
		return result;
	}

	private static String required(JsonNode node, String field) throws IOException {
		String value = text(node, field);
		if (value == null) {
			throw new IOException("Missing legacy chat field: " + field);
		}
		return value;
	}

	private static String text(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IOException("Legacy chat field must be text: " + field);
		}
		return value.textValue();
	}

}
