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
package io.github.agentic.ai.agent.interceptor;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Request object for model calls. Contains all information needed to make a model
 * invocation.
 */
public class ModelRequest {

	private final ChatMessage systemMessage;

	private final Map<String, Object> context;

	private final Map<String, Object> attributes;

	private final List<ChatMessage> messages;

	private final GenerationOptions options;

	private final ChatClient chatClient;

	private final ToolRegistry toolRegistry;

	// An explicit empty selection disables tools; an absent selection uses every binding.
	private final List<String> tools;

	private final boolean toolSelectionSpecified;

	private final ToolRegistry dynamicToolRegistry;

	// tool descriptions for tool selection, mapping tool name to description.
	private final Map<String, String> toolDescriptions;

	// Typed runtime trust boundary; never read from or written to the loosely-typed
	// context map.
	private final TrustedExecutionContext trustedExecutionContext;

	// The guard of the run that owns this request. It carries the liveness and the
	// deadline of the run to the interceptors, so that a retry sequence can be
	// bounded by the run itself. It never leaves the process: the request attributes
	// are passed to the provider and must stay free of runtime objects.
	private final ExecutionGuard executionGuard;

	public ModelRequest(ChatMessage systemMessage, List<ChatMessage> messages, GenerationOptions options,
			ChatClient chatClient, ToolRegistry toolRegistry, List<String> tools, boolean toolSelectionSpecified,
			ToolRegistry dynamicToolRegistry, Map<String, String> toolDescriptions, Map<String, Object> context,
			Map<String, Object> attributes) {
		this(systemMessage, messages, options, chatClient, toolRegistry, tools, toolSelectionSpecified,
				dynamicToolRegistry, toolDescriptions, context, attributes, null);
	}

	public ModelRequest(ChatMessage systemMessage, List<ChatMessage> messages, GenerationOptions options,
			ChatClient chatClient, ToolRegistry toolRegistry, List<String> tools, boolean toolSelectionSpecified,
			ToolRegistry dynamicToolRegistry, Map<String, String> toolDescriptions, Map<String, Object> context,
			Map<String, Object> attributes, TrustedExecutionContext trustedExecutionContext) {
		this(systemMessage, messages, options, chatClient, toolRegistry, tools, toolSelectionSpecified,
				dynamicToolRegistry, toolDescriptions, context, attributes, trustedExecutionContext, null);
	}

