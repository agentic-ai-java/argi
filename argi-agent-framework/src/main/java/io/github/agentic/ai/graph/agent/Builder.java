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

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.agent.hook.Hook;
import io.github.agentic.ai.graph.agent.interceptor.Interceptor;
import io.github.agentic.ai.graph.agent.interceptor.BusinessOutcomeMapperRegistry;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.StreamingModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;

import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.micrometer.observation.ObservationRegistry;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.tool.ToolRegistry;

import org.springframework.util.Assert;

public abstract class Builder {

	protected String name;

	protected String description;

	protected String instruction;

	protected String systemPrompt;

	protected BiFunction<String, Map<String, Object>, String> templateRenderer;

	protected GenerationOptions generationOptions;

	protected ChatClient chatClient;

	protected ToolRegistry toolRegistry = new ToolRegistry(List.of());

	protected BusinessOutcomeMapperRegistry businessOutcomeMappers = BusinessOutcomeMapperRegistry.empty();

	protected boolean throwOnModelError;

	protected boolean throwOnToolError;

	protected Map<String, Object> toolContext = new HashMap<>();

	protected boolean releaseThread;

	protected BaseCheckpointSaver saver;

	protected CompileConfig compileConfig;

	protected List<Hook> hooks = new ArrayList<>();

	protected List<Interceptor> interceptors = new ArrayList<>();

	protected List<ModelInterceptor> modelInterceptors = new ArrayList<>();

	protected List<ToolInterceptor> toolInterceptors = new ArrayList<>();

	protected List<StreamingModelInterceptor> streamingInterceptors = new ArrayList<>();

	protected boolean includeContents = true;

	protected boolean returnReasoningContents;

	// Register AssistantMessageSanitizerInterceptor by default to keep tool-calling
	// AssistantMessages with null text compatible with strict DeepSeek-compatible
	// backends.
	protected boolean assistantMessageSanitizerEnabled = true;

	protected String outputKey;

	protected KeyStrategy outputKeyStrategy;

	protected String inputSchema;

	protected Type inputType;

	protected String outputSchema;

	protected Class<?> outputType;

	protected ObservationRegistry observationRegistry;

	protected boolean enableLogging;

	protected StateSerializer stateSerializer;

	protected Executor executor;

	// Async tool execution configuration
	protected boolean parallelToolExecution = false;

	protected int maxParallelTools = 5;

	protected Duration toolExecutionTimeout = Duration.ofMinutes(5);

	protected boolean validateToolArguments = false;

	public Builder name(String name) {
		this.name = name;
		return this;
	}

