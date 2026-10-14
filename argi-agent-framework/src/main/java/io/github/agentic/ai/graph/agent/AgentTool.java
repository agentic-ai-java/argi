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
package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.tools.ToolContextConstants;
import io.github.agentic.ai.graph.exception.GraphRunnerException;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;

import io.github.agentic.ai.graph.agent.internal.JsonCodec;
import io.github.agentic.ai.graph.agent.tool.ToolSchema;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Exposes an ARGI ReactAgent as a framework-neutral tool. The parent runtime executes the
 * tool; no native framework agent loop is installed.
 */
public class AgentTool {

	private static final JsonCodec jsonHelper = new JsonCodec();

	/**
	 * Create a tool that forwards raw tool-call JSON to the agent executor.
	 * @param agent the ReactAgent instance
	 * @return neutral tool for the ReactAgent
	 */
	public static Tool create(ReactAgent agent) {
		Objects.requireNonNull(agent, "agent must not be null");
		// Get the original schema from inputSchema or inputType
		String originalSchema = StringUtils.hasLength(agent.getInputSchema()) ? agent.getInputSchema()
				: (agent.getInputType() != null) ? ToolSchema.forType(agent.getInputType()) : null;

		ToolDefinition definition = new ToolDefinition(agent.name(), agent.description(),
				wrapSchemaInInputParameter(originalSchema));
		return new AgentToolBinding(definition, new AgentToolExecutor(agent));
	}

	/**
	 * Wrap the original schema in an "input" parameter. This method handles both JSON
	 * Schema strings (including DRAFT_2020_12 format) and null cases.
	 * @param originalSchema the original schema string (may be null, or DRAFT_2020_12
	 * format)
	 * @return a wrapped schema with "input" parameter containing the original schema
	 */
	private static String wrapSchemaInInputParameter(String originalSchema) {
		try {
			// Parse the original schema if provided
			Map<String, Object> originalSchemaMap = null;
			if (StringUtils.hasLength(originalSchema)) {
				try {
					originalSchemaMap = jsonHelper.fromJsonToMap(originalSchema);
				}
				catch (Exception e) {
					// If parsing fails, treat as a simple string type
					originalSchemaMap = Map.of("type", "string");
				}
			}
			else {
				// Default to string type if no schema provided
				originalSchemaMap = Map.of("type", "string");
			}

			// Create the wrapped schema structure
			Map<String, Object> wrappedSchema = new HashMap<>();
			wrappedSchema.put("type", "object");

			Map<String, Object> properties = new HashMap<>();
			properties.put("input", originalSchemaMap);
			wrappedSchema.put("properties", properties);

			wrappedSchema.put("required", List.of("input"));

			// Convert to JSON string
			return jsonHelper.toJson(wrappedSchema);
		}
		catch (Exception e) {
			// Fallback: create a simple wrapper schema
			return String.format("""
					{
						"type": "object",
						"properties": {
							"input": {
								"type": "string"
							}
						},
						"required": ["input"]
					}
					""");
		}
	}

	private static class AgentToolBinding implements Tool {

		private final ToolDefinition toolDefinition;

		private final AgentToolExecutor executor;

		AgentToolBinding(ToolDefinition toolDefinition, AgentToolExecutor executor) {
			this.toolDefinition = toolDefinition;
			this.executor = executor;
		}

		@Override
		public ToolDefinition definition() {
			return toolDefinition;
		}

		@Override
		public CompletionStage<ToolResult> execute(ToolCall call, ToolContext toolContext) {
			Objects.requireNonNull(call, "call must not be null");
			Objects.requireNonNull(toolContext, "toolContext must not be null");
			// Child graph execution is blocking. Disposing the future interrupts its
			// worker.
			return Mono.fromCallable(() -> {
				ChatMessage result = executor.executeAgent(call.arguments(), toolContext);
				return new ToolResult(result.content(), false, result.metadata());
			}).subscribeOn(Schedulers.boundedElastic()).toFuture();
		}

	}

	/**
	 * Executor class for AgentTool. This class does not require @Tool annotation.
	 */
	public static class AgentToolExecutor {

		private final ReactAgent agent;

		public AgentToolExecutor(ReactAgent agent) {
			this.agent = Objects.requireNonNull(agent, "agent must not be null");
		}

