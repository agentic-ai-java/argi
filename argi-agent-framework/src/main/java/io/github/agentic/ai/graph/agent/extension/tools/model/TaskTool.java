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
package io.github.agentic.ai.graph.agent.extension.tools.model;

import io.github.agentic.ai.graph.agent.ReactAgent;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.graph.agent.tool.TypedTool;

import java.util.Map;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Tool that enables invoking subagents to handle complex, isolated tasks.
 *
 * This tool allows the main agent to delegate work to specialized subagents, each with
 * their own context and capabilities.
 */
public class TaskTool implements BiFunction<TaskTool.TaskRequest, ToolContext, String> {

	private final Map<String, ReactAgent> subAgents;

	public TaskTool(Map<String, ReactAgent> subAgents) {
		this.subAgents = subAgents;
	}

	@Override
	public String apply(TaskRequest request, ToolContext toolContext) {
		// Validate subagent type
		if (!subAgents.containsKey(request.subagentType)) {
			return "Error: invoked agent of type " + request.subagentType + ", the only allowed types are "
					+ subAgents.keySet();
		}

		// Get the subagent
		ReactAgent subAgent = subAgents.get(request.subagentType);

		try {
			// Invoke the subagent with the task description
			ChatMessage result = subAgent.call(request.description);

			// Return the subagent's response
			return result.text();
		}
		catch (Exception e) {
			return "Error executing subagent task: " + e.getMessage();
		}
	}

	/**
	 * Create a neutral tool binding for delegating a task.
	 */
	public static Tool createTaskTool(Map<String, ReactAgent> subAgents, String description) {
		return TypedTool.builder("task", new TaskTool(subAgents))
			.description(description)
			.inputType(TaskRequest.class)
			.build();
	}

	/**
	 * Request structure for the task tool.
	 */
	public static class TaskRequest {

		@JsonProperty(required = true)
		@JsonPropertyDescription("Detailed description of the task to be performed by the subagent")
		public String description;

		@JsonProperty(required = true, value = "subagent_type")
		@JsonPropertyDescription("The type of subagent to use for this task")
		public String subagentType;

		public TaskRequest() {
		}

		public TaskRequest(String description, String subagentType) {
			this.description = description;
			this.subagentType = subagentType;
		}

	}

}
