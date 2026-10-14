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
package io.github.agentic.ai.graph.agent.interceptor;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ContentPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * Per-subscription protocol validation and text reconciliation for model interceptors.
 */
final class ModelStreamResponses {

	private final StringBuilder text = new StringBuilder();

	private ChatResponse response;

	static Flux<ChatEvent> normalize(Flux<ChatEvent> source) {
		return Flux.defer(() -> {
			ModelStreamResponses state = new ModelStreamResponses();
			return source.concatMap(event -> {
				state.accept(event);
				if (event.type() != ChatEvent.Type.RESPONSE) {
					return Flux.just(event);
				}
				String fullText = event.response().text();
				if (!fullText.startsWith(state.text.toString())) {
					return Flux.error(new IllegalStateException("Final response text does not match streamed deltas"));
				}
				String suffix = fullText.substring(state.text.length());
				// Route unstreamed terminal text through delta interceptors as well.
				return suffix.isEmpty() ? Flux.just(event) : Flux.just(ChatEvent.textDelta(suffix), event);
			}, 1).concatWith(Flux.defer(() -> {
				state.completedResponse();
				return Flux.empty();
			}));
		});
	}

	ChatEvent reconcile(ChatEvent event) {
		if (event.type() != ChatEvent.Type.RESPONSE) {
			return event;
		}
		ChatResponse original = Objects.requireNonNull(event.response(), "Final response must not be null");
		ChatMessage message = ModelResponse.of(original).getMessage();
		if (message.text().equals(text.toString())) {
			return event;
		}
		List<ContentPart> content = new ArrayList<>();
		boolean replaced = false;
		for (ContentPart part : message.content()) {
			if (part.type() == ContentPart.Type.TEXT || part.type() == ContentPart.Type.JSON) {
				if (!replaced) {
					content.add(new ContentPart(part.type(), text.toString(), part.mimeType(), null, part.metadata()));
					replaced = true;
				}
			}
			else {
				content.add(part);
			}
		}
		if (!replaced && !text.isEmpty()) {
			content.add(0, ContentPart.text(text.toString()));
		}
		ChatMessage rewritten = new ChatMessage(message.role(), content, message.toolCalls(), null, null,
				message.metadata());
		ChatResponse rewrittenResponse = new ChatResponse(original.id(), original.model(), List.of(rewritten),
				original.toolCalls(), original.finishReason(), original.usage(), original.metadata());
		return new ChatEvent(event.type(), null, null, rewrittenResponse, null, event.metadata());
	}

	void accept(ChatEvent event) {
		if (response != null) {
			throw new IllegalStateException("ChatClient emitted events after its final response");
		}
		switch (event.type()) {
			case TEXT_DELTA -> text.append(Objects.requireNonNull(event.text(), "Text delta must not be null"));
			case TOOL_CALL -> Objects.requireNonNull(event.toolCall(), "Tool call must not be null");
			case USAGE -> Objects.requireNonNull(event.usage(), "Usage must not be null");
			case RESPONSE -> response = ModelResponse.of(event.response()).getChatResponse();
		}
	}

	ChatResponse completedResponse() {
		if (response == null) {
			throw new IllegalStateException("ChatClient stream ended without a final response");
		}
		return response;
	}

}
