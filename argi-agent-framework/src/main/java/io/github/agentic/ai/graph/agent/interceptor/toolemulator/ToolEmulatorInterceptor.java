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
package io.github.agentic.ai.graph.agent.interceptor.toolemulator;

import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool interceptor that emulates specified tools using an LLM instead of executing them.
 *
 * This interceptor allows selective emulation of tools for testing purposes. By default,
 * all tools are emulated. Set {@code emulateAllTools(false)} to emulate only the
 * explicitly configured tool names.
 *
 * Example: // Emulate all tools (default behavior) ToolEmulatorInterceptor emulator =
 * ToolEmulatorInterceptor.builder() .chatClient(chatClient) .build();
 *
 * // Emulate specific tools by name ToolEmulatorInterceptor emulator =
 * ToolEmulatorInterceptor.builder() .chatClient(chatClient) .emulateAllTools(false)
 * .addTool("get_weather") .addTool("get_user_location") .build();
 *
 * // Emulate only an expensive tool ToolEmulatorInterceptor emulator =
 * ToolEmulatorInterceptor.builder() .chatClient(chatClient) .emulateAllTools(false) //
 * Only emulate specified tools .addTool("expensive_api") .build();
 */
public class ToolEmulatorInterceptor extends ToolInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ToolEmulatorInterceptor.class);

	private final ChatClient chatClient;

	private final boolean emulateAll;

	private final Set<String> toolsToEmulate;

	private final String promptTemplate;

	private ToolEmulatorInterceptor(Builder builder) {
		this.chatClient = builder.chatClient;
		this.emulateAll = builder.emulateAll;
		this.toolsToEmulate = new HashSet<>(builder.toolsToEmulate);
		this.promptTemplate = builder.promptTemplate;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String getName() {
		return "ToolEmulator";
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		String toolName = request.getToolName();

		// Check if this tool should be emulated
		boolean shouldEmulate = emulateAll || toolsToEmulate.contains(toolName);

		if (!shouldEmulate) {
			// Let it execute normally by calling the handler
			return handler.call(request);
		}

		log.info("Emulating tool call: {}", toolName);

		try {
			// Build prompt for emulator LLM
			String prompt = String.format(promptTemplate, toolName, "No description available", // TODO:
																								// Tool
																								// descriptions
																								// not
																								// available
																								// in
																								// ToolCallRequest
					request.getArguments());

			// Get emulated response from LLM
			String emulatedResult = chatClient.callBlocking(ChatRequest.of(List.of(ChatMessage.user(prompt)))).text();
			if (emulatedResult.isBlank()) {
				throw new IllegalStateException("Tool emulator returned an empty result");
			}

			log.debug("Emulated tool '{}' returned: {}", toolName,
					emulatedResult.length() > 100 ? emulatedResult.substring(0, 100) + "..." : emulatedResult);

			// Short-circuit: return emulated result without executing real tool
			// An emulated result is a successful outcome: it must carry the success status
		// so downstream consumers (e.g. retry interceptors) do not treat it as a failure.
		return ToolCallResponse.success(request.getToolCallId(), toolName, emulatedResult);

		}
		catch (Exception e) {
			log.error("Failed to emulate tool call for: {}", toolName, e);
			// Fall back to actual execution on emulation failure
			return handler.call(request);
		}
	}

	public static class Builder {

		private final Set<String> toolsToEmulate = new HashSet<>();

		private ChatClient chatClient;

		private boolean emulateAll = true; // Default: emulate all tools

		private String promptTemplate = """
				You are emulating a tool call for testing purposes.

				Tool: %s
				Description: %s
				Arguments: %s

				Generate a realistic response that this tool would return given these arguments.
				Return ONLY the tool's output, no explanation or preamble.
				Introduce variation into your responses.
				""";

		/**
		 * Set the provider-neutral chat client used for emulation. Required.
		 */
		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		/**
		 * Add a tool name to emulate. If emulateAllTools is true, this is ignored.
		 */
		public Builder addTool(String toolName) {
			this.toolsToEmulate.add(toolName);
			return this;
		}

		/**
		 * Add multiple tool names to emulate.
		 */
		public Builder addTools(Collection<String> toolNames) {
			this.toolsToEmulate.addAll(toolNames);
			return this;
		}

		/**
		 * Set whether to emulate all tools or only specified ones. Default is true
		 * (emulate all tools).
		 *
		 * Set to false to only emulate tools added via addTool().
		 */
		public Builder emulateAllTools(boolean emulateAll) {
			this.emulateAll = emulateAll;
			return this;
		}

		/**
		 * Set a custom prompt template for emulation. The template should accept 3 string
		 * format arguments: 1. Tool name 2. Tool description 3. Tool arguments (JSON)
		 */
		public Builder promptTemplate(String template) {
			this.promptTemplate = template;
			return this;
		}

		public ToolEmulatorInterceptor build() {
			if (chatClient == null) {
				throw new IllegalStateException("Emulator chatClient is required");
			}
			return new ToolEmulatorInterceptor(this);
		}

	}

}
