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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatStreams;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/** Validates model turns and translates chat events into graph streaming deltas. */
final class AgentChatProtocol {

	private AgentChatProtocol() {
	}

	static List<ChatMessage> toolResponse(List<ChatMessage> results, Map<String, Object> metadata) {
		return results.stream().map(message -> {
			var properties = new HashMap<>(message.metadata());
			properties.putAll(metadata);
			return message.withMetadata(properties);
		}).toList();
	}

	static ChatResponse call(ChatClient client, ChatRequest request) {
		return response(client.callBlocking(request), null);
	}

	static Flux<ChatResponse> stream(ChatClient client, ChatRequest request) {
		return stream(subscriber -> client.stream(request).subscribe(subscriber));
	}

	static Flux<ChatResponse> stream(Flow.Publisher<ChatEvent> publisher) {
		return Flux.defer(() -> {
			StringBuilder emitted = new StringBuilder();
			AtomicBoolean finished = new AtomicBoolean();
			return JdkFlowAdapter.flowPublisherToFlux(publisher).<ChatResponse>handle((event, sink) -> {
				if (finished.get()) {
					sink.error(new IllegalStateException("ChatClient emitted events after its final response"));
					return;
				}
				switch (event.type()) {
					case TEXT_DELTA -> {
						String delta = Objects.requireNonNull(event.text(), "Text delta must not be null");
						emitted.append(delta);
						sink.next(new ChatResponse(null, null,
								List.of(ChatMessage.assistant(delta).withMetadata(event.metadata())), List.of(), null,
								null, event.metadata()));
					}
					case RESPONSE -> {
						var aggregate = Objects.requireNonNull(event.response(), "Final response must not be null");
						sink.next(response(aggregate, ChatStreams.unseenSuffix(aggregate.text(), emitted)));
						finished.set(true);
					}
					case TOOL_CALL, USAGE -> {
						// Final tool calls and usage are authoritative.
					}
				}
			})
				.concatWith(Flux.defer(() -> finished.get() ? Flux.empty()
						: Flux.error(new IllegalStateException("ChatClient stream ended without a final response"))));
		});
	}

	static ChatResponse response(ChatResponse response, String delta) {
		Objects.requireNonNull(response, "ChatClient returned no response");
		if (response.messages().size() != 1 || response.messages().get(0).role() != ChatRole.ASSISTANT) {
			throw new IllegalArgumentException("Agent model turn requires exactly one assistant message");
		}
		ChatMessage message = response.messages().get(0).withToolCalls(response.toolCalls());
		var metadata = new HashMap<>(response.metadata());
		if (delta != null) {
			metadata.put("argiFinalResponse", true);
			// Keep the aggregate authoritative; the graph exposes only the unseen suffix.
			metadata.put("argiTextDelta", delta);
		}
		return new ChatResponse(response.id(), response.model(), List.of(message), response.toolCalls(),
				response.finishReason(), response.usage(), metadata);
	}

}
