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
package io.github.agentic.ai.graph.agent.interceptor.toolselection;

import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;

import io.github.agentic.ai.api.chat.ChatRole;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses an LLM to select relevant tools before calling the main model.
 *
 * When an agent has many tools available, this interceptor filters them down to only the
 * most relevant ones for the user's query. This reduces token usage and helps the main
 * model focus on the right tools.
 *
 * Example: ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
 * .chatClient(selectionClient) .maxTools(3) .build();
 */
public class ToolSelectionInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ToolSelectionInterceptor.class);

	private static final String DEFAULT_SYSTEM_PROMPT = "Your goal is to select the most relevant tools for answering the user's query.";

	private final ChatClient chatClient;

	private final String systemPrompt;

	private final Integer maxTools;

	private final Set<String> alwaysInclude;

	private final ObjectMapper objectMapper;

	private ToolSelectionInterceptor(Builder builder) {
		this.chatClient = builder.chatClient;
		this.systemPrompt = builder.systemPrompt;
		this.maxTools = builder.maxTools;
		this.alwaysInclude = builder.alwaysInclude != null ? new HashSet<>(builder.alwaysInclude) : new HashSet<>();
		this.objectMapper = new ObjectMapper();
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		List<String> availableTools = request.getTools();

		// If no tools or already within limit, skip selection
		if (availableTools == null || availableTools.isEmpty()
				|| (maxTools != null && availableTools.size() <= maxTools)) {
			return handler.call(request);
		}

		// Find the last user message
		String lastUserQuery = findLastUserMessage(request.getMessages());
		if (lastUserQuery == null) {
			log.debug("No user message found, skipping tool selection");
			return handler.call(request);
		}

		// Perform tool selection
		Set<String> selectedToolNames = selectTools(availableTools, lastUserQuery, request.getToolDescriptions());

		log.info("Selected {} tools from {} available: {}", selectedToolNames.size(), availableTools.size(),
				selectedToolNames);

		// Filter tools based on selection
		List<String> filteredTools = availableTools.stream()
			.filter(selectedToolNames::contains)
			.collect(Collectors.toList());

		// Create new request with filtered tools
		ModelRequest filteredRequest = ModelRequest.builder(request).tools(filteredTools).build();

		return handler.call(filteredRequest);
	}

	private String findLastUserMessage(List<ChatMessage> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage msg = messages.get(i);
			if (msg.role() == ChatRole.USER) {
				return msg.text();
			}
		}
		return null;
	}

	private Set<String> selectTools(List<String> toolNames, String userQuery, Map<String, String> toolDescriptions) {
		try {
			// Build tool list for prompt with descriptions
			StringBuilder toolList = new StringBuilder();
			for (String toolName : toolNames) {
				toolList.append("- ").append(toolName);
				if (toolDescriptions != null) {
					String description = toolDescriptions.get(toolName);
					if (description != null && !description.isEmpty()) {
						toolList.append(": ").append(description);
					}
				}
				toolList.append("\n");
			}

			String maxToolsInstruction = maxTools != null ? "\nIMPORTANT: List the tool names in order of relevance. "
					+ "Select at most " + maxTools + " tools." : "";

			// Create selection prompt
			List<ChatMessage> selectionMessages = List
				.of(ChatMessage.system(systemPrompt + maxToolsInstruction), ChatMessage.user("Available tools:\n"
						+ toolList + "\nUser query: " + userQuery
						+ "\n\nRespond with a JSON object containing a 'tools' array with the selected tool names: {\"tools\": [\"tool1\", \"tool2\"]}"));

			String responseText = chatClient.callBlocking(ChatRequest.of(selectionMessages)).text();

			// Parse JSON response
			Set<String> selected = new LinkedHashSet<>();
			for (String toolName : toolNames) {
				if (alwaysInclude.contains(toolName)) {
					selected.add(toolName);
				}
			}
			for (String toolName : parseToolSelection(responseText)) {
				if (maxTools != null && selected.size() >= maxTools) {
					break;
				}
				if (toolNames.contains(toolName)) {
					selected.add(toolName);
				}
			}

			return selected;

		}
		catch (Exception e) {
			log.warn("Tool selection failed, using all tools: {}", e.getMessage());
			return new HashSet<>(toolNames);
		}
	}

	private Set<String> parseToolSelection(String responseText) throws java.io.IOException {
		JsonNode response = objectMapper.reader()
			.with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.readTree(responseText);
		JsonNode tools = response == null ? null : response.get("tools");
		if (tools == null || !tools.isArray()) {
			throw new IllegalArgumentException("Tool selection must contain a tools array");
		}
		Set<String> selected = new LinkedHashSet<>();
		for (JsonNode tool : tools) {
			if (!tool.isTextual() || tool.textValue().isBlank()) {
				throw new IllegalArgumentException("Selected tool names must be non-blank strings");
			}
			selected.add(tool.textValue());
		}
		return selected;
	}

	@Override
	public String getName() {
		return "ToolSelection";
	}

	public static class Builder {

		private ChatClient chatClient;

		private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

		private Integer maxTools;

		private Set<String> alwaysInclude;

		/** Set the provider-neutral chat client used to select tools. */
		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		/**
		 * Limits the total selected tools unless available always-include tools alone
		 * exceed the limit. Mandatory tools take precedence in that case.
		 * @param maxTools maximum total selected tools; mandatory tools count toward this
		 * limit except mandatory-only overflow
		 * @return this builder
		 */
		public Builder maxTools(int maxTools) {
			if (maxTools <= 0) {
				throw new IllegalArgumentException("maxTools must be > 0");
			}
			this.maxTools = maxTools;
			return this;
		}

		public Builder alwaysInclude(Set<String> alwaysInclude) {
			this.alwaysInclude = alwaysInclude;
			return this;
		}

		public Builder alwaysInclude(String... toolNames) {
			this.alwaysInclude = new HashSet<>(Arrays.asList(toolNames));
			return this;
		}

		public ToolSelectionInterceptor build() {
			if (chatClient == null) {
				throw new IllegalStateException("Tool selection chatClient is required");
			}
			return new ToolSelectionInterceptor(this);
		}

	}

}
