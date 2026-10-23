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
package io.github.agentic.ai.graph.serializer;

import io.github.agentic.ai.api.chat.ChatMessage;

import java.util.HashMap;
import java.util.Map;

/** Metadata helpers for instruction templates carried by ordinary neutral messages. */
public final class AgentInstructionMessage {

	private static final String TEMPLATE = "argiInstructionTemplate";

	private static final String RENDERED = "argiInstructionRendered";

	private AgentInstructionMessage() {
	}

	public static boolean isTemplate(ChatMessage message) {
		return Boolean.TRUE.equals(message.metadata().get(TEMPLATE));
	}

	public static boolean isRendered(ChatMessage message) {
		return Boolean.TRUE.equals(message.metadata().get(RENDERED));
	}

	public static ChatMessage rendered(ChatMessage message, String text) {
		var metadata = new HashMap<>(message.metadata());
		metadata.put(RENDERED, true);
		return message.withText(text).withMetadata(metadata);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String textContent;

		private boolean rendered;

		private Map<String, Object> metadata = new HashMap<>();

		public Builder text(String textContent) {
			this.textContent = textContent;
			return this;
		}

		public Builder rendered(boolean rendered) {
			this.rendered = rendered;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public ChatMessage build() {
			var properties = new HashMap<>(metadata);
			properties.put(TEMPLATE, true);
			properties.put(RENDERED, rendered);
			return ChatMessage.user(textContent).withMetadata(properties);
		}

	}

}
