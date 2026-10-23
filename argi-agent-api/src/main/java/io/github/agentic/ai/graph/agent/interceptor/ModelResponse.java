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
package io.github.agentic.ai.graph.agent.interceptor;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Response object for model calls. Contains the model's response message.
 */
public final class ModelResponse {

	private final ChatResponse chatResponse;

	private final Flow.Publisher<ChatEvent> stream;

	private ModelResponse(ChatResponse chatResponse, Flow.Publisher<ChatEvent> stream) {
		this.chatResponse = chatResponse;
		this.stream = stream;
	}

	public static ModelResponse of(ChatMessage message) {
		Objects.requireNonNull(message, "message must not be null");
		return of(new ChatResponse(null, null, List.of(message), message.toolCalls(), null, null, Map.of()));
	}

	public static ModelResponse of(ChatResponse response) {
		Objects.requireNonNull(response, "response must not be null");
		if (response.messages().size() != 1 || response.messages().get(0).role() != ChatRole.ASSISTANT) {
			throw new IllegalArgumentException("Agent model turn requires exactly one assistant message");
		}
		return new ModelResponse(response, null);
	}

	public static ModelResponse stream(Flow.Publisher<ChatEvent> stream) {
		return new ModelResponse(null, Objects.requireNonNull(stream, "stream must not be null"));
	}

	public boolean isStreaming() {
		return stream != null;
	}

	public ChatMessage getMessage() {
		return getChatResponse().messages().get(0);
	}

	public ChatResponse getChatResponse() {
		if (isStreaming()) {
			throw new IllegalStateException("Streaming responses must be consumed through getStream()");
		}
		return chatResponse;
	}

	public Flow.Publisher<ChatEvent> getStream() {
		if (!isStreaming()) {
			throw new IllegalStateException("Synchronous responses must be consumed through getChatResponse()");
		}
		return stream;
	}

}
