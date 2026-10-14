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
package io.github.agentic.ai.adapter.agentscope;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.agent.accumulator.ThinkingAccumulator;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * Adapts a single AgentScope model turn without invoking an AgentScope agent or toolkit.
 */
public final class AgentScopeChatClientAdapter implements ChatClient {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Model model;

	public AgentScopeChatClientAdapter(Model model) {
		this.model = Objects.requireNonNull(model, "model must not be null");
	}

	@Override
	public CompletionStage<ChatResponse> call(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		// AgentScope returns deltas even through its only model invocation API.
		return responses(request).reduce(new Turn(), (turn, chunk) -> {
			turn.add(chunk);
			return turn;
		}).map(turn -> turn.response(model.getModelName())).toFuture();
	}

	@Override
	public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return JdkFlowAdapter.publisherToFlowPublisher(Flux.defer(() -> {
			Turn turn = new Turn();
			return responses(request).concatMap(chunk -> {
				turn.add(chunk);
				return Flux.fromIterable(chunk.getContent())
					.ofType(TextBlock.class)
					.map(TextBlock::getText)
					.filter(text -> !text.isEmpty())
					.map(ChatEvent::textDelta);
			}).concatWith(Flux.defer(() -> {
				ChatResponse response = turn.response(model.getModelName());
				return Flux.fromIterable(response.toolCalls())
					.map(ChatEvent::toolCall)
					.concatWithValues(ChatEvent.usage(response.usage()), ChatEvent.response(response));
			}));
		}));
	}

	@Override
	public ChatClientCapabilities capabilities() {
		return new ChatClientCapabilities(true, true, model.supportsNativeStructuredOutput(), false, true, true);
	}

	private Flux<io.agentscope.core.model.ChatResponse> responses(ChatRequest request) {
		return Flux.defer(() -> {
			var options = request.options();
			if (!request.attributes().isEmpty() || !options.metadata().isEmpty()) {
				throw new UnsupportedOperationException(
						"AgentScope does not expose neutral invocation attributes or generation metadata");
			}
			if (!options.stopSequences().isEmpty()) {
				throw new UnsupportedOperationException("AgentScope common options do not expose stop sequences");
			}
			GenerateOptions.Builder builder = GenerateOptions.builder()
				.stream(true)
				.temperature(options.temperature())
				.topP(options.topP())
				.maxTokens(options.maxTokens());
			builder.toolChoice(switch (options.toolChoice().mode()) {
				case AUTO -> new io.agentscope.core.model.ToolChoice.Auto();
				case NONE -> new io.agentscope.core.model.ToolChoice.None();
				case REQUIRED -> new io.agentscope.core.model.ToolChoice.Required();
				case NAMED -> new io.agentscope.core.model.ToolChoice.Specific(options.toolChoice().toolName());
			});
			if (options.responseFormat() != null) {
				var format = options.responseFormat();
				if (format.type() != io.github.agentic.ai.api.chat.ResponseFormat.Type.TEXT
						&& (!model.supportsNativeStructuredOutput()
								|| (!request.tools().isEmpty() && !model.supportsNativeStructuredOutputWithTools()))) {
					throw new UnsupportedOperationException(
							"AgentScope model does not support the requested native structured output");
				}
				builder.responseFormat(switch (format.type()) {
					case TEXT -> io.agentscope.core.formatter.ResponseFormat.text();
					case JSON_OBJECT -> io.agentscope.core.formatter.ResponseFormat.jsonObject();
					case JSON_SCHEMA -> io.agentscope.core.formatter.ResponseFormat.jsonSchema(
							JsonSchema.builder().name(format.name()).schema(parseObject(format.schema())).build());
				});
			}
			List<ToolSchema> tools = request.tools()
				.stream()
				.map(tool -> ToolSchema.builder()
					.name(tool.name())
					.description(tool.description())
					.parameters(parseObject(tool.inputSchema()))
					.build())
				.toList();
			return model.stream(request.messages().stream().map(AgentScopeChatClientAdapter::toMessage).toList(), tools,
					builder.build());
		});
	}

	private static Msg toMessage(ChatMessage message) {
		if (message.content()
			.stream()
			.anyMatch(part -> part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON)) {
			throw new UnsupportedOperationException("AgentScope adapter accepts text and JSON input only");
		}
		List<ContentBlock> blocks = new ArrayList<>();
		if (message.role() == io.github.agentic.ai.api.chat.ChatRole.TOOL) {
			blocks.add(ToolResultBlock.builder()
				.id(message.toolCallId())
				.name(message.toolName())
				.output(TextBlock.builder().text(message.text()).build())
				.build());
		}
		else {
			if (!message.content().isEmpty()) {
				blocks.add(TextBlock.builder().text(message.text()).build());
			}
			message.toolCalls()
				.forEach(tool -> blocks.add(ToolUseBlock.builder()
					.id(tool.id())
					.name(tool.name())
					.input(parseObject(tool.arguments()))
					.build()));
		}
		return Msg.builder()
			.name(message.role().name().toLowerCase(Locale.ROOT))
			.role(MsgRole.valueOf(message.role().name()))
			.content(blocks)
			.metadata(message.metadata())
			.build();
	}

	private static Map<String, Object> parseObject(String value) {
		try {
			Map<String, Object> result = JSON.readValue(value, new TypeReference<Map<String, Object>>() {
			});
			if (result == null) {
				throw new IllegalArgumentException("JSON must be an object");
			}
			return result;
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

	private static final class Turn {

		private final TextAccumulator text = new TextAccumulator();

		private final ThinkingAccumulator thinking = new ThinkingAccumulator();

		private final ToolCallsAccumulator tools = new ToolCallsAccumulator();

		private final Map<String, String> pendingToolIdsByName = new LinkedHashMap<>();

		private final Map<String, String> nativeToolIds = new LinkedHashMap<>();

		private final Map<String, String> reportedToolIds = new LinkedHashMap<>();

		private final Map<String, String> toolNames = new LinkedHashMap<>();

		private final Map<String, StringBuilder> rawArguments = new LinkedHashMap<>();

		private final Map<String, Object> metadata = new LinkedHashMap<>();

		private String currentToolId;

		private String id;

		private String finishReason;

		private ChatUsage usage;

		private boolean received;

		private void add(io.agentscope.core.model.ChatResponse chunk) {
			received = true;
			if (chunk.getId() != null) {
				id = chunk.getId();
			}
			if (chunk.getFinishReason() != null) {
				finishReason = chunk.getFinishReason();
			}
			if (chunk.getUsage() != null) {
				usage = chunk.getUsage();
			}
			if (chunk.getMetadata() != null) {
				metadata.putAll(chunk.getMetadata());
			}
			for (ContentBlock block : chunk.getContent()) {
				if (block instanceof TextBlock tb) {
					text.add(tb);
				}
				else if (block instanceof ThinkingBlock tb) {
					thinking.add(tb);
				}
				else if (block instanceof ToolUseBlock tool) {
					addTool(tool);
				}
				else {
					throw new UnsupportedOperationException(
							"Unsupported AgentScope response block: " + block.getClass().getName());
				}
			}
		}

		private void addTool(ToolUseBlock tool) {
			String nativeId = tool.getId();
			String name = tool.getName();
			boolean named = name != null && !name.isBlank() && !name.startsWith("__");
			String toolId;
			if (nativeId != null && !nativeId.isBlank()) {
				toolId = nativeToolIds.get(nativeId);
				if (toolId == null) {
					// A provider may publish its ID after the first argument fragment.
					// Keep the accumulator key stable while retaining the provider's
					// final ID.
					String pending = named ? pendingToolIdsByName.get(name) : currentToolId;
					toolId = pending != null && !reportedToolIds.containsKey(pending) ? pending
							: UUID.randomUUID().toString();
					nativeToolIds.put(nativeId, toolId);
					reportedToolIds.put(toolId, nativeId);
					pendingToolIdsByName.values().remove(toolId);
				}
			}
			else {
				if (named) {
					toolId = name.equals(toolNames.get(currentToolId)) ? currentToolId
							: pendingToolIdsByName.computeIfAbsent(name, ignored -> UUID.randomUUID().toString());
				}
				else {
					toolId = currentToolId;
				}
			}
			if (toolId == null) {
				throw new IllegalStateException("AgentScope tool fragment has no preceding invocation");
			}
			currentToolId = toolId;
			if (named) {
				toolNames.put(toolId, name);
			}
			// Stable IDs avoid the native accumulator's time-based fallback collisions.
			tools.add(ToolUseBlock.builder()
				.id(toolId)
				.name(tool.getName())
				.input(tool.getInput())
				.content(tool.getContent())
				.metadata(tool.getMetadata())
				.build());
			if (tool.getContent() != null && !tool.getContent().isEmpty()) {
				rawArguments.computeIfAbsent(toolId, ignored -> new StringBuilder()).append(tool.getContent());
			}
		}

		private ChatResponse response(String modelName) {
			if (!received) {
				throw new IllegalStateException("AgentScope returned no response");
			}
			// Validate the original JSON before the native accumulator can sanitize it to
			// {}.
			rawArguments.values().forEach(arguments -> parseObject(arguments.toString()));
			List<ToolCall> calls = tools.buildAllToolCalls()
				.stream()
				.map(tool -> new ToolCall(reportedToolIds.getOrDefault(tool.getId(), tool.getId()), tool.getName(),
						encode(tool.getInput())))
				.toList();
			Map<String, Object> messageMetadata = thinking.hasContent()
					? Map.of(ChatMetadataKeys.REASONING, thinking.getAccumulated()) : Map.of();
			Usage tokens = usage == null ? Usage.unknown() : new Usage((long) usage.getInputTokens(),
					(long) usage.getOutputTokens(), (long) usage.getTotalTokens());
			FinishReason finish = finishReason == null ? null : switch (finishReason.toLowerCase(Locale.ROOT)) {
				case "stop", "end_turn" -> FinishReason.STOP;
				case "length", "max_tokens" -> FinishReason.LENGTH;
				case "tool_calls", "tool_use" -> FinishReason.TOOL_CALLS;
				case "content_filter" -> FinishReason.CONTENT_FILTER;
				default -> FinishReason.OTHER;
			};
			return new ChatResponse(id, modelName,
					List.of(ChatMessage.assistant(text.getAccumulated(), calls).withMetadata(messageMetadata)), calls,
					finish, tokens, metadata);
		}

	}

}
