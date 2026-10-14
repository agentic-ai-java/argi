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
package io.github.agentic.ai.adapter.springai;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.ToolChoice;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Adapts Spring AI ChatClient and ChatModel to the ARGI ChatClient contract. */
public final class SpringAiChatClientAdapter implements ChatClient {

	private final org.springframework.ai.chat.client.ChatClient chatClient;

	public SpringAiChatClientAdapter(org.springframework.ai.chat.client.ChatClient chatClient) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
	}

	public SpringAiChatClientAdapter(ChatModel chatModel) {
		this(org.springframework.ai.chat.client.ChatClient.create(chatModel));
	}

	@Override
	public CompletionStage<ChatResponse> call(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return Mono.fromCallable(() -> toArgiResponse(requestSpec(request).call().chatResponse()))
			.subscribeOn(Schedulers.boundedElastic())
			.toFuture();
	}

	@Override
	public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		Flux<ChatEvent> events = Flux.defer(() -> {
			AtomicReference<org.springframework.ai.chat.model.ChatResponse> aggregate = new AtomicReference<>();
			AtomicBoolean received = new AtomicBoolean();
			Flux<org.springframework.ai.chat.model.ChatResponse> chunks = requestSpec(request).stream()
				.chatResponse()
				// Native advisors aggregate before ARGI sees chunks. Preserve their chain
				// and report invalid native data rather than synthesizing a response.
				.onErrorMap(NullPointerException.class,
						failure -> new IllegalStateException("Spring AI stream failed before ARGI conversion; "
								+ "check native generation outputs and advisor responses. "
								+ "Usage-only chunks must use an empty generation list", failure))
				.doOnNext(response -> {
					if (!response.getResults().isEmpty()) {
						received.set(true);
					}
				});
			return new MessageAggregator().aggregate(chunks, aggregate::set)
				// Usage-only chunks still contribute to aggregation, but have no text
				// delta.
				.filter(response -> !response.getResults().isEmpty())
				.map(SpringAiChatClientAdapter::toArgiResponse)
				.filter(response -> !response.messages().isEmpty() && (!response.text().isEmpty()
						|| response.messages().get(0).metadata().containsKey(ChatMetadataKeys.REASONING)))
				.map(response -> new ChatEvent(ChatEvent.Type.TEXT_DELTA, response.text(), null, null, null,
						response.messages().get(0).metadata()))
				.concatWith(Flux.defer(() -> received.get() ? finalEvents(toArgiResponse(aggregate.get()))
						: Flux.error(new IllegalStateException("Spring AI stream ended without a response"))));
		});
		return JdkFlowAdapter.publisherToFlowPublisher(events);
	}

	@Override
	public ChatClientCapabilities capabilities() {
		return new ChatClientCapabilities(true, true, false, false, true, true);
	}

	private ChatClientRequestSpec requestSpec(ChatRequest request) {
		validate(request);
		List<ToolCallback> callbacks = request.options().toolChoice().mode() == ToolChoice.Mode.NONE ? List.of()
				: request.tools().stream().map(SpringAiChatClientAdapter::toToolCallback).toList();
		return chatClient.prompt()
			.messages(request.messages().stream().map(SpringAiChatClientAdapter::toSpringMessage).toList())
			.advisors(advisors -> advisors.params(request.attributes())
				.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false)
				.advisors(new ReturnToolCallsAdvisor(callbacks, request.options())));
	}

	private static void validate(ChatRequest request) {
		if (request.options().toolChoice().mode() == ToolChoice.Mode.REQUIRED
				|| request.options().toolChoice().mode() == ToolChoice.Mode.NAMED) {
			throw new UnsupportedOperationException(
					"Spring AI common options do not expose REQUIRED or NAMED tool choice");
		}
		ResponseFormat format = request.options().responseFormat();
		if (format != null && format.type() != ResponseFormat.Type.TEXT) {
			throw new UnsupportedOperationException("Configure native structured output on the Spring AI ChatClient");
		}
		if (!request.options().metadata().isEmpty()) {
			throw new UnsupportedOperationException("Spring AI generation metadata is not supported by this adapter");
		}
		for (ChatMessage message : request.messages()) {
			if (message.content()
				.stream()
				.anyMatch(part -> part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON)) {
				throw new UnsupportedOperationException(
						"Spring AI adapter currently accepts text and JSON content only");
			}
		}
	}

	private static Flux<ChatEvent> finalEvents(ChatResponse response) {
		return Flux.fromIterable(response.toolCalls())
			.map(ChatEvent::toolCall)
			.concatWithValues(ChatEvent.usage(response.usage()), ChatEvent.response(response));
	}

	private static ToolCallback toToolCallback(ToolDefinition definition) {
		org.springframework.ai.tool.definition.ToolDefinition springDefinition = DefaultToolDefinition.builder()
			.name(definition.name())
			.description(definition.description() == null ? "" : definition.description())
			.inputSchema(definition.inputSchema())
			.build();
		return new ToolCallback() {
			@Override
			public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
				return springDefinition;
			}

			@Override
			public String call(String toolInput) {
				throw new IllegalStateException("Tool execution must be handled by ARGI");
			}
		};
	}

	private static Message toSpringMessage(ChatMessage message) {
		String text = message.text();
		return switch (message.role()) {
			case SYSTEM -> SystemMessage.builder().text(text).metadata(message.metadata()).build();
			case USER -> UserMessage.builder().text(text).metadata(message.metadata()).build();
			case ASSISTANT -> toAssistantMessage(message, text);
			case TOOL -> toToolResponseMessage(message, text);
		};
	}

	private static AssistantMessage toAssistantMessage(ChatMessage message, String text) {
		List<AssistantMessage.ToolCall> toolCalls = message.toolCalls()
			.stream()
			.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(), "function", toolCall.name(),
					toolCall.arguments()))
			.toList();
		return AssistantMessage.builder().content(text).toolCalls(toolCalls).properties(message.metadata()).build();
	}

	private static ToolResponseMessage toToolResponseMessage(ChatMessage message, String text) {
		String toolCallId = Objects.requireNonNull(message.toolCallId(), "toolCallId is required for tool messages");
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, message.toolName(), text)))
			.metadata(message.metadata())
			.build();
	}

	private static ChatResponse toArgiResponse(org.springframework.ai.chat.model.ChatResponse response) {
		Objects.requireNonNull(response, "Spring AI returned no response");
		if (response.getResults().size() != 1) {
			throw new UnsupportedOperationException("ARGI requires exactly one Spring AI generation per model turn");
		}
		List<ChatMessage> messages = new ArrayList<>();
		List<ToolCall> toolCalls = new ArrayList<>();
		Generation generation = response.getResult();
		if (generation != null && generation.getOutput() != null) {
			AssistantMessage output = generation.getOutput();
			toolCalls
				.addAll(output.getToolCalls()
					.stream()
					.map(toolCall -> new ToolCall(toolCall.id() == null || toolCall.id().isBlank()
							? UUID.randomUUID().toString() : toolCall.id(), toolCall.name(), toolCall.arguments()))
					.toList());
			ChatMessage message = ChatMessage.assistant(output.getText(), toolCalls);
			Map<String, Object> properties = new java.util.HashMap<>(output.getMetadata());
			Object reasoning = properties.remove("reasoningContent");
			if (reasoning != null) {
				properties.put(ChatMetadataKeys.REASONING, reasoning);
			}
			messages.add(message.withMetadata(properties));
		}
		var metadata = response.getMetadata();
		Usage usage = metadata == null || metadata.getUsage() == null ? Usage.unknown() : toUsage(metadata.getUsage());
		FinishReason finishReason = generation == null || generation.getMetadata() == null ? null
				: toFinishReason(generation.getMetadata().getFinishReason());
		return new ChatResponse(metadata == null ? null : metadata.getId(),
				metadata == null ? null : metadata.getModel(), messages, toolCalls, finishReason, usage, Map.of());
	}

	private static Usage toUsage(org.springframework.ai.chat.metadata.Usage usage) {
		return new Usage(toLong(usage.getPromptTokens()), toLong(usage.getCompletionTokens()),
				toLong(usage.getTotalTokens()));
	}

	private static Long toLong(Integer value) {
		return value == null ? null : value.longValue();
	}

	private static FinishReason toFinishReason(String reason) {
		if (reason == null) {
			return null;
		}
		return switch (reason.toLowerCase(java.util.Locale.ROOT)) {
			case "stop", "end_turn" -> FinishReason.STOP;
			case "length", "max_tokens" -> FinishReason.LENGTH;
			case "tool_calls", "tool_call", "tool_use" -> FinishReason.TOOL_CALLS;
			case "content_filter" -> FinishReason.CONTENT_FILTER;
			default -> FinishReason.OTHER;
		};
	}

}
