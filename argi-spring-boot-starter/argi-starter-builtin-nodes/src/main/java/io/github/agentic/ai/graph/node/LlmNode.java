/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.action.AsyncNodeAction;
import io.github.agentic.ai.graph.streaming.GraphFlux;
import io.github.agentic.ai.graph.utils.PromptTemplate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Executes one model turn through an ARGI ChatClient. This node never executes tools.
 * Configuration is immutable; templates and history are resolved per invocation.
 */
public final class LlmNode implements AsyncNodeAction {

	public static final String LLM_RESPONSE_KEY = "llm_response";

	private final ChatClient chatClient;

	private final String systemPrompt;

	private final String userPrompt;

	private final String systemPromptKey;

	private final String userPromptKey;

	private final String paramsKey;

	private final String messagesKey;

	private final String outputKey;

	private final Map<String, Object> params;

	private final List<ChatMessage> messages;

	private final List<ToolDefinition> tools;

	private final GenerationOptions options;

	private final boolean streaming;

	private LlmNode(Builder builder) {
		this.chatClient = Objects.requireNonNull(builder.chatClient, "chatClient must not be null");
		this.systemPrompt = builder.systemPrompt;
		this.userPrompt = builder.userPrompt;
		this.systemPromptKey = builder.systemPromptKey;
		this.userPromptKey = builder.userPromptKey;
		this.paramsKey = builder.paramsKey;
		this.messagesKey = builder.messagesKey;
		this.outputKey = builder.outputKey;
		if (LLM_RESPONSE_KEY.equals(outputKey)) {
			throw new IllegalArgumentException("outputKey must not overwrite llm_response");
		}
		if (NodeOutput.TOKEN_USAGE_KEY.equals(outputKey)) {
			throw new IllegalArgumentException("outputKey must not overwrite node token usage metadata");
		}
		this.params = Map.copyOf(builder.params);
		this.messages = List.copyOf(builder.messages);
		this.tools = List.copyOf(builder.tools);
		this.options = Objects.requireNonNull(builder.options, "options must not be null");
		this.streaming = builder.streaming;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		return Mono.defer(() -> {
			if (streaming) {
				GraphFlux<ChatEvent> graphFlux = GraphFlux.of(null, LLM_RESPONSE_KEY, stream(state),
						event -> responseState(event.response()), null);
				return Mono.just(Map.<String, Object>of(LLM_RESPONSE_KEY, graphFlux));
			}
			return Mono.fromCompletionStage(() -> call(state)).map(this::responseState);
		}).toFuture();
	}

	/** Starts a cancellable, asynchronous model turn. */
	public CompletionStage<ChatResponse> call(OverAllState state) {
		return Mono
			.defer(() -> Mono.fromCompletionStage(chatClient.call(request(state)))
				.switchIfEmpty(Mono.error(new IllegalStateException("ChatClient returned no response")))
				.map(this::validateResponse))
			.toFuture();
	}

	/**
	 * A cold stream; final state is eligible for submission only after successful
	 * completion.
	 */
	public Flux<ChatEvent> stream(OverAllState state) {
		return Flux.defer(() -> {
			ChatRequest request = request(state);
			AtomicReference<ChatEvent> terminal = new AtomicReference<>();
			// Hold the final response until onComplete so a subsequent failure cannot
			// commit it.
			return JdkFlowAdapter.flowPublisherToFlux(chatClient.stream(request)).<ChatEvent>handle((event, sink) -> {
				if (terminal.get() != null) {
					sink.error(new IllegalStateException("ChatClient emitted an event after its final response"));
				}
				else if (event.type() == ChatEvent.Type.RESPONSE) {
					terminal.set(ChatEvent.response(validateResponse(event.response())));
				}
				else {
					sink.next(event);
				}
			})
				.concatWith(Mono.defer(() -> terminal.get() == null
						? Mono.error(new IllegalStateException("ChatClient stream completed without a final response"))
						: Mono.just(terminal.get())));
		});
	}

