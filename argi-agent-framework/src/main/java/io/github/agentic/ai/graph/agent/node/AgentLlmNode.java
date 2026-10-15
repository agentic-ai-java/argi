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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.Usage;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.MessageProvenance;
import io.github.agentic.ai.api.chat.ModelContextMerger;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.NodeActionWithConfig;
import io.github.agentic.ai.graph.serializer.AgentInstructionMessage;
import io.github.agentic.ai.graph.utils.TypeRef;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.InterceptorChain;
import io.github.agentic.ai.graph.agent.interceptor.StreamingModelInterceptor;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.graph.utils.PromptTemplate;

import org.springframework.util.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import reactor.core.publisher.Flux;

import static io.github.agentic.ai.graph.RunnableConfig.AGENT_MODEL_NAME;
import static io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver.THREAD_ID_DEFAULT;

public class AgentLlmNode implements NodeActionWithConfig {

	private static final Logger logger = LoggerFactory.getLogger(AgentLlmNode.class);

	public static final String MODEL_ITERATION_KEY = "_MODEL_ITERATION_";

	private String agentName;

	private final AgentToolBindingStore toolBindings;

	private List<ModelInterceptor> modelInterceptors = new ArrayList<>();

	private List<StreamingModelInterceptor> streamingInterceptors = new ArrayList<>();

	private String outputKey;

	private String outputSchema;

	private ChatClient chatClient;

	private final io.github.agentic.ai.api.tool.ToolRegistry toolRegistry;

	private String systemPrompt;

	private BiFunction<String, Map<String, Object>, String> templateRenderer;

	private String instruction;

	private GenerationOptions generationOptions;

	private boolean enableReasoningLog;

	private final boolean throwOnModelError;

	public AgentLlmNode(Builder builder) {
		this.agentName = builder.agentName;
		this.outputKey = builder.outputKey;
		this.outputSchema = builder.outputSchema;
		this.systemPrompt = builder.systemPrompt;
		this.instruction = builder.instruction;
		this.templateRenderer = builder.templateRenderer;
		this.toolBindings = builder.toolBindingStore;
		if (builder.modelInterceptors != null) {
			this.modelInterceptors = builder.modelInterceptors;
		}
		if (builder.streamingInterceptors != null) {
			this.streamingInterceptors = builder.streamingInterceptors;
		}
		this.chatClient = builder.chatClient;
		this.toolRegistry = builder.toolRegistry;
		this.generationOptions = builder.generationOptions;
		this.enableReasoningLog = builder.enableReasoningLog;
		this.throwOnModelError = builder.throwOnModelError;
	}

	public static Builder builder() {
		return new Builder();
	}

	public void setModelInterceptors(List<ModelInterceptor> modelInterceptors) {
		this.modelInterceptors = modelInterceptors;
	}

	public void setStreamingInterceptors(List<StreamingModelInterceptor> streamingInterceptors) {
		this.streamingInterceptors = streamingInterceptors;
	}

	public void setInstruction(String instruction) {
		this.instruction = instruction;
	}

	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	@Override
	public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
		toolBindings.clear(config);
		if (enableReasoningLog && logger.isDebugEnabled()) {
			logger.debug("[ThreadId {}] Agent {} start reasoning.", config.threadId().orElse(THREAD_ID_DEFAULT),
					agentName);
		}

		// Check and manage iteration counter
		final AtomicInteger iterations;
		if (!config.context().containsKey(MODEL_ITERATION_KEY)) {
			iterations = new AtomicInteger(0);
			config.context().put(MODEL_ITERATION_KEY, iterations);
		}
		else {
			iterations = (AtomicInteger) config.context().get(MODEL_ITERATION_KEY);
			iterations.incrementAndGet();
		}

		// Check and manage messages
		List<ChatMessage> messages = new ArrayList<>();
		if (state.value("messages").map(value -> ((List<?>) value).isEmpty()).orElse(true)) {
			// try with "input" key, which is more commonly used in graph input when agent
			// is used as a node.
			if (state.value("input").isPresent()) {
				messages.add(MessageProvenance.forEntryUser(ChatMessage.user(state.value("input").get().toString())));
			}
			else {
				throw new IllegalArgumentException("Either 'instruction' or 'includeContents' must be set for Agent.");
			}
		}
		else {
			messages = new ArrayList<>((List<ChatMessage>) state.value("messages").get());
			messages.replaceAll(message -> message.role() == ChatRole.USER ? MessageProvenance.forEntryUser(message)
					: MessageProvenance.canonicalize(message));
		}

		augmentUserMessage(messages, outputSchema);
		renderTemplatedUserMessage(messages, state.data(), config.metadata());

