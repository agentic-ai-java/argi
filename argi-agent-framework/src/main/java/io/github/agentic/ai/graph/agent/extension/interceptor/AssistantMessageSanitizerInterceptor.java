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
package io.github.agentic.ai.graph.agent.extension.interceptor;

import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Normalizes assistant tool-call messages without textual content to an explicit empty
 * text part for backends that require a content field. Tool calls, metadata and nontext
 * content are preserved. This default interceptor can be disabled with
 * {@code ReactAgent.builder().assistantMessageSanitizerEnabled(false)}.
 *
 * @see <a href="https://github.com/agentic-ai-java/argi/issues/4561">Issue #4561</a>
 */
public class AssistantMessageSanitizerInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(AssistantMessageSanitizerInterceptor.class);

	private static final String NAME = "AssistantMessageSanitizer";

	private AssistantMessageSanitizerInterceptor(Builder builder) {
		// Currently no configuration options, but builder pattern allows future
		// extensibility
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		List<ChatMessage> messages = request.getMessages();

		if (messages == null || messages.isEmpty()) {
			return handler.call(request);
		}

		List<ChatMessage> sanitized = sanitizeMessages(messages);

		// Only rebuild the request when at least one message was rewritten.
		if (sanitized != messages) {
			ModelRequest sanitizedRequest = ModelRequest.builder(request).messages(sanitized).build();
			return handler.call(sanitizedRequest);
		}

		return handler.call(request);
	}

	/**
	 * Rewrite tool-calling {@link ChatMessage}s without text so their content becomes an
	 * empty string, preserving tool calls, metadata, and media.
	 * @param messages the original message list
	 * @return a new list with normalized messages, or the original list if nothing needed
	 * fixing
	 */
	private List<ChatMessage> sanitizeMessages(List<ChatMessage> messages) {
		boolean needsFix = false;
		for (ChatMessage msg : messages) {
			if (needsSanitizing(msg)) {
				needsFix = true;
				break;
			}
		}

		if (!needsFix) {
			return messages;
		}

		List<ChatMessage> sanitized = new ArrayList<>(messages.size());
		for (ChatMessage msg : messages) {
			if (needsSanitizing(msg)) {
				ChatMessage fixed = msg.appendText("");
				if (log.isDebugEnabled()) {
					log.debug("[{}] AssistantMessage text was null with tool calls; patched content to empty string",
							NAME);
				}
				sanitized.add(fixed);
			}
			else {
				sanitized.add(msg);
			}
		}

		return sanitized;
	}

	private static boolean needsSanitizing(ChatMessage msg) {
		return msg.role() == ChatRole.ASSISTANT && !msg.toolCalls().isEmpty()
				&& msg.content()
					.stream()
					.noneMatch(part -> part.type() == ContentPart.Type.TEXT || part.type() == ContentPart.Type.JSON);
	}

	/**
	 * Builder for creating {@link AssistantMessageSanitizerInterceptor} instances.
	 */
	public static class Builder {

		public Builder() {
		}

		/**
		 * Build the {@link AssistantMessageSanitizerInterceptor} instance.
		 * @return a new {@link AssistantMessageSanitizerInterceptor}
		 */
		public AssistantMessageSanitizerInterceptor build() {
			return new AssistantMessageSanitizerInterceptor(this);
		}

	}

}