	public ModelRequest(ChatMessage systemMessage, List<ChatMessage> messages, GenerationOptions options,
			ChatClient chatClient, ToolRegistry toolRegistry, List<String> tools, boolean toolSelectionSpecified,
			ToolRegistry dynamicToolRegistry, Map<String, String> toolDescriptions, Map<String, Object> context,
			Map<String, Object> attributes, TrustedExecutionContext trustedExecutionContext,
			ExecutionGuard executionGuard) {
		if (systemMessage != null && systemMessage.role() != ChatRole.SYSTEM) {
			throw new IllegalArgumentException("systemMessage must have the SYSTEM role");
		}
		this.systemMessage = systemMessage;
		this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(List.copyOf(messages));
		this.options = options;
		this.chatClient = chatClient;
		this.toolRegistry = java.util.Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
		this.tools = List.copyOf(tools);
		this.toolSelectionSpecified = toolSelectionSpecified;
		this.dynamicToolRegistry = java.util.Objects.requireNonNull(dynamicToolRegistry,
				"dynamicToolRegistry must not be null");
		this.toolDescriptions = java.util.Collections.unmodifiableMap(new HashMap<>(toolDescriptions));
		this.context = new HashMap<>(context);
		this.attributes = Map.copyOf(attributes);
		this.trustedExecutionContext = trustedExecutionContext;
		this.executionGuard = executionGuard;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static Builder builder(ModelRequest request) {
		Builder copy = new Builder().systemMessage(request.systemMessage)
			.messages(request.messages)
			.options(request.options)
			.chatClient(request.chatClient)
			.toolRegistry(request.toolRegistry)
			.tools(request.tools)
			.dynamicToolRegistry(request.dynamicToolRegistry)
			.toolDescriptions(request.toolDescriptions)
			.context(request.context)
			.attributes(request.attributes)
			.trustedExecutionContext(request.trustedExecutionContext)
			.executionGuard(request.executionGuard);
		copy.toolSelectionSpecified = request.toolSelectionSpecified;
		return copy;
	}

	public List<ChatMessage> getMessages() {
		return messages;
	}

	public ChatMessage getSystemMessage() {
		return systemMessage;
	}

	public GenerationOptions getOptions() {
		return options;
	}

	/** Optional client override, used by interceptors such as model fallback. */
	public ChatClient getChatClient() {
		return chatClient;
	}

	/** Neutral bindings available for declaration and filtering on this invocation. */
	public ToolRegistry getToolRegistry() {
		return toolRegistry;
	}

	/**
	 * Explicit selection, or all currently disclosed bindings when no selection was
	 * supplied.
	 */
	public List<String> getTools() {
		if (toolSelectionSpecified) {
			return tools;
		}
		return java.util.stream.Stream
			.concat(toolRegistry.definitions().stream(), dynamicToolRegistry.definitions().stream())
			.map(io.github.agentic.ai.api.chat.ToolDefinition::name)
			.distinct()
			.toList();
	}

	public boolean hasToolSelection() {
		return toolSelectionSpecified;
	}

	/** Invocation-local tools disclosed by skills or other interceptors. */
	public ToolRegistry getDynamicToolRegistry() {
		return dynamicToolRegistry;
	}

	/**
	 * Trusted execution context of the run that issued this model call, when the
	 * application supplied one through its authenticated execution boundary. It cannot
	 * be set implicitly from application inputs or runtime metadata maps.
	 */
	public Optional<TrustedExecutionContext> getTrustedExecutionContext() {
		return Optional.ofNullable(trustedExecutionContext);
	}

	/**
	 * The guard of the run that issued this model call, when the run carries one. The
	 * guard reports the liveness and the deadline of the run, so that an interceptor
	 * that repeats the call can stop the sequence when the run is cancelled or out of
	 * time. It is never passed to the provider.
	 */
	public Optional<ExecutionGuard> getExecutionGuard() {
		return Optional.ofNullable(executionGuard);
	}

	public Map<String, String> getToolDescriptions() {
		return toolDescriptions;
	}

	public Map<String, Object> getContext() {
		return context;
	}

	/**
	 * Explicit native invocation attributes, separate from agent state and interceptor
	 * context.
	 */
	public Map<String, Object> getAttributes() {
		return attributes;
	}

	public static class Builder {

		private ChatMessage systemMessage;

		private List<ChatMessage> messages = List.of();

		private GenerationOptions options = GenerationOptions.defaults();

		private ChatClient chatClient;

		private ToolRegistry toolRegistry = new ToolRegistry(List.of());

		private List<String> tools = List.of();

		private boolean toolSelectionSpecified;

		private ToolRegistry dynamicToolRegistry = new ToolRegistry(List.of());

		private Map<String, String> toolDescriptions = new HashMap<>();

		private Map<String, Object> context = new HashMap<>();

		private Map<String, Object> attributes = Map.of();

		private TrustedExecutionContext trustedExecutionContext;

		private ExecutionGuard executionGuard;

		public Builder systemMessage(ChatMessage systemMessage) {
			this.systemMessage = systemMessage;
			return this;
		}

		public Builder messages(List<ChatMessage> messages) {
			this.messages = messages;
			return this;
		}

		public Builder options(GenerationOptions options) {
			this.options = options;
			return this;
		}

		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder toolRegistry(ToolRegistry toolRegistry) {
			this.toolRegistry = java.util.Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
			return this;
		}

		public Builder tools(List<String> tools) {
			if (tools != null) {
				this.tools = new ArrayList<>(tools);
				this.toolSelectionSpecified = true;
			}
			return this;
		}

		public Builder dynamicToolRegistry(ToolRegistry dynamicToolRegistry) {
			this.dynamicToolRegistry = java.util.Objects.requireNonNull(dynamicToolRegistry,
					"dynamicToolRegistry must not be null");
			return this;
		}

		public Builder toolDescriptions(Map<String, String> toolDescriptions) {
			if (toolDescriptions != null) {
				this.toolDescriptions = new HashMap<>(toolDescriptions);
			}
			return this;
		}

		public Builder context(Map<String, Object> context) {
			if (context != null) {
				this.context = new HashMap<>(context);
			}
			return this;
		}

		/**
		 * Sets opt-in invocation attributes. Adapter support must be checked by the
		 * caller.
		 */
		public Builder attributes(Map<String, Object> attributes) {
			this.attributes = Map.copyOf(attributes);
			return this;
		}

		/** Sets the trusted execution context propagated from the run configuration. */
		public Builder trustedExecutionContext(TrustedExecutionContext trustedExecutionContext) {
			this.trustedExecutionContext = trustedExecutionContext;
			return this;
		}

		/**
		 * Sets the guard of the run that owns this model call. The guard is propagated
		 * by the engine and stays inside the process.
		 */
		public Builder executionGuard(ExecutionGuard executionGuard) {
			this.executionGuard = executionGuard;
			return this;
		}

		public ModelRequest build() {
			return new ModelRequest(systemMessage, messages, options, chatClient, toolRegistry, tools,
					toolSelectionSpecified, dynamicToolRegistry, toolDescriptions, context, attributes,
					trustedExecutionContext, executionGuard);
		}

	}

}
