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
package io.github.agentic.ai.agent.orchestration.tools.task;

import io.github.agentic.ai.agent.orchestration.ReactAgent;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.agent.orchestration.tool.TypedTool;
import io.github.agentic.ai.agent.orchestration.tools.ToolContextHelper;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceContext;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceOperation;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceSpan;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTracer;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Tool that enables invoking sub-agents to handle complex, isolated tasks.
 * <p>
 * Inspired by spring ai TaskTool, this allows the main agent to delegate work to
 * specialized sub-agents, each with their own context and capabilities. Supports both
 * synchronous and background execution.
 */
public class TaskTool implements BiFunction<TaskTool.Request, ToolContext, String> {

	private static final Logger logger = LoggerFactory.getLogger(TaskTool.class);

	// @formatter:off
	public static final String DEFAULT_DESCRIPTION = """
		Launch a specialized sub-agent to handle complex, multi-step tasks autonomously.

		Use this tool when:
		- The task requires exploring codebases, researching, or multi-step execution
		- You need to delegate to an agent with specific expertise (e.g., Explore, general-purpose)
		- The task would consume too much context if done directly

		Available sub-agent types and their capabilities:
		%s

		Parameters:
		- description: Short (3-5 word) summary of what the agent will do
		- prompt: The detailed task for the sub-agent to perform
		- subagent_type: The type of specialized agent to use (required)
		- run_in_background: Set to true to run asynchronously; use TaskOutput tool to retrieve results later

		When run_in_background=true, returns a task_id. Use the TaskOutput tool with that task_id to get results.
		""";
	// @formatter:on

	private final Map<String, ReactAgent> subAgents;

	private final TaskRepository taskRepository;

	private final AgentTracer agentTracer;

	private final TaskTraceCorrelation traceCorrelation;

	public TaskTool(Map<String, ReactAgent> subAgents, TaskRepository taskRepository) {
		this(subAgents, taskRepository, null, null);
	}

	/**
	 * Create a TaskTool with optional cross-agent trace correlation. The tracer is an
	 * explicit opt-in; with a null tracer the historical behavior is preserved exactly and
	 * no observation is emitted.
	 * @param subAgents the sub-agents this tool can delegate to
	 * @param taskRepository the repository used for background tasks
	 * @param agentTracer the cross-agent tracer, may be null
	 * @param traceCorrelation the correlation store shared with the TaskOutputTool, may be
	 * null
	 */
	public TaskTool(Map<String, ReactAgent> subAgents, TaskRepository taskRepository, AgentTracer agentTracer,
			TaskTraceCorrelation traceCorrelation) {
		Assert.notEmpty(subAgents, "subAgents must not be empty");
		Assert.notNull(taskRepository, "taskRepository must not be null");
		this.subAgents = Map.copyOf(subAgents);
		this.taskRepository = taskRepository;
		this.agentTracer = agentTracer;
		this.traceCorrelation = agentTracer != null && traceCorrelation == null ? new TaskTraceCorrelation()
				: traceCorrelation;
	}

	@Override
	public String apply(Request request, ToolContext toolContext) {
		String subagentType = request.subagentType();
		if (!StringUtils.hasText(subagentType)) {
			return "Error: subagent_type is required";
		}

		if (!subAgents.containsKey(subagentType)) {
			return "Error: Unknown subagent type: " + subagentType + ". Allowed types: " + subAgents.keySet();
		}

		ReactAgent subAgent = subAgents.get(subagentType);
		String prompt = request.prompt();
		if (!StringUtils.hasText(prompt)) {
			return "Error: prompt is required";
		}

		boolean tracing = tracing();
		RunnableConfig parentConfig = tracing ? ToolContextHelper.getConfig(toolContext).orElse(null) : null;
		AgentTraceContext parentContext = tracing ? captureContext(toolContext, parentConfig) : null;

		if (Boolean.TRUE.equals(request.runInBackground())) {
			String taskId = "task_" + UUID.randomUUID();
			AgentTraceContext taskContext = parentContext != null
					? parentContext.withTaskId(taskId).withDelegationId(UUID.randomUUID().toString()) : null;
			if (this.traceCorrelation != null && taskContext != null) {
				this.traceCorrelation.record(taskId, taskContext);
			}
			try {
				this.taskRepository.putTask(taskId, () -> {
					try {
						return executeSubAgent(subAgent, prompt, parentConfig, taskContext);
					}
					finally {
						// The pollable result window is measured from completion, not
						// submission: a long-running task must not lose its link before
						// the first result poll (#370 review).
						if (this.traceCorrelation != null) {
							this.traceCorrelation.refresh(taskId);
						}
					}
				});
			}
			catch (RuntimeException e) {
				// A rejected submission must not leave a stranded correlation record.
				if (this.traceCorrelation != null) {
					this.traceCorrelation.forget(taskId);
				}
				throw e;
			}
			return String.format(
					"task_id: %s%n%nBackground task started. Use TaskOutput tool with task_id='%s' to retrieve results.",
					taskId, taskId);
		}

		return executeSubAgent(subAgent, prompt, parentConfig, parentContext);
	}

