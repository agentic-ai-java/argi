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
package io.github.agentic.ai.adapter.langchain4j;

import io.github.agentic.ai.api.chat.ChatStreams;
import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AsyncNotSupportedException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Adapts LangChain4j models without creating an AI Service or a native agent loop. */
public final class LangChain4jChatClientAdapter implements ChatClient {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final ChatModel model;

	private final StreamingChatModel streamingModel;

	public LangChain4jChatClientAdapter(ChatModel model) {
		this(model, null);
	}

	public LangChain4jChatClientAdapter(ChatModel model, StreamingChatModel streamingModel) {
		this.model = Objects.requireNonNull(model, "model must not be null");
		this.streamingModel = streamingModel;
	}

	@Override
	public CompletionStage<ChatResponse> call(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return Mono.defer(() -> {
			var nativeRequest = toNativeRequest(request);
			var nativeOptions = ChatRequestOptions.builder()
				.listenerAttributes(new LinkedHashMap<>(request.attributes()))
				.build();
			return Mono.fromFuture(() -> model.chatAsync(nativeRequest, nativeOptions))
				.onErrorResume(AsyncNotSupportedException.class,
						error -> Mono.fromCallable(() -> model.chat(nativeRequest, nativeOptions))
							.subscribeOn(Schedulers.boundedElastic()));
		}).map(LangChain4jChatClientAdapter::toResponse).toFuture();
	}

