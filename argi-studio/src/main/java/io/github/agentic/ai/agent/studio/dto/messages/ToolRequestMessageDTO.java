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
package io.github.agentic.ai.agent.studio.dto.messages;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object for ChatMessage. Provides serialization-friendly representation
 * with default constructor and getters/setters.
 */
public class ToolRequestMessageDTO implements MessageDTO {

	@JsonProperty("messageType")
	private String messageType = "tool-request";

	@JsonProperty("content")
	private String content;

	@JsonProperty("metadata")
	private Map<String, Object> metadata;

	@JsonProperty("toolCalls")
	private List<ToolCallDTO> toolCalls;

	/**
	 * Default constructor for deserialization.
	 */
	public ToolRequestMessageDTO() {
		this.metadata = new HashMap<>();
		this.toolCalls = new ArrayList<>();
	}

	/**
	 * Constructor with content.
	 */
	public ToolRequestMessageDTO(String content) {
		this();
		this.content = content;
	}

	/**
	 * Constructor from provider-neutral ChatMessage.
	 */
	public ToolRequestMessageDTO(ChatMessage message) {
		this();
		this.content = message.text();
		this.metadata = new HashMap<>(message.metadata());

		// Convert tool calls if present
		if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
			this.toolCalls = new ArrayList<>();
			for (io.github.agentic.ai.api.chat.ToolCall toolCall : message.toolCalls()) {
				this.toolCalls.add(new ToolCallDTO(toolCall));
			}
		}
	}

	/**
	 * Convert to provider-neutral ChatMessage.
	 */
	public ChatMessage toAssistantMessage() {
		List<io.github.agentic.ai.api.chat.ToolCall> springToolCalls = new ArrayList<>();
		if (this.toolCalls != null) {
			for (ToolCallDTO dto : this.toolCalls) {
				springToolCalls.add(dto.toToolCall());
			}
		}

		return ChatMessage.builder(ChatRole.ASSISTANT)
			.text(this.content)
			.metadata(this.metadata)
			.toolCalls(springToolCalls)
			.build();
	}

	// Getters and Setters

	public String getMessageType() {
		return messageType;
	}

	public void setMessageType(String messageType) {
		this.messageType = messageType;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public Map<String, Object> getMetadata() {
		return metadata;
	}

	public void setMetadata(Map<String, Object> metadata) {
		this.metadata = metadata;
	}

	public List<ToolCallDTO> getToolCalls() {
		return toolCalls;
	}

	public void setToolCalls(List<ToolCallDTO> toolCalls) {
		this.toolCalls = toolCalls;
	}

	/**
	 * DTO for ToolCall within ChatMessage.
	 */
	public static class ToolCallDTO {

		@JsonProperty("id")
		private String id;

		@JsonProperty("type")
		private String type;

		@JsonProperty("name")
		private String name;

		@JsonProperty("arguments")
		private String arguments;

		public ToolCallDTO() {
		}

		public ToolCallDTO(io.github.agentic.ai.api.chat.ToolCall toolCall) {
			this.id = toolCall.id();
			this.type = "function";
			this.name = toolCall.name();
			this.arguments = toolCall.arguments();
		}

		public io.github.agentic.ai.api.chat.ToolCall toToolCall() {
			return new io.github.agentic.ai.api.chat.ToolCall(this.id, this.name, this.arguments);
		}

		// Getters and Setters

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getArguments() {
			return arguments;
		}

		public void setArguments(String arguments) {
			this.arguments = arguments;
		}

	}

}