	private String executeSubAgent(ReactAgent subAgent, String prompt, RunnableConfig parentConfig,
			AgentTraceContext context) {
		boolean tracing = tracing();
		AgentTraceContext spanContext = context;
		if (tracing) {
			// Every delegation span carries its own correlation anchor so consumers can
			// link back to it even when the caller did not provide one.
			spanContext = spanContext != null
					? (spanContext.delegationId() != null ? spanContext
							: spanContext.withDelegationId(UUID.randomUUID().toString()))
					: AgentTraceContext.builder().delegationId(UUID.randomUUID().toString()).build();
		}
		try (AgentTraceSpan span = tracing
				? this.agentTracer.start(AgentTraceOperation.AGENT_DELEGATION, spanContext)
				: AgentTraceSpan.disabled()) {
			try {
				ChatMessage result = invokeSubAgent(subAgent, prompt, parentConfig, tracing);
				span.success();
				return result != null ? result.text() : "Sub-agent returned empty response";
			}
			catch (Exception e) {
				span.error(e);
				logger.warn("Sub-agent execution failed: {}", e.getMessage());
				return "Error executing sub-agent: " + e.getMessage();
			}
		}
	}

	private ChatMessage invokeSubAgent(ReactAgent subAgent, String prompt, RunnableConfig parentConfig,
			boolean tracing) throws Exception {
		if (tracing && parentConfig != null) {
			// Same derivation convention as AgentTool so the child chain stays searchable.
			RunnableConfig childConfig = RunnableConfig.builder(parentConfig)
				.threadId(parentConfig.threadId().map(id -> id + "_" + subAgent.name()).orElseGet(subAgent::name))
				.nextNode(null)
				.checkPointId(null)
				.build();
			childConfig.clearContext();
			return subAgent.call(prompt, childConfig);
		}
		return subAgent.call(prompt);
	}

	private boolean tracing() {
		return this.agentTracer != null && this.agentTracer.isEnabled();
	}

	private AgentTraceContext captureContext(ToolContext toolContext, RunnableConfig parentConfig) {
		Map<String, Object> state = ToolContextHelper.getState(toolContext).map(stateValue -> stateValue.data())
			.orElse(null);
		return AgentTraceContext.capture(parentConfig, state);
	}

	/**
	 * Request structure for the Task tool.
	 */
	@JsonClassDescription("Request to launch a sub-agent task")
	public record Request(@JsonProperty(
			required = true) @JsonPropertyDescription("Short (3-5 word) description of what the agent will do") String description,

			@JsonProperty(
					required = true) @JsonPropertyDescription("The detailed task for the sub-agent to perform") String prompt,

			@JsonProperty(required = true,
					value = "subagent_type") @JsonPropertyDescription("The type of specialized agent to use") String subagentType,

			@JsonProperty(
					value = "run_in_background") @JsonPropertyDescription("Set to true to run in background; use TaskOutput to retrieve results") Boolean runInBackground) {
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private final Map<String, ReactAgent> subAgents = new HashMap<>();

		private TaskRepository taskRepository = new DefaultTaskRepository();

		private AgentTracer agentTracer;

		private TaskTraceCorrelation traceCorrelation;

		private String name = "Task";

		private String description;

		public Builder subAgent(String type, ReactAgent agent) {
			Assert.hasText(type, "type must not be empty");
			Assert.notNull(agent, "agent must not be null");
			this.subAgents.put(type, agent);
			return this;
		}

		public Builder subAgents(Map<String, ReactAgent> agents) {
			if (!CollectionUtils.isEmpty(agents)) {
				this.subAgents.putAll(agents);
			}
			return this;
		}

		public Builder taskRepository(TaskRepository taskRepository) {
			Assert.notNull(taskRepository, "taskRepository must not be null");
			this.taskRepository = taskRepository;
			return this;
		}

		/**
		 * Enable cross-agent trace correlation with the given tracer.
		 * @param agentTracer the tracer, may be null to keep tracing disabled
		 * @return this builder
		 */
		public Builder agentTracer(AgentTracer agentTracer) {
			this.agentTracer = agentTracer;
			return this;
		}

		/**
		 * Share one correlation store between TaskTool and TaskOutputTool so receive spans
		 * can link to their delegation span. When omitted, a private store is created.
		 * @param traceCorrelation the shared store, may be null
		 * @return this builder
		 */
		public Builder traceCorrelation(TaskTraceCorrelation traceCorrelation) {
			this.traceCorrelation = traceCorrelation;
			return this;
		}

		public Builder withName(String name) {
			this.name = name != null ? name : "Task";
			return this;
		}

		public Builder withDescription(String description) {
			this.description = description;
			return this;
		}

		public Tool build() {
			Assert.notEmpty(this.subAgents, "At least one sub-agent must be configured");

			String desc = this.description;
			if (!StringUtils.hasText(desc)) {
				String agentList = this.subAgents.entrySet()
					.stream()
					.map(e -> "- " + e.getKey() + ": " + e.getValue().description())
					.reduce("", (a, b) -> a + b + "\n");
				desc = DEFAULT_DESCRIPTION.formatted(agentList);
			}

			TaskTool taskTool = new TaskTool(this.subAgents, this.taskRepository, this.agentTracer,
					this.traceCorrelation);
			return TypedTool.builder(this.name, taskTool).description(desc).inputType(Request.class).build();
		}

	}

}
