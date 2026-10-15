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
package io.github.agentic.ai.api.tool;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.MessageProvenance;
import io.github.agentic.ai.api.chat.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete tool output; an error result is distinct from a failed invocation stage. */
public record ToolResult(List<ContentPart> content, boolean error, Map<String, Object> metadata) {

	public ToolResult {
		content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	public static ToolResult text(String text) {
		return new ToolResult(List.of(ContentPart.text(text)), false, Map.of());
	}

	public static ToolResult error(String text) {
		return new ToolResult(List.of(ContentPart.text(text)), true, Map.of());
	}

	/**
	 * Creates a model-visible result tied to the original invocation identity.
	 * Tool payloads are tagged as untrusted external data so later summarization cannot
	 * elevate them to application instructions.
	 */
	public ChatMessage toMessage(ToolCall call) {
		Objects.requireNonNull(call, "call must not be null");
		java.util.HashMap<String, Object> properties = new java.util.HashMap<>(metadata);
		properties.put("tool.error", error);
		return MessageProvenance.ensureToolOutput(
				new ChatMessage(ChatRole.TOOL, content, List.of(), call.id(), call.name(), properties));
	}
}
