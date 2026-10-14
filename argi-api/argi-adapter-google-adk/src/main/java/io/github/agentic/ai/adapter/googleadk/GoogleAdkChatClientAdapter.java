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
package io.github.agentic.ai.adapter.googleadk;

import io.github.agentic.ai.api.chat.ChatStreams;
import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.ApigeeLlm;
import com.google.adk.models.Claude;
import com.google.adk.models.Gemini;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Adapts ADK BaseLlm directly; ARGI, not ADK Runner, owns orchestration and tool
 * execution.
 */
public final class GoogleAdkChatClientAdapter implements ChatClient {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final BaseLlm model;

	private final ChatClientCapabilities capabilities;

	public GoogleAdkChatClientAdapter(BaseLlm model) {
		this(model, defaultCapabilities(Objects.requireNonNull(model, "model must not be null")));
	}

	/**
	 * Declares capabilities for a custom ADK implementation or narrows provider defaults.
	 * The declaration describes this adapter's mappings, not a guarantee from a remote
	 * model.
	 */
	public GoogleAdkChatClientAdapter(BaseLlm model, ChatClientCapabilities capabilities) {
		this.model = Objects.requireNonNull(model, "model must not be null");
		this.capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
		if (capabilities.multimodal() || !capabilities.async()) {
			throw new IllegalArgumentException("ADK adapter accepts text/JSON input and always invokes asynchronously");
		}
		// ADK 1.11.0 Claude ignores config tools and most generation options, and blocks
		// its caller.
		if (model instanceof Claude && (capabilities.streaming() || capabilities.toolCalling()
				|| capabilities.structuredOutput() || capabilities.cancellation())) {
			throw new IllegalArgumentException("ADK Claude supports only non-streaming text through this adapter");
		}
	}

	@Override
	public CompletionStage<ChatResponse> call(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return Flux.defer(() -> Flux.from(model.generateContent(toRequest(request), false)))
			.single()
			.map(this::toResponse)
			// BaseLlm implementations may perform blocking I/O before returning a
			// Flowable.
			.subscribeOn(Schedulers.boundedElastic())
			.toFuture();
	}

