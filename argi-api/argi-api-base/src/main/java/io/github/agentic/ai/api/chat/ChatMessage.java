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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** A provider-neutral chat message. */
public record ChatMessage(ChatRole role, List<ContentPart> content, List<ToolCall> toolCalls, String toolCallId,
		String toolName, Map<String, Object> metadata) {

	public ChatMessage {
		Objects.requireNonNull(role, "role must not be null");
		content = content == null ? List.of() : List.copyOf(content);
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		if (role == ChatRole.TOOL) {
			if (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
				throw new IllegalArgumentException("Tool results require a toolCallId and toolName");
			}
		}
		else if (toolCallId != null || toolName != null) {
			throw new IllegalArgumentException("Only tool results may contain toolCallId and toolName");
		}
		if (role != ChatRole.ASSISTANT && !toolCalls.isEmpty()) {
			throw new IllegalArgumentException("Only assistant messages may contain tool calls");
		}
	}

	public ChatMessage(ChatRole role, List<ContentPart> content, Map<String, Object> metadata) {
		this(role, content, List.of(), null, null, metadata);
	}

	public static ChatMessage text(ChatRole role, String text) {
		return new ChatMessage(role, List.of(ContentPart.text(text)), List.of(), null, null, Map.of());
	}

	public static ChatMessage system(String text) {
		return text(ChatRole.SYSTEM, text);
	}

	public static ChatMessage user(String text) {
		return text(ChatRole.USER, text);
	}

	public static ChatMessage assistant(String text) {
		return text(ChatRole.ASSISTANT, text);
	}

	public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
		return new ChatMessage(ChatRole.ASSISTANT, text == null ? List.of() : List.of(ContentPart.text(text)),
				toolCalls, null, null, Map.of());
	}

	public static ChatMessage tool(String toolCallId, String toolName, String text) {
		return new ChatMessage(ChatRole.TOOL, List.of(ContentPart.text(text)), List.of(), toolCallId, toolName,
				Map.of());
	}

	/**
	 * Appends a text part without losing existing content, tool identifiers or metadata.
	 */
	public ChatMessage appendText(String text) {
		List<ContentPart> parts = new ArrayList<>(content);
		parts.add(ContentPart.text(text));
		return new ChatMessage(role, parts, toolCalls, toolCallId, toolName, metadata);
	}

	/** Replaces text while retaining non-text content and invocation identity. */
	public ChatMessage withText(String text) {
		List<ContentPart> parts = new ArrayList<>();
		if (text != null) {
			parts.add(ContentPart.text(text));
		}
		content.stream()
			.filter(part -> part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON)
			.forEach(parts::add);
		return new ChatMessage(role, parts, toolCalls, toolCallId, toolName, metadata);
	}

	public ChatMessage withMetadata(Map<String, Object> properties) {
		return new ChatMessage(role, content, toolCalls, toolCallId, toolName, properties);
	}

	public ChatMessage withToolCalls(List<ToolCall> calls) {
		return new ChatMessage(role, content, calls, toolCallId, toolName, metadata);
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}

	public static Builder builder(ChatRole role) {
		return new Builder(role);
	}

	public Builder mutate() {
		return new Builder(role).content(content)
			.toolCalls(toolCalls)
			.metadata(metadata)
			.toolResult(toolCallId, toolName);
	}

	/** Builder for immutable messages, including multimodal content and tool results. */
	public static final class Builder {

		private final ChatRole role;

		private List<ContentPart> content = List.of();

		private List<ToolCall> toolCalls = List.of();

		private Map<String, Object> metadata = Map.of();

		private String toolCallId;

		private String toolName;

		private Builder(ChatRole role) {
			this.role = Objects.requireNonNull(role, "role must not be null");
		}

		public Builder text(String text) {
			List<ContentPart> parts = new ArrayList<>();
			if (text != null) {
				parts.add(ContentPart.text(text));
			}
			content.stream()
				.filter(part -> part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON)
				.forEach(parts::add);
			this.content = parts;
			return this;
		}

		public Builder content(List<ContentPart> parts) {
			this.content = parts == null ? List.of() : List.copyOf(parts);
			return this;
		}

		public Builder toolCalls(List<ToolCall> calls) {
			this.toolCalls = calls == null ? List.of() : List.copyOf(calls);
			return this;
		}

		public Builder metadata(Map<String, Object> properties) {
			this.metadata = properties == null ? Map.of() : Map.copyOf(properties);
			return this;
		}

		public Builder toolResult(String id, String name) {
			this.toolCallId = id;
			this.toolName = name;
			return this;
		}

		public ChatMessage build() {
			return new ChatMessage(role, content, toolCalls, toolCallId, toolName, metadata);
		}

	}

	public String text() {
		return content.stream()
			.filter(part -> part.type() == ContentPart.Type.TEXT || part.type() == ContentPart.Type.JSON)
			.map(ContentPart::text)
			.filter(Objects::nonNull)
			.collect(Collectors.joining());
	}
}