	private ChatRequest request(OverAllState state) {
		Objects.requireNonNull(state, "state must not be null");
		Map<String, Object> values = new HashMap<>(state.data());
		Object configuredParams = value(state, paramsKey, params);
		if (!(configuredParams instanceof Map<?, ?> parameters)) {
			throw new IllegalArgumentException("Prompt parameters must be a map");
		}
		parameters.forEach((key, value) -> {
			if (!(key instanceof String name)) {
				throw new IllegalArgumentException("Prompt parameter names must be strings");
			}
			values.put(name, "null".equals(value) ? state.value(name).orElse(value) : value);
		});
		List<ChatMessage> requestMessages = new ArrayList<>();
		String system = prompt(state, systemPromptKey, systemPrompt);
		if (hasText(system)) {
			requestMessages.add(ChatMessage.system(render(system, values)));
		}
		Object history = value(state, messagesKey, messages);
		if (history instanceof ChatMessage message) {
			requestMessages.add(message);
		}
		else if (history instanceof List<?> list) {
			for (Object item : list) {
				if (!(item instanceof ChatMessage message)) {
					throw new IllegalArgumentException("History must contain only ARGI ChatMessage values");
				}
				requestMessages.add(message);
			}
		}
		else {
			throw new IllegalArgumentException("History must be an ARGI ChatMessage or a list of messages");
		}
		String user = prompt(state, userPromptKey, userPrompt);
		if (hasText(user)) {
			requestMessages.add(ChatMessage.user(render(user, values)));
		}
		return new ChatRequest(requestMessages, tools, options, Map.of());
	}

	private static Object value(OverAllState state, String key, Object fallback) {
		return hasText(key) ? state.value(key).orElse(fallback) : fallback;
	}

	private static String prompt(OverAllState state, String key, String fallback) {
		Object value = value(state, key, fallback);
		if (value != null && !(value instanceof String)) {
			throw new IllegalArgumentException("Prompt must be a string");
		}
		return (String) value;
	}

	private static String render(String text, Map<String, Object> values) {
		return PromptTemplate.of(text).render(values);
	}

	private ChatResponse validateResponse(ChatResponse response) {
		if (response == null || response.messages().isEmpty()
				|| response.messages().stream().anyMatch(message -> message.role() != ChatRole.ASSISTANT)) {
			throw new IllegalStateException("ChatClient must return at least one assistant message and no other roles");
		}
		return response;
	}

	private Map<String, Object> responseState(ChatResponse response) {
		validateResponse(response);
		Map<String, Object> result = new HashMap<>();
		result.put("messages", response.messages());
		result.put(LLM_RESPONSE_KEY, response);
		result.put(NodeOutput.TOKEN_USAGE_KEY, response.usage());
		if (hasText(outputKey)) {
			result.put(outputKey, response.messages());
		}
		return result;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public static final class Builder {

		private ChatClient chatClient;

		private String systemPrompt;

		private String userPrompt;

		private String systemPromptKey;

		private String userPromptKey;

		private String paramsKey;

		private String messagesKey = "messages";

		private String outputKey;

		private Map<String, ?> params = Map.of();

		private List<ChatMessage> messages = List.of();

		private List<ToolDefinition> tools = List.of();

		private GenerationOptions options = GenerationOptions.defaults();

		private boolean streaming;

		private Builder() {
		}

		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder systemPromptTemplate(String template) {
			this.systemPrompt = template;
			return this;
		}

		public Builder userPromptTemplate(String template) {
			this.userPrompt = template;
			return this;
		}

		public Builder systemPromptTemplateKey(String key) {
			this.systemPromptKey = key;
			return this;
		}

		public Builder userPromptTemplateKey(String key) {
			this.userPromptKey = key;
			return this;
		}

		public Builder params(Map<String, ?> params) {
			this.params = params;
			return this;
		}

		public Builder paramsKey(String key) {
			this.paramsKey = key;
			return this;
		}

		public Builder messages(List<ChatMessage> messages) {
			this.messages = messages;
			return this;
		}

		public Builder messagesKey(String key) {
			this.messagesKey = key;
			return this;
		}

		public Builder outputKey(String key) {
			this.outputKey = key;
			return this;
		}

		public Builder tools(List<ToolDefinition> tools) {
			this.tools = tools;
			return this;
		}

		public Builder toolRegistry(ToolRegistry registry) {
			this.tools = registry.definitions();
			return this;
		}

		public Builder options(GenerationOptions options) {
			this.options = options;
			return this;
		}

		public Builder stream(boolean stream) {
			this.streaming = stream;
			return this;
		}

		public LlmNode build() {
			return new LlmNode(this);
		}

	}

}
