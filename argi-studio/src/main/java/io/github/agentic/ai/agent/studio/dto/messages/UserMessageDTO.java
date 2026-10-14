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

import io.github.agentic.ai.api.chat.ContentPart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object for ChatMessage. Provides serialization-friendly representation
 * with default constructor and getters/setters.
 */
public class UserMessageDTO implements MessageDTO {

	@JsonProperty("messageType")
	private String messageType = "user";

	@JsonProperty("content")
	private String content;

	@JsonProperty("metadata")
	private Map<String, Object> metadata;

	@JsonProperty("media")
	private List<MediaDTO> media;

	/**
	 * Default constructor for deserialization.
	 */
	public UserMessageDTO() {
		this.metadata = new HashMap<>();
		this.media = new ArrayList<>();
	}

	/**
	 * Constructor with content.
	 */
	public UserMessageDTO(String content) {
		this();
		this.content = content;
	}

	/**
	 * Constructor from provider-neutral ChatMessage.
	 */
	public UserMessageDTO(ChatMessage message) {
		this();
		this.content = message.text();
		this.metadata = new HashMap<>(message.metadata());

		for (ContentPart part : message.content()) {
			if (part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON) {
				MediaDTO item = new MediaDTO();
				item.setMimeType(part.mimeType());
				item.setData(part.data());
				this.media.add(item);
			}
		}
	}

	/**
	 * Convert to provider-neutral ChatMessage.
	 */
	public ChatMessage toUserMessage() {
		return toChatMessage();
	}

	/** Converts Studio input into a neutral message; media data is bytes or base64. */
	public ChatMessage toChatMessage() {
		List<ContentPart> parts = new ArrayList<>();
		if (content != null) {
			parts.add(ContentPart.text(content));
		}
		if (media != null) {
			for (MediaDTO item : media) {
				if (item == null || item.mimeType == null || item.mimeType.isBlank()) {
					throw new IllegalArgumentException("Media requires a MIME type");
				}
				byte[] bytes;
				if (item.data instanceof byte[] binary) {
					bytes = binary;
				}
				else if (item.data instanceof String encoded) {
					bytes = java.util.Base64.getDecoder().decode(encoded);
				}
				else {
					throw new IllegalArgumentException("Media data must be bytes or base64");
				}
				ContentPart.Type type = item.mimeType.startsWith("image/") ? ContentPart.Type.IMAGE
						: item.mimeType.startsWith("audio/") ? ContentPart.Type.AUDIO : ContentPart.Type.OTHER;
				parts.add(ContentPart.binary(type, item.mimeType, bytes));
			}
		}
		return new ChatMessage(ChatRole.USER, parts, metadata);
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

	public List<MediaDTO> getMedia() {
		return media;
	}

	public void setMedia(List<MediaDTO> media) {
		this.media = media;
	}

	/**
	 * DTO for Media within ChatMessage. Binary media encoded as bytes or a base64 string.
	 */
	public static class MediaDTO {

		@JsonProperty("mimeType")
		private String mimeType;

		@JsonProperty("data")
		private Object data;

		public MediaDTO() {
		}

		// Getters and Setters

		public String getMimeType() {
			return mimeType;
		}

		public void setMimeType(String mimeType) {
			this.mimeType = mimeType;
		}

		public Object getData() {
			return data;
		}

		public void setData(Object data) {
			this.data = data;
		}

	}

}
