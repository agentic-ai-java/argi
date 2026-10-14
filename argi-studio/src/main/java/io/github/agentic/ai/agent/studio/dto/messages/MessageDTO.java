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

import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.serializer.AgentInstructionMessage;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for message DTOs with polymorphic serialization support. Uses Jackson
 * annotations for type discrimination during JSON serialization/deserialization.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "messageType")
@JsonSubTypes({ @JsonSubTypes.Type(value = AssistantMessageDTO.class, name = "assistant"),
		@JsonSubTypes.Type(value = UserMessageDTO.class, name = "user"),
		@JsonSubTypes.Type(value = ToolResponseMessageDTO.class, name = "tool"),
		@JsonSubTypes.Type(value = ToolRequestMessageDTO.class, name = "tool-request"),
		@JsonSubTypes.Type(value = ToolRequestConfirmMessageDTO.class, name = "tool-confirm") })
public interface MessageDTO {

	/**
	 * Get the message type identifier.
	 */
	String getMessageType();

	/**
	 * Get the content of the message.
	 */
	String getContent();

	/**
	 * Factory class for converting between provider-neutral ChatMessage and DTO types.
	 */
	class MessageDTOFactory {

		/** Converts neutral output without constructing provider-native messages. */
		public static MessageDTO fromChatMessage(ChatMessage message) {
			if (message == null || AgentInstructionMessage.isTemplate(message)) {
				return null;
			}
			return switch (message.role()) {
				case SYSTEM -> null;
				case USER -> new UserMessageDTO(message);
				case ASSISTANT -> {
					if (message.toolCalls().isEmpty()) {
						AssistantMessageDTO dto = new AssistantMessageDTO(message.text());
						dto.setMetadata(message.metadata());
						yield dto;
					}
					ToolRequestMessageDTO dto = new ToolRequestMessageDTO(message.text());
					dto.setMetadata(message.metadata());
					dto.setToolCalls(message.toolCalls().stream().map(call -> {
						ToolRequestMessageDTO.ToolCallDTO tool = new ToolRequestMessageDTO.ToolCallDTO();
						tool.setId(call.id());
						tool.setName(call.name());
						tool.setArguments(call.arguments());
						tool.setType("function");
						return tool;
					}).toList());
					yield dto;
				}
				case TOOL -> {
					ToolResponseMessageDTO dto = new ToolResponseMessageDTO(message.text());
					dto.setMetadata(message.metadata());
					ToolResponseMessageDTO.ToolResponseDTO result = new ToolResponseMessageDTO.ToolResponseDTO();
					result.setId(message.toolCallId());
					result.setName(message.toolName());
					result.setResponseData(message.text());
					dto.setResponses(List.of(result));
					yield dto;
				}
			};
		}

		/**
		 * Convert provider-neutral ChatMessage to appropriate DTO type.
		 */
		public static MessageDTO fromMessage(ChatMessage message) {
			return fromChatMessage(message);
		}

		/**
		 * Convert InterruptionMetadata to ToolRequestMessageDTO. This is a specialized
		 * method for handling interruption metadata from agent execution.
		 */
		public static ToolRequestConfirmMessageDTO fromInterruptionMetadata(InterruptionMetadata interruptionMetadata) {
			if (interruptionMetadata == null) {
				return null;
			}
			return new ToolRequestConfirmMessageDTO(interruptionMetadata);
		}

		/**
		 * Convert DTO to provider-neutral ChatMessage.
		 */
		public static ChatMessage toMessage(MessageDTO dto) {
			if (dto == null) {
				return null;
			}

			if (dto instanceof ToolRequestMessageDTO) {
				return ((ToolRequestMessageDTO) dto).toAssistantMessage();
			}
			else if (dto instanceof AssistantMessageDTO) {
				return ((AssistantMessageDTO) dto).toAssistantMessage();
			}
			else if (dto instanceof UserMessageDTO) {
				return ((UserMessageDTO) dto).toUserMessage();
			}
			else if (dto instanceof ToolResponseMessageDTO) {
				return ((ToolResponseMessageDTO) dto).toToolResponseMessage();
			}
			else {
				throw new IllegalArgumentException("Unsupported DTO type: " + dto.getClass().getName());
			}
		}

	}

}