	@Override
	public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return JdkFlowAdapter.publisherToFlowPublisher(Flux.defer(() -> {
			if (!capabilities.streaming()) {
				return Flux.error(new UnsupportedOperationException("Configured ADK model does not support streaming"));
			}
			StreamingTurn turn = new StreamingTurn();
			return Flux.from(model.generateContent(toRequest(request), true))
				.concatMap(turn::accept)
				.concatWith(Flux.defer(() -> {
					if (turn.snapshot == null) {
						return Flux.error(new IllegalStateException("ADK stream completed without a final response"));
					}
					ChatResponse response = toResponse(turn.response());
					return Flux.fromIterable(response.toolCalls())
						.map(ChatEvent::toolCall)
						.concatWithValues(ChatEvent.usage(response.usage()), ChatEvent.response(response));
				}));
		}).subscribeOn(Schedulers.boundedElastic()));
	}

	@Override
	public ChatClientCapabilities capabilities() {
		return capabilities;
	}

	private static ChatClientCapabilities defaultCapabilities(BaseLlm model) {
		if (model instanceof Gemini || model instanceof ApigeeLlm) {
			return new ChatClientCapabilities(true, true, true, false, true, true);
		}
		// BaseLlm has no capability discovery API. Custom implementations opt in
		// explicitly.
		return ChatClientCapabilities.basic();
	}

	private LlmRequest toRequest(ChatRequest request) {
		if (!request.attributes().isEmpty() || !request.options().metadata().isEmpty()) {
			throw new UnsupportedOperationException(
					"ADK does not expose neutral invocation attributes or generation metadata");
		}
		var options = request.options();
		if (!capabilities.toolCalling() && (!request.tools().isEmpty() || request.messages()
			.stream()
			.anyMatch(message -> message.role() == ChatRole.TOOL || !message.toolCalls().isEmpty()))) {
			throw new UnsupportedOperationException("Configured ADK model does not support tool calling");
		}
		if (!capabilities.structuredOutput() && options.responseFormat() != null
				&& options.responseFormat().type() != ResponseFormat.Type.TEXT) {
			throw new UnsupportedOperationException("Configured ADK model does not support structured output");
		}
		if (model instanceof Claude && (options.temperature() != null || options.topP() != null
				|| options.maxTokens() != null || !options.stopSequences().isEmpty())) {
			throw new UnsupportedOperationException(
					"ADK Claude does not honor per-request generation options; configure its constructor instead");
		}
		GenerateContentConfig.Builder config = GenerateContentConfig.builder()
			.candidateCount(1)
			.stopSequences(options.stopSequences());
		if (options.temperature() != null) {
			config.temperature(options.temperature().floatValue());
		}
		if (options.topP() != null) {
			config.topP(options.topP().floatValue());
		}
		if (options.maxTokens() != null) {
			config.maxOutputTokens(options.maxTokens());
		}
		List<Content> contents = new ArrayList<>();
		List<Part> instructions = new ArrayList<>();
		for (ChatMessage message : request.messages()) {
			List<Part> parts = toParts(message);
			if (message.role() == ChatRole.SYSTEM) {
				instructions.addAll(parts);
			}
			else {
				contents.add(Content.builder()
					.role(message.role() == ChatRole.ASSISTANT ? "model" : "user")
					.parts(parts)
					.build());
			}
		}
		if (!instructions.isEmpty()) {
			config.systemInstruction(Content.builder().parts(instructions).build());
		}
		if (!request.tools().isEmpty()) {
			config.tools(Tool.builder()
				.functionDeclarations(request.tools()
					.stream()
					.map(tool -> FunctionDeclaration.builder()
						.name(tool.name())
						.description(tool.description() == null ? "" : tool.description())
						.parametersJsonSchema(parseObject(tool.inputSchema()))
						.build())
					.toList())
				.build());
		}
		FunctionCallingConfig.Builder choice = FunctionCallingConfig.builder()
			.streamFunctionCallArguments(false)
			.mode(switch (options.toolChoice().mode()) {
				case AUTO -> "AUTO";
				case NONE -> "NONE";
				case REQUIRED, NAMED -> "ANY";
			});
		if (options.toolChoice().mode() == io.github.agentic.ai.api.chat.ToolChoice.Mode.NAMED) {
			choice.allowedFunctionNames(options.toolChoice().toolName());
		}
		config.toolConfig(ToolConfig.builder().functionCallingConfig(choice.build()).build());
		if (options.responseFormat() != null) {
			var format = options.responseFormat();
			switch (format.type()) {
				case TEXT -> {
				}
				case JSON_OBJECT -> config.responseMimeType("application/json");
				case JSON_SCHEMA ->
					config.responseMimeType("application/json").responseJsonSchema(parseObject(format.schema()));
			}
		}
		return LlmRequest.builder().contents(contents).config(config.build()).build();
	}

	private static List<Part> toParts(ChatMessage message) {
		if (message.content()
			.stream()
			.anyMatch(part -> part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON)) {
			throw new UnsupportedOperationException("ADK adapter accepts text and JSON input only");
		}
		if (message.role() == ChatRole.TOOL) {
			return List.of(Part.builder()
				.functionResponse(FunctionResponse.builder()
					.id(message.toolCallId())
					.name(message.toolName())
					.response(Map.of("result", message.text()))
					.build())
				.build());
		}
		List<Part> parts = new ArrayList<>();
		message.content().forEach(part -> parts.add(Part.builder().text(part.text()).build()));
		message.toolCalls()
			.forEach(tool -> parts.add(Part.builder()
				.functionCall(FunctionCall.builder()
					.id(tool.id())
					.name(tool.name())
					.args(parseObject(tool.arguments()))
					.build())
				.build()));
		return parts;
	}

	private ChatResponse toResponse(LlmResponse response) {
		checkResponse(response);
		List<Part> parts = response.content().flatMap(Content::parts).orElse(List.of());
		StringBuilder text = new StringBuilder();
		StringBuilder thinking = new StringBuilder();
		List<ToolCall> calls = new ArrayList<>();
		for (Part part : parts) {
			if (part.text().isPresent()) {
				(part.thought().orElse(false) ? thinking : text).append(part.text().get());
			}
			else if (part.functionCall().isPresent()) {
				FunctionCall call = part.functionCall().get();
				if (call.willContinue().orElse(false) || call.partialArgs().isPresent()) {
					throw new IllegalStateException("ADK final response contains incomplete tool arguments");
				}
				calls.add(new ToolCall(
						call.id().filter(id -> !id.isBlank()).orElseGet(() -> UUID.randomUUID().toString()),
						call.name().orElseThrow(() -> new IllegalStateException("ADK tool call has no name")),
						encode(call.args().orElse(Map.of()))));
			}
			else {
				throw new UnsupportedOperationException("Unsupported ADK response part");
			}
		}
		Usage usage = response.usageMetadata()
			.map(value -> new Usage(value.promptTokenCount().map(Integer::longValue).orElse(null),
					value.candidatesTokenCount().map(Integer::longValue).orElse(null),
					value.totalTokenCount().map(Integer::longValue).orElse(null)))
			.orElseGet(Usage::unknown);
		FinishReason finish = response.finishReason().map(reason -> switch (reason.knownEnum()) {
			case STOP -> calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
			case MAX_TOKENS -> FinishReason.LENGTH;
			case SAFETY, RECITATION, BLOCKLIST, PROHIBITED_CONTENT -> FinishReason.CONTENT_FILTER;
			default -> FinishReason.OTHER;
		}).orElse(null);
		Map<String, Object> metadata = thinking.isEmpty() ? Map.of()
				: Map.of(ChatMetadataKeys.REASONING, thinking.toString());
		return new ChatResponse(null, response.modelVersion().orElse(model.model()),
				List.of(ChatMessage.assistant(text.toString(), calls).withMetadata(metadata)), calls, finish, usage,
				Map.of());
	}

	private static void checkResponse(LlmResponse response) {
		if (response.errorCode().isPresent() || response.errorMessage().isPresent()
				|| response.interrupted().orElse(false)) {
			throw new IllegalStateException(
					"ADK model failed: " + response.errorCode().map(Object::toString).orElse("interrupted") + ": "
							+ response.errorMessage().orElse("No error details"));
		}
	}

	private static Map<String, Object> parseObject(String value) {
		try {
			return Objects.requireNonNull(JSON.readValue(value, new TypeReference<Map<String, Object>>() {
			}), "JSON must be an object");
		}
		catch (JsonProcessingException error) {
			throw new IllegalArgumentException("Invalid JSON object", error);
		}
	}

	private static String encode(Object value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (JsonProcessingException error) {
			throw new IllegalArgumentException("Cannot encode tool arguments", error);
		}
	}

	private static final class StreamingTurn {

		private final StringBuilder emitted = new StringBuilder();

		private LlmResponse snapshot;

		private GenerateContentResponseUsageMetadata usage;

		private String modelVersion;

		private boolean terminal;

		private Flux<ChatEvent> accept(LlmResponse chunk) {
			checkResponse(chunk);
			chunk.usageMetadata().ifPresent(value -> usage = value);
			chunk.modelVersion().ifPresent(value -> modelVersion = value);
			List<Part> parts = chunk.content().flatMap(Content::parts).orElse(List.of());
			String text = parts.stream()
				.filter(part -> !part.thought().orElse(false))
				.flatMap(part -> part.text().stream())
				.reduce("", String::concat);
			boolean completesTurn = chunk.turnComplete().orElse(false) || chunk.finishReason().isPresent();
			// ADK can emit usage-only partial events after the finish event.
			if (parts.isEmpty() && !completesTurn) {
				return Flux.empty();
			}
			if (chunk.partial().orElse(false)) {
				if (snapshot != null || terminal) {
					return Flux.error(new IllegalStateException("ADK emitted content after committing its response"));
				}
				emitted.append(text);
				return text.isEmpty() ? Flux.empty() : Flux.just(ChatEvent.textDelta(text));
			}
			if (terminal || (snapshot != null && (!completesTurn || !text.isEmpty()))) {
				return Flux.error(new IllegalStateException("ADK emitted a duplicate or inconsistent final response"));
			}
			// The OpenAI driver splits the text commit and tool/finish metadata into two
			// events.
			// Gemini instead emits one aggregate containing text, thoughts and tool
			// calls.
			if (text.isEmpty() && completesTurn) {
				List<Part> merged = new ArrayList<>();
				if (snapshot != null) {
					merged.addAll(snapshot.content().flatMap(Content::parts).orElse(List.of()));
				}
				else if (!emitted.isEmpty()) {
					merged.add(Part.fromText(emitted.toString()));
				}
				merged.addAll(parts);
				snapshot = chunk.toBuilder().content(Content.builder().role("model").parts(merged).build()).build();
				terminal = true;
				return Flux.empty();
			}
			snapshot = chunk;
			terminal = completesTurn;
			String suffix = ChatStreams.unseenSuffix(text, emitted);
			emitted.append(suffix);
			return suffix.isEmpty() ? Flux.empty() : Flux.just(ChatEvent.textDelta(suffix));
		}

		private LlmResponse response() {
			return snapshot.toBuilder().usageMetadata(usage).modelVersion(modelVersion).build();
		}

	}

}
