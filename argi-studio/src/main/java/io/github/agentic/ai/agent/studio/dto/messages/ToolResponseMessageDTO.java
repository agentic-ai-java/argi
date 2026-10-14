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
 * Data Transfer Object for ToolResponseMessage. Provides serialization-friendly
 * representation with default constructor and getters/setters.
 */
public class ToolResponseMessageDTO implements MessageDTO {

	@JsonProperty("messageType")
	private String messageType = "tool";

	@JsonProperty("content")
	private String content;

	@JsonProperty("metadata")
	private Map<String, Object> metadata;

	@JsonProperty("responses")
	private List<ToolResponseDTO> responses;

	/**
	 * Default constructor for deserialization.
	 */
	public ToolResponseMessageDTO() {
		this.metadata = new HashMap<>();
		this.responses = new ArrayList<>();
	}

	/**
	 * Constructor with content.
	 */
	public ToolResponseMessageDTO(String content) {
		this();
		this.content = content;
	}

	/**
	 * Constructor from provider-neutral tool message.
	 */
	public ToolResponseMessageDTO(ChatMessage message) {
		this(message.text());
		if (message.role() != ChatRole.TOOL) {
			throw new IllegalArgumentException("Expected a tool message");
		}
		this.metadata = new HashMap<>(message.metadata());
		this.responses.add(new ToolResponseDTO(message));
	}

	/**
	 * Convert to provider-neutral tool message.
	 */
	public ChatMessage toToolResponseMessage() {
		if (responses == null || responses.size() != 1) {
			throw new IllegalArgumentException("A tool message must contain exactly one result");
		}
		ToolResponseDTO result = responses.get(0);
		return ChatMessage.tool(result.id, result.name, result.responseData).withMetadata(metadata);
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

	public List<ToolResponseDTO> getResponses() {
		return responses;
	}

	public void setResponses(List<ToolResponseDTO> responses) {
		this.responses = responses;
	}

	/**
	 * DTO for ToolResponse within ToolResponseMessage.
	 */
	public static class ToolResponseDTO {

		@JsonProperty("id")
		private String id;

		@JsonProperty("name")
		private String name;

		@JsonProperty("responseData")
		private String responseData;

		public ToolResponseDTO() {
		}

		public ToolResponseDTO(ChatMessage response) {
			this.id = response.toolCallId();
			this.name = response.toolName();
			this.responseData = response.text();
		}

		// Getters and Setters

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getResponseData() {
			return responseData;
		}

		public void setResponseData(String responseData) {
			this.responseData = responseData;
		}

	}

}