		/**
		 * Execute the agent tool with the given input. Receives raw tool-call JSON and
		 * the invocation-local neutral context.
		 * @param input the input JSON string containing the "input" parameter
		 * @param toolContext the tool context containing state, config, etc.
		 * @return the complete assistant response from the agent
		 */
		public ChatMessage executeAgent(String input, ToolContext toolContext) {
			Objects.requireNonNull(toolContext, "toolContext must not be null");
			// Extract the actual input value from the wrapped JSON structure
			// The input parameter is wrapped as {"input": "actual_value"}
			String actualInput = extractInputValue(input);

			// Instruction is injected exactly once by the child agent's default
			// InstructionAgentHook (see issue #77); only the user input is added here.
			List<ChatMessage> messagesToAdd = List.of(ChatMessage.user(actualInput));

			Optional<OverAllState> resultState;
			Optional<RunnableConfig> parentConfigOpt = Optional
				.ofNullable(toolContext.attributes().get(ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY))
				.filter(RunnableConfig.class::isInstance)
				.map(RunnableConfig.class::cast);
			try {
				if (parentConfigOpt.isPresent()) {
					RunnableConfig parentConfig = parentConfigOpt.get();
					RunnableConfig subConfig = RunnableConfig.builder(parentConfig)
						.threadId(parentConfig.threadId().map(id -> id + "_" + agent.name()).orElseGet(agent::name))
						.nextNode(null)
						.checkPointId(null)
						.build();
					subConfig.clearContext();
					resultState = agent.invoke(Map.of("messages", messagesToAdd), subConfig);
				}
				else {
					resultState = agent.invoke(Map.of("messages", messagesToAdd));
				}
			}
			catch (GraphRunnerException e) {
				throw buildExecutionException(actualInput, parentConfigOpt.orElse(null),
						"sub-agent invocation failed: " + e.getMessage(), e);
			}
			catch (RuntimeException e) {
				throw buildExecutionException(actualInput, parentConfigOpt.orElse(null),
						"sub-agent invocation failed: " + e.getMessage(), e);
			}

			Optional<List> messages = resultState.flatMap(overAllState -> overAllState.value("messages", List.class));
			if (messages.isPresent()) {
				List<?> messageList = messages.get();
				Object last = messageList.isEmpty() ? null : messageList.get(messageList.size() - 1);
				ChatMessage response = last instanceof ChatMessage chat ? chat : null;
				if (response != null && response.role() == ChatRole.ASSISTANT) {
					return response;
				}
				throw buildExecutionException(actualInput, parentConfigOpt.orElse(null),
						"sub-agent returned no assistant message. Last message type: " + (response != null
								? response.role() : last == null ? "<empty>" : last.getClass().getName()),
						null);
			}

			throw buildExecutionException(actualInput, parentConfigOpt.orElse(null), "sub-agent returned no messages",
					null);
		}

		private RuntimeException buildExecutionException(String actualInput, RunnableConfig parentConfig, String detail,
				Throwable cause) {
			String threadId = parentConfig != null ? parentConfig.threadId().orElse("<no-thread-id>") : "<standalone>";
			String message = String.format("Failed to execute agent tool '%s' (parentThreadId=%s, input=%s): %s",
					agent.name(), threadId, actualInput, detail);
			return cause == null ? new RuntimeException(message) : new RuntimeException(message, cause);
		}

		/**
		 * Extract the actual input value from the wrapped JSON structure. The input is
		 * expected to be in the format: {"input": "actual_value"} If the input is not a
		 * valid JSON object or doesn't contain "input" field, the original input string
		 * is returned as-is.
		 * @param input the wrapped input JSON string
		 * @return the extracted input value, or the original input if extraction fails
		 */
		private String extractInputValue(String input) {
			if (!StringUtils.hasText(input)) {
				return input;
			}

			// Try to parse as JSON object and extract the "input" field
			try {
				Map<String, Object> jsonMap = jsonHelper.fromJsonToMap(input);

				if (jsonMap != null && jsonMap.containsKey("input")) {
					Object inputValue = jsonMap.get("input");
					// Convert the input value to string
					if (inputValue != null) {
						// If it's already a string, return it
						if (inputValue instanceof String) {
							return (String) inputValue;
						}
						// Otherwise, serialize it to JSON string
						return jsonHelper.toJson(inputValue);
					}
				}
			}
			catch (Exception e) {
				// Not a JSON object or parsing failed, use input as-is
			}

			// If extraction fails, return the original input
			return input;
		}

	}

}