		// Create ModelRequest; include state in context so interceptors (e.g. handoffs
		// step-config) can read it. Authority keys are accepted only from runtime
		// metadata so ordinary state payloads cannot forge authorization or secrets.
		Map<String, Object> contextMap = ModelContextMerger.merge(state.data(),
				config.metadata().orElse(Map.of()));
		ModelRequest.Builder requestBuilder = ModelRequest.builder()
			.messages(messages)
			.options(this.generationOptions)
			.toolRegistry(configuredRegistry())
			.context(contextMap);

		// Extract tool names and descriptions from toolCallbacks and pass them to
		// ModelRequest
		if (!toolRegistry.definitions().isEmpty()) {
			Map<String, String> toolDescriptions = new HashMap<>();
			for (var definition : toolRegistry.definitions()) {
				toolDescriptions.put(definition.name(), definition.description());
			}
			requestBuilder.toolDescriptions(toolDescriptions);
		}

		if (StringUtils.hasLength(this.systemPrompt)) {
			requestBuilder.systemMessage(MessageProvenance.appInstruction(ChatMessage.system(this.systemPrompt)));
		}

		ModelRequest modelRequest = requestBuilder.build();

		// add streaming support
		boolean stream = config.metadata("_stream_", new TypeRef<Boolean>() {
		}).orElse(true);
		if (stream) {
			// Each cold subscription owns its selection, including any retry
			// replacements.
			Flux<ChatResponse> output = Flux.defer(() -> {
				RunnableConfig subscriptionConfig = RunnableConfig.builder(config).build();
				toolBindings.clear(subscriptionConfig);
				ModelCallHandler baseHandler = request -> {
					try {
						if (enableReasoningLog && logger.isDebugEnabled()) {
							String prompt = request.getSystemMessage() != null ? request.getSystemMessage().text() : "";
							logger.debug("[ThreadId {}] Agent {} reasoning with system prompt: {}",
									config.threadId().orElse(THREAD_ID_DEFAULT), agentName, prompt);
						}
						ChatClient selectedClient = request.getChatClient() != null ? request.getChatClient()
								: chatClient;
						if (!selectedClient.capabilities().streaming()) {
							return ModelResponse
								.of(selectedClient.callBlocking(buildChatRequest(request, subscriptionConfig)));
						}
						ChatRequest chatRequest = buildChatRequest(request, subscriptionConfig);
						Flux<io.github.agentic.ai.api.chat.ChatEvent> chatResponseFlux = Flux
							.defer(() -> reactor.adapter.JdkFlowAdapter
								.flowPublisherToFlux(selectedClient.stream(chatRequest)));
						if (enableReasoningLog) {
							chatResponseFlux = chatResponseFlux
								.doOnNext(event -> logger.info("[ThreadId {}] Agent {} reasoning round {} event: {}",
										config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get(),
										event));
						}
						return ModelResponse.stream(InterceptorChain.applyStreamingInterceptors(streamingInterceptors,
								reactor.adapter.JdkFlowAdapter.publisherToFlowPublisher(chatResponseFlux), request));
					}
					catch (Exception e) {
						logger.error("Exception during streaming model call: ", e);
						throw e;
					}
				};
				ModelCallHandler chainedHandler = InterceptorChain.chainModelInterceptors(modelInterceptors,
						baseHandler);
				ModelRequest subscriptionRequest = ModelRequest.builder(modelRequest)
					.context(new HashMap<>(modelRequest.getContext()))
					.build();
				return streamResponse(invokeModelChain(chainedHandler, subscriptionRequest), subscriptionConfig);
			});
			return Map.of(StringUtils.hasLength(this.outputKey) ? this.outputKey : "messages", output);
		}
		else {
			// Create base handler that actually calls the model
			ModelCallHandler baseHandler = request -> {
				try {
					if (enableReasoningLog) {
						String systemPrompt = request.getSystemMessage() != null ? request.getSystemMessage().text()
								: "";
						logger.info("[ThreadId {}] Agent {} reasoning round {} with system prompt: {}.",
								config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get(), systemPrompt);
					}

					ChatClient selectedClient = request.getChatClient() != null ? request.getChatClient() : chatClient;
					var response = selectedClient.callBlocking(buildChatRequest(request, config));

					if (enableReasoningLog) {
						logger.info("[ThreadId {}] Agent {} reasoning round {} returned: {}.",
								config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get(), response);
					}

					return ModelResponse.of(response);
				}
				catch (Exception e) {
					logger.error("Exception during invoking model call: ", e);
					throw e;
				}
			};

			// Chain interceptors if any
			ModelCallHandler chainedHandler = InterceptorChain.chainModelInterceptors(modelInterceptors, baseHandler);

			if (enableReasoningLog) {
				logger.info("[ThreadId {}] Agent {} reasoning round {} model chain has started.",
						config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get());
			}

			// Execute the chained handler
			ModelResponse modelResponse = invokeModelChain(chainedHandler, modelRequest);
			Usage tokenUsage = modelResponse.isStreaming() ? Usage.unknown() : modelResponse.getChatResponse().usage();
			Object output = stateResponse(modelResponse, config);

			Map<String, Object> updatedState = new HashMap<>();
			updatedState.put(io.github.agentic.ai.graph.NodeOutput.TOKEN_USAGE_KEY, tokenUsage);
			updatedState.put("messages", output);
			if (StringUtils.hasLength(this.outputKey)) {
				updatedState.put(this.outputKey, output);
			}

			return updatedState;
		}
	}

	private Object stateResponse(ModelResponse response, RunnableConfig config) {
		AgentToolBindings configured = new AgentToolBindings(toolRegistry);
		return response.isStreaming() ? streamResponse(response, config)
				: toolBindings.attach(AgentChatProtocol.response(response.getChatResponse(), null).messages().get(0),
						toolBindings.current(config), configured);
	}

	private Flux<ChatResponse> streamResponse(ModelResponse response, RunnableConfig config) {
		AgentToolBindings configured = new AgentToolBindings(toolRegistry);
		var chunks = response.isStreaming() ? AgentChatProtocol.stream(response.getStream())
				: Flux.just(AgentChatProtocol.response(response.getChatResponse(), null));
		return chunks.map(chunk -> {
			// Streaming retries can replace the selection after the publisher is
			// assembled.
			var output = toolBindings.attach(chunk.messages().get(0), toolBindings.current(config), configured);
			return new ChatResponse(chunk.id(), chunk.model(), List.of(output), chunk.toolCalls(), chunk.finishReason(),
					chunk.usage(), chunk.metadata());
		});
	}

	private List<ChatMessage> appendSystemPromptIfNeeded(ModelRequest modelRequest) {
		// Create a new list and copy messages from modelRequest
		List<ChatMessage> messages = new ArrayList<>(modelRequest.getMessages());

		// FIXME, there should have only one ChatMessage.
		// Users may have added SystemMessages in hooks or somewhere else, simply remove
		// will cause unexpected agent behaviour.
		// messages.removeIf(message -> message instanceof ChatMessage && ((ChatMessage)
		// message).role() == ChatRole.SYSTEM);

		// Add the ChatMessage from modelRequest at the beginning if present
		if (modelRequest.getSystemMessage() != null) {
			messages.add(0, modelRequest.getSystemMessage());
		}

		long systemMessageCount = messages.stream().filter(message -> message.role() == ChatRole.SYSTEM).count();

		if (systemMessageCount > 2) {
			logger.warn(
					"Detected {} SystemMessages in the message list. There should typically be only one ChatMessage. "
							+ "Multiple SystemMessages may cause unexpected behavior or model confusion.",
					systemMessageCount);
		}

		return messages;
	}

	private String renderPromptTemplate(String prompt, Map<String, Object> params) {
		return templateRenderer != null ? java.util.Objects.requireNonNull(templateRenderer.apply(prompt, params),
				"Template renderer must not return null") : PromptTemplate.of(prompt).render(params);
	}

	public void augmentUserMessage(List<ChatMessage> messages, String outputSchema) {
		if (!StringUtils.hasText(outputSchema)) {
			return;
		}

		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage message = messages.get(i);
			if (message.role() == ChatRole.USER) {
				ChatMessage userMessage = message;
				String schema = AgentInstructionMessage.isTemplate(message)
						? outputSchema.replace("{", "\\{").replace("}", "\\}") : outputSchema;
				// Check if outputSchema is already present to avoid duplication
				if (!userMessage.text().contains(schema)) {
					messages.set(i, userMessage.withText(userMessage.text() + System.lineSeparator() + schema));
				}
				break;
			}
			if (i == 0) {
				messages.add(ChatMessage.user(outputSchema));
			}
		}
	}

	public void renderTemplatedUserMessage(List<ChatMessage> messages, Map<String, Object> params,
			Optional<Map<String, Object>> metadata) {
		// Process params to create a new Map
		Map<String, Object> processedParams = new HashMap<>();
		if (params != null) {
			for (Map.Entry<String, Object> entry : params.entrySet()) {
				String key = entry.getKey();
				Object value = entry.getValue();

				// Exclude key "messages"
				if ("messages".equals(key)) {
					continue;
				}

				// Exclude List type values
				if (value instanceof List) {
					continue;
				}

				// Convert ChatMessage type to String using getText()
				if (value instanceof ChatMessage message) {
					processedParams.put(key, message.text());
				}
				else {
					// Keep other types as is
					processedParams.put(key, value);
				}
			}
		}

		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage message = messages.get(i);
			if (AgentInstructionMessage.isTemplate(message) && !AgentInstructionMessage.isRendered(message)) {
				ChatMessage newMessage = AgentInstructionMessage.rendered(message,
						renderPromptTemplate(message.text(), processedParams));
				messages.set(i, newMessage);
				break;
			}
		}
	}

	private ModelResponse invokeModelChain(ModelCallHandler handler, ModelRequest request) {
		try {
			return handler.call(request);
		}
		catch (RuntimeException failure) {
			// Interceptors must see failures before the optional user-facing error
			// message is built.
			if (throwOnModelError || failure instanceof java.util.concurrent.CancellationException
					|| Thread.currentThread().isInterrupted()) {
				throw failure;
			}
			return ModelResponse
				.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Exception: " + failure.getMessage()));
		}
	}

	private io.github.agentic.ai.api.tool.ToolRegistry configuredRegistry() {
		return toolRegistry;
	}

	private ChatRequest buildChatRequest(ModelRequest modelRequest, RunnableConfig config) {
		List<ChatMessage> messages = appendSystemPromptIfNeeded(modelRequest);

		// Static bindings win duplicate dynamic disclosures. Explicit selection filters
		// both registries.
		var available = new java.util.LinkedHashMap<String, io.github.agentic.ai.api.tool.Tool>();
		for (var registry : List.of(modelRequest.getToolRegistry(), modelRequest.getDynamicToolRegistry())) {
			for (var definition : registry.definitions()) {
				available.putIfAbsent(definition.name(), registry.find(definition.name()).orElseThrow());
			}
		}
		var neutral = new ArrayList<io.github.agentic.ai.api.tool.Tool>();
		var definitions = new ArrayList<io.github.agentic.ai.api.chat.ToolDefinition>();
		for (var tool : available.values()) {
			if (modelRequest.hasToolSelection() && !modelRequest.getTools().contains(tool.definition().name())) {
				continue;
			}
			definitions.add(tool.definition());
			neutral.add(tool);
		}
		toolBindings.publish(config, new AgentToolBindings(new io.github.agentic.ai.api.tool.ToolRegistry(neutral)));
		return new ChatRequest(messages, definitions, modelRequest.getOptions(), modelRequest.getAttributes());
	}

	public String getName() {
		return AGENT_MODEL_NAME;
	}

	public static class Builder {

		private io.github.agentic.ai.api.tool.ToolRegistry toolRegistry = new io.github.agentic.ai.api.tool.ToolRegistry(
				List.of());

		public Builder toolRegistry(io.github.agentic.ai.api.tool.ToolRegistry toolRegistry) {
			this.toolRegistry = java.util.Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
			return this;
		}

		private String agentName;

		private String outputKey;

		private String outputSchema;

		private String systemPrompt;

		private BiFunction<String, Map<String, Object>, String> templateRenderer;

		private ChatClient chatClient;

		private AgentToolBindingStore toolBindingStore = new AgentToolBindingStore();

		private List<ModelInterceptor> modelInterceptors;

		private List<StreamingModelInterceptor> streamingInterceptors;

		private String instruction;

		private boolean enableReasoningLog;

		private boolean throwOnModelError;

		private GenerationOptions generationOptions;

		public Builder agentName(String agentName) {
			this.agentName = agentName;
			return this;
		}

		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		public Builder outputSchema(String outputSchema) {
			this.outputSchema = outputSchema;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		/** Set a thread-safe, provider-neutral instruction template renderer. */
		public Builder templateRenderer(BiFunction<String, Map<String, Object>, String> templateRenderer) {
			this.templateRenderer = templateRenderer;
			return this;
		}

		public Builder toolBindingStore(AgentToolBindingStore toolBindingStore) {
			this.toolBindingStore = java.util.Objects.requireNonNull(toolBindingStore,
					"toolBindingStore must not be null");
			return this;
		}

		public Builder modelInterceptors(List<ModelInterceptor> modelInterceptors) {
			this.modelInterceptors = modelInterceptors;
			return this;
		}

		public Builder streamingInterceptors(List<StreamingModelInterceptor> streamingInterceptors) {
			this.streamingInterceptors = streamingInterceptors;
			return this;
		}

		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder instruction(String instruction) {
			this.instruction = instruction;
			return this;
		}

		public Builder enableReasoningLog(boolean enableReasoningLog) {
			this.enableReasoningLog = enableReasoningLog;
			return this;
		}

		/**
		 * Whether to propagate caught model exceptions instead of converting them to
		 * assistant messages. Defaults to {@code false}. Errors emitted by a streaming
		 * publisher continue to propagate regardless of this setting.
		 * @param throwOnModelError whether to propagate caught model exceptions
		 * @return this builder
		 */
		public Builder throwOnModelError(boolean throwOnModelError) {
			this.throwOnModelError = throwOnModelError;
			return this;
		}

		public Builder generationOptions(GenerationOptions generationOptions) {
			this.generationOptions = generationOptions;
			return this;
		}

		public AgentLlmNode build() {
			return new AgentLlmNode(this);
		}

	}

}