	public Builder chatClient(ChatClient chatClient) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
		return this;
	}

	public Builder generationOptions(GenerationOptions generationOptions) {
		this.generationOptions = generationOptions;
		return this;
	}

	/** Sets immutable, provider-neutral tool bindings executed by the agent runtime. */
	public Builder toolRegistry(ToolRegistry toolRegistry) {
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
		return this;
	}

	/**
	 * Registers per-tool {@link BusinessOutcomeMapper}s so a business-level failure
	 * reported inside an otherwise successful tool result can be surfaced with the
	 * {@link io.github.agentic.ai.graph.agent.interceptor.ToolOutcome#BUSINESS_FAILED}
	 * outcome. Unregistered tools are never inspected for failure signals.
	 */
	public Builder businessOutcomeMappers(BusinessOutcomeMapperRegistry businessOutcomeMappers) {
		this.businessOutcomeMappers = Objects.requireNonNull(businessOutcomeMappers,
				"businessOutcomeMappers must not be null");
		return this;
	}

	/**
	 * Whether to propagate exceptions caught while invoking the model instead of
	 * converting them to an {@code AssistantMessage} with an {@code Exception:} prefix.
	 * Defaults to {@code false} for backward compatibility. When enabled, model
	 * interceptors receive the original exception, including its type and cause.
	 * <p>
	 * Applies to synchronous calls and synchronous streaming setup failures. Errors
	 * emitted by a streaming publisher continue to propagate in either mode. This option
	 * does not change tool exception handling or configure retries.
	 * @param throwOnModelError whether to propagate caught model exceptions
	 * @return this builder
	 */
	public Builder throwOnModelError(boolean throwOnModelError) {
		this.throwOnModelError = throwOnModelError;
		return this;
	}

	/**
	 * Propagates tool execution failures instead of sending an error result to the model.
	 */
	public Builder throwOnToolError(boolean throwOnToolError) {
		this.throwOnToolError = throwOnToolError;
		return this;
	}

	public Builder toolContext(Map<String, Object> toolContext) {
		Assert.notNull(toolContext, "toolContext cannot be null");
		Assert.noNullElements(toolContext.keySet(), "toolContext keys cannot contain null elements");
		Assert.noNullElements(toolContext.values(), "toolContext values cannot contain null elements");
		this.toolContext.putAll(toolContext);
		return this;
	}

	public Builder releaseThread(boolean releaseThread) {
		this.releaseThread = releaseThread;
		return this;
	}

	public Builder saver(BaseCheckpointSaver saver) {
		Assert.notNull(saver, "saver cannot be null");
		this.saver = saver;
		return this;
	}

	public Builder compileConfig(CompileConfig compileConfig) {
		Assert.notNull(compileConfig, "compileConfig cannot be null");
		this.compileConfig = compileConfig;
		return this;
	}

	public Builder description(String description) {
		this.description = description;
		return this;
	}

	public Builder instruction(String instruction) {
		this.instruction = instruction;
		return this;
	}

	public Builder systemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
		return this;
	}

	/**
	 * Sets a provider-neutral renderer for instruction templates. The function receives
	 * the template text and its variables, and must be safe for concurrent calls.
	 * @param templateRenderer the template renderer
	 * @return this builder
	 */
	public Builder templateRenderer(BiFunction<String, Map<String, Object>, String> templateRenderer) {
		this.templateRenderer = templateRenderer;
		return this;
	}

	public Builder outputKey(String outputKey) {
		this.outputKey = outputKey;
		return this;
	}

	public Builder outputKeyStrategy(KeyStrategy outputKeyStrategy) {
		this.outputKeyStrategy = outputKeyStrategy;
		return this;
	}

	public Builder inputSchema(String inputSchema) {
		this.inputSchema = inputSchema;
		return this;
	}

	public Builder inputType(Type inputType) {
		this.inputType = inputType;
		return this;
	}

	public Builder outputSchema(String outputSchema) {
		this.outputSchema = outputSchema;
		return this;
	}

	public Builder outputType(Class<?> outputType) {
		this.outputType = outputType;
		return this;
	}

	public Builder includeContents(boolean includeContents) {
		this.includeContents = includeContents;
		return this;
	}

	public Builder returnReasoningContents(boolean returnReasoningContents) {
		this.returnReasoningContents = returnReasoningContents;
		return this;
	}

	/**
	 * Enables or disables opt-in validation of tool-call arguments against the tool's
	 * declared input schema before dispatch.
	 * <p>
	 * When enabled, malformed JSON, missing required properties, type/enum/range
	 * violations and undeclared properties are rejected before the tool callback runs.
	 * The default is disabled, preserving legacy pass-through behavior.
	 * @param validate true to validate tool arguments before dispatch, false to pass them
	 * through unchanged
	 * @return this builder instance
	 */
	public Builder validateToolArguments(boolean validate) {
		this.validateToolArguments = validate;
		return this;
	}

	public Builder assistantMessageSanitizerEnabled(boolean assistantMessageSanitizerEnabled) {
		this.assistantMessageSanitizerEnabled = assistantMessageSanitizerEnabled;
		return this;
	}

	public Builder hooks(List<? extends Hook> hooks) {
		Assert.notNull(hooks, "hooks cannot be null");
		Assert.noNullElements(hooks, "hooks cannot contain null elements");
		this.hooks.addAll(hooks);
		return this;
	}

	public Builder hooks(Hook... hooks) {
		Assert.notNull(hooks, "hooks cannot be null");
		Assert.noNullElements(hooks, "hooks cannot contain null elements");
		this.hooks.addAll(List.of(hooks));
		return this;
	}

	public Builder interceptors(List<? extends Interceptor> interceptors) {
		Assert.notNull(interceptors, "interceptors cannot be null");
		Assert.noNullElements(interceptors, "interceptors cannot contain null elements");
		this.interceptors.addAll(interceptors);
		return this;
	}

	public Builder interceptors(Interceptor... interceptors) {
		Assert.notNull(interceptors, "interceptors cannot be null");
		Assert.noNullElements(interceptors, "interceptors cannot contain null elements");
		this.interceptors.addAll(List.of(interceptors));
		return this;
	}

	public Builder streamingInterceptors(List<StreamingModelInterceptor> streamingInterceptors) {
		Assert.notNull(streamingInterceptors, "streamingInterceptors cannot be null");
		Assert.noNullElements(streamingInterceptors, "streamingInterceptors cannot contain null elements");
		this.streamingInterceptors.addAll(streamingInterceptors);
		return this;
	}

	public Builder streamingInterceptors(StreamingModelInterceptor... streamingInterceptors) {
		Assert.notNull(streamingInterceptors, "streamingInterceptors cannot be null");
		Assert.noNullElements(streamingInterceptors, "streamingInterceptors cannot contain null elements");
		this.streamingInterceptors.addAll(List.of(streamingInterceptors));
		return this;
	}

	public Builder observationRegistry(ObservationRegistry observationRegistry) {
		this.observationRegistry = observationRegistry;
		return this;
	}

	public Builder enableLogging(boolean enableLogging) {
		this.enableLogging = enableLogging;
		return this;
	}

	/**
	 * Sets the state serializer for the agent.
	 * @param stateSerializer the state serializer to use
	 * @return this builder instance
	 */
	public Builder stateSerializer(StateSerializer stateSerializer) {
		this.stateSerializer = stateSerializer;
		return this;
	}

	/**
	 * Sets the executor for parallel nodes.
	 * <p>
	 * This executor will be used for all parallel nodes in the agent's execution graph.
	 * When a parallel node is executed, it will use this executor to run the parallel
	 * branches concurrently.
	 * @param executor the {@link Executor} to use for parallel nodes
	 * @return this builder instance
	 */
	public Builder executor(Executor executor) {
		Assert.notNull(executor, "executor cannot be null");
		this.executor = executor;
		return this;
	}

	/**
	 * Enables or disables parallel tool execution.
	 * <p>
	 * When enabled, multiple tool calls in a single response will be executed
	 * concurrently rather than sequentially. This can significantly improve performance
	 * when tools are I/O-bound or have long execution times.
	 * @param parallel true to enable parallel execution, false for sequential
	 * @return this builder instance
	 */
	public Builder parallelToolExecution(boolean parallel) {
		this.parallelToolExecution = parallel;
		return this;
	}

	/**
	 * Sets the maximum number of tools that can execute in parallel.
	 * <p>
	 * This limit helps prevent resource exhaustion when many tools are called
	 * simultaneously. The default is 5.
	 * @param max the maximum number of parallel tool executions (must be at least 1)
	 * @return this builder instance
	 * @throws IllegalArgumentException if max is less than 1
	 */
	public Builder maxParallelTools(int max) {
		if (max < 1) {
			throw new IllegalArgumentException("maxParallelTools must be at least 1");
		}
		this.maxParallelTools = max;
		return this;
	}

	/**
	 * Sets the timeout for individual tool executions.
	 * <p>
	 * If a tool execution exceeds this timeout, it will be cancelled and an error will be
	 * returned. The default is 5 minutes.
	 * @param timeout the maximum duration for a single tool execution
	 * @return this builder instance
	 * @throws NullPointerException if timeout is null
	 */
	public Builder toolExecutionTimeout(Duration timeout) {
		this.toolExecutionTimeout = Objects.requireNonNull(timeout, "timeout cannot be null");
		return this;
	}

	protected CompileConfig buildConfig() {
		if (compileConfig != null) {
			return compileConfig;
		}

		SaverConfig saverConfig = SaverConfig.builder().register(saver).build();
		return CompileConfig.builder()
			.saverConfig(saverConfig)
			.recursionLimit(Integer.MAX_VALUE)
			.releaseThread(releaseThread)
			.build();
	}

	public abstract ReactAgent build();

}
