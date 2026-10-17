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
package io.github.agentic.ai.graph.agent.tools.task;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.tool.TypedTool;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import org.springframework.util.Assert;

import java.util.function.BiFunction;

import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;

/**
 * Tool for retrieving output from running or completed background tasks.
 * <p>
 * Use this tool when TaskTool was invoked with run_in_background=true. Provides the
 * task_id to check status and retrieve results.
 */
public class TaskOutputTool implements BiFunction<TaskOutputTool.Request, ToolContext, String> {

	// @formatter:off
	public static final String DEFAULT_DESCRIPTION = """
		Retrieves output from a running or completed background task (sub-agent).

		Use when:
		- A Task was launched with run_in_background=true
		- You need to check status or get results from a background sub-agent

		Parameters:
		- task_id: The task ID returned by the Task tool when run_in_background was true
		- block: Whether to wait for completion (default: true)
		- timeout: Max wait time in milliseconds (default: 30000, max: 600000)
		""";
	// @formatter:on

	private final TaskRepository taskRepository;

	public TaskOutputTool(TaskRepository taskRepository) {
		Assert.notNull(taskRepository, "taskRepository must not be null");
		this.taskRepository = taskRepository;
	}

	@Override
	public String apply(Request request, ToolContext toolContext) {
		// Extract caller tenant for tenant isolation
		String callerTenantId = null;
		Object configObj = toolContext.attributes().get(AGENT_CONFIG_CONTEXT_KEY);
		if (configObj instanceof RunnableConfig config) {
			TrustedExecutionContext trustCtx = config.trustedExecutionContext().orElse(null);
			if (trustCtx != null && trustCtx.tenant() != null) {
				callerTenantId = trustCtx.tenant().id();
			}
		}

		BackgroundTask bgTask = this.taskRepository.getTask(request.taskId(), callerTenantId);

		if (bgTask == null) {
			return "Error: No background task found with ID: " + request.taskId();
		}

		boolean shouldBlock = request.block() == null || request.block();
		long timeoutMs = request.timeout() != null ? Math.min(request.timeout(), 600000) : 30000;

		if (shouldBlock && !bgTask.isCompleted()) {
			try {
				bgTask.waitForCompletion(timeoutMs);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "Error: Wait for task interrupted";
			}
		}

		StringBuilder result = new StringBuilder();
		result.append("Task ID: ").append(request.taskId()).append("\n");
		result.append("Status: ").append(bgTask.getStatus()).append("\n\n");

		if (bgTask.isCompleted() && bgTask.getResult() != null) {
			result.append("Result:\n").append(bgTask.getResult());
		}
		else if (bgTask.getError() != null) {
			result.append("Error:\n").append(bgTask.getError().getMessage());
			if (bgTask.getError().getCause() != null) {
				result.append("\nCause: ").append(bgTask.getError().getCause().getMessage());
			}
		}
		else if (!bgTask.isCompleted()) {
			result.append("Task still running...");
		}

		return result.toString();
	}

	/**
	 * Request structure for the TaskOutput tool.
	 */
	@JsonClassDescription("Request to get output from a background task")
	public record Request(
			@JsonProperty(required = true,
					value = "task_id") @JsonPropertyDescription("The task ID to get output from") String taskId,

			@JsonProperty(
					value = "block") @JsonPropertyDescription("Whether to wait for completion (default: true)") Boolean block,

			@JsonProperty(
					value = "timeout") @JsonPropertyDescription("Max wait time in milliseconds (default: 30000)") Long timeout) {
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private TaskRepository taskRepository;

		private String name = "TaskOutput";

		private String description = DEFAULT_DESCRIPTION;

		public Builder taskRepository(TaskRepository taskRepository) {
			Assert.notNull(taskRepository, "taskRepository must not be null");
			this.taskRepository = taskRepository;
			return this;
		}

		public Builder withName(String name) {
			this.name = name != null ? name : "TaskOutput";
			return this;
		}

		public Builder withDescription(String description) {
			this.description = description;
			return this;
		}

		public Tool build() {
			Assert.notNull(this.taskRepository, "taskRepository must be provided");
			return TypedTool.builder(this.name, new TaskOutputTool(this.taskRepository))
				.description(this.description)
				.inputType(Request.class)
				.build();
		}

	}

}