	@Override
	public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return JdkFlowAdapter.publisherToFlowPublisher(Flux.defer(() -> {
			if (streamingModel == null) {
				return Flux
					.error(new UnsupportedOperationException("No LangChain4j StreamingChatModel was configured"));
			}
			if (!request.attributes().isEmpty()) {
				return Flux.error(new UnsupportedOperationException(
						"LangChain4j Flow streaming does not expose listener attributes"));
			}
			StringBuilder text = new StringBuilder();
			AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
			return JdkFlowAdapter.flowPublisherToFlux(streamingModel.chat(toNativeRequest(request)))
				.concatMap(event -> {
					if (aggregate.get() != null) {
						return Flux
							.error(new IllegalStateException("LangChain4j emitted an event after its final response"));
					}
					if (event instanceof PartialResponse partial) {
						text.append(partial.text());
						return Flux.just(ChatEvent.textDelta(partial.text()));
					}
					if (event instanceof CompleteResponse complete) {
						aggregate.set(toResponse(complete.chatResponse()));
					}
					// The final response is authoritative for complete tool calls and
					// usage.
					return Flux.empty();
				})
				.concatWith(Flux.defer(() -> {
					ChatResponse response = aggregate.get();
					if (response == null) {
						return Flux
							.error(new IllegalStateException("LangChain4j stream ended without a final response"));
					}
					String suffix = ChatStreams.unseenSuffix(response.text(), text);
					Flux<ChatEvent> remainingText = suffix.isEmpty() ? Flux.empty()
							: Flux.just(ChatEvent.textDelta(suffix));
					return remainingText.concatWith(Flux.fromIterable(response.toolCalls()).map(ChatEvent::toolCall))
						.concatWithValues(ChatEvent.usage(response.usage()), ChatEvent.response(response));
				}));
		}));
	}

	@Override
	public ChatClientCapabilities capabilities() {
		boolean structured = model.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
		return new ChatClientCapabilities(streamingModel != null, true, structured, false, true, true);
	}

	private static dev.langchain4j.model.chat.request.ChatRequest toNativeRequest(ChatRequest request) {
		if (!request.options().metadata().isEmpty()) {
			throw new UnsupportedOperationException("LangChain4j generation metadata is not supported by this adapter");
		}
		var options = request.options();
		var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
			.messages(request.messages().stream().map(LangChain4jChatClientAdapter::toMessage).toList())
			.temperature(options.temperature())
			.topP(options.topP())
			.maxOutputTokens(options.maxTokens())
			.stopSequences(options.stopSequences())
			.toolSpecifications(request.tools().stream().map(LangChain4jChatClientAdapter::toSpecification).toList());
		builder.toolChoice(switch (options.toolChoice().mode()) {
			case AUTO -> dev.langchain4j.model.chat.request.ToolChoice.AUTO;
			case NONE -> dev.langchain4j.model.chat.request.ToolChoice.NONE;
			case REQUIRED -> dev.langchain4j.model.chat.request.ToolChoice.REQUIRED;
			case NAMED -> throw new UnsupportedOperationException("LangChain4j does not expose named tool choice");
		});
		if (options.responseFormat() != null) {
			builder.responseFormat(toFormat(options.responseFormat()));
		}
		return builder.build();
	}

	private static dev.langchain4j.data.message.ChatMessage toMessage(ChatMessage message) {
		if (message.content()
			.stream()
			.anyMatch(part -> part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON)) {
			throw new UnsupportedOperationException("LangChain4j adapter currently accepts text and JSON content only");
		}
		return switch (message.role()) {
			case SYSTEM -> SystemMessage.from(message.text());
			case USER -> UserMessage.from(message.text());
			case TOOL -> ToolExecutionResultMessage.from(message.toolCallId(), message.toolName(), message.text());
			case ASSISTANT -> AiMessage.builder()
				.text(message.content().isEmpty() ? null : message.text())
				.toolExecutionRequests(message.toolCalls()
					.stream()
					.map(tool -> ToolExecutionRequest.builder()
						.id(tool.id())
						.name(tool.name())
						.arguments(tool.arguments())
						.build())
					.toList())
				.attributes(message.metadata())
				.build();
		};
	}

	private static ToolSpecification toSpecification(ToolDefinition tool) {
		try {
			var schema = JSON.readTree(tool.inputSchema());
			if (schema == null || !schema.isObject()) {
				throw new IllegalArgumentException("Tool schema must be a JSON object");
			}
			if (schema.isEmpty()) {
				schema = JSON.createObjectNode().put("type", "object");
			}
			var definition = JSON.createObjectNode().put("name", tool.name()).put("description", tool.description());
			definition.set("parameters", schema);
			return ToolSpecification.fromJson(JSON.writeValueAsString(definition));
		}
		catch (JsonProcessingException error) {
			throw new IllegalArgumentException("Invalid tool schema for " + tool.name(), error);
		}
	}

	private static dev.langchain4j.model.chat.request.ResponseFormat toFormat(ResponseFormat format) {
		return switch (format.type()) {
			case TEXT -> dev.langchain4j.model.chat.request.ResponseFormat.TEXT;
			case JSON_OBJECT -> dev.langchain4j.model.chat.request.ResponseFormat.JSON;
			case JSON_SCHEMA -> dev.langchain4j.model.chat.request.ResponseFormat.builder()
				.type(ResponseFormatType.JSON)
				.jsonSchema(JsonSchema.builder()
					.name(format.name())
					.rootElement(JsonRawSchema.from(format.schema()))
					.build())
				.build();
		};
	}

	private static ToolCall toToolCall(ToolExecutionRequest tool) {
		return new ToolCall(
				tool.id() == null || tool.id().isBlank() ? java.util.UUID.randomUUID().toString() : tool.id(),
				tool.name(), tool.arguments());
	}

	private static ChatResponse toResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
		Objects.requireNonNull(response, "LangChain4j returned no response");
		AiMessage output = Objects.requireNonNull(response.aiMessage(), "LangChain4j returned no assistant message");
		List<ToolCall> calls = output.toolExecutionRequests()
			.stream()
			.map(LangChain4jChatClientAdapter::toToolCall)
			.toList();
		var tokens = response.tokenUsage();
		Usage usage = tokens == null ? Usage.unknown() : new Usage(toLong(tokens.inputTokenCount()),
				toLong(tokens.outputTokenCount()), toLong(tokens.totalTokenCount()));
		FinishReason finish = response.finishReason() == null ? null : switch (response.finishReason()) {
			case STOP -> FinishReason.STOP;
			case LENGTH -> FinishReason.LENGTH;
			case TOOL_EXECUTION -> FinishReason.TOOL_CALLS;
			case CONTENT_FILTER -> FinishReason.CONTENT_FILTER;
			default -> FinishReason.OTHER;
		};
		Map<String, Object> metadata = new java.util.HashMap<>(output.attributes());
		if (output.thinking() != null) {
			metadata.put(ChatMetadataKeys.REASONING, output.thinking());
		}
		ChatMessage message = ChatMessage.assistant(output.text(), calls);
		ChatMessage assistant = new ChatMessage(message.role(), message.content(), calls, null, null, metadata);
		return new ChatResponse(response.id(), response.modelName(), List.of(assistant), calls, finish, usage,
				Map.of());
	}

	private static Long toLong(Integer value) {
		return value == null ? null : value.longValue();
	}

}
