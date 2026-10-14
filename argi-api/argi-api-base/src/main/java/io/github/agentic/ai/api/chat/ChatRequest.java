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

/** Input to a framework-neutral chat client. Tools are schemas, never executors. */
public record ChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools, GenerationOptions options,
		Map<String, Object> attributes) {

	public ChatRequest {
		messages = messages == null ? List.of() : List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
		options = options == null ? GenerationOptions.defaults() : options;
		attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
		if (messages.isEmpty()) {
			throw new IllegalArgumentException("messages must not be empty");
		}
		if (tools.stream().map(ToolDefinition::name).distinct().count() != tools.size()) {
			throw new IllegalArgumentException("Tool names must be unique");
		}
		if (options.toolChoice().mode() == ToolChoice.Mode.REQUIRED && tools.isEmpty()) {
			throw new IllegalArgumentException("REQUIRED tool choice requires at least one tool");
		}
		if (options.toolChoice().mode() == ToolChoice.Mode.NAMED) {
			String name = options.toolChoice().toolName();
			if (tools.stream().noneMatch(tool -> tool.name().equals(name))) {
				throw new IllegalArgumentException("Named tool is not present in the request: " + name);
			}
		}
	}

	public static ChatRequest of(List<ChatMessage> messages) {
		return new ChatRequest(messages, List.of(), GenerationOptions.defaults(), Map.of());
	}
}
