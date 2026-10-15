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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.exception.GraphRunnerException;
import io.github.agentic.ai.graph.exception.GraphStateException;
import io.github.agentic.ai.graph.scheduling.ScheduleConfig;
import io.github.agentic.ai.graph.scheduling.ScheduledAgentTask;
import io.github.agentic.ai.graph.state.StateSnapshot;

import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.MessageProvenance;

import reactor.core.publisher.Flux;

import org.springframework.scheduling.Trigger;

/**
 * Abstract base class for all agents in the graph system. Contains common properties and
 * methods shared by different agent implementations.
 */
public abstract class Agent {

	/** The agent's name. Must be a unique identifier within the graph. */
	protected String name;

	/**
	 * One line description about the agent's capability. The system can use this for
	 * decision-making when delegating control to different agents.
	 */
	protected String description;

	protected CompileConfig compileConfig;

	protected volatile CompiledGraph compiledGraph;

	protected volatile StateGraph graph;

	protected Executor executor;

	/**
	 * Protected constructor for initializing all base agent properties.
	 * @param name the unique name of the agent
	 * @param description the description of the agent's capability
	 */
	protected Agent(String name, String description) {
		this.name = name;
		this.description = description;
	}

	/**
	 * Default protected constructor for subclasses that need to initialize properties
	 * differently.
	 */
	protected Agent() {
		// Allow subclasses to initialize properties through other means
	}

	/**
	 * Gets the agent's unique name.
	 * @return the unique name of the agent.
	 */
	public String name() {
		return name;
	}

	/**
	 * Gets the one-line description of the agent's capability.
	 * @return the description of the agent.
	 */
	public String description() {
		return description;
	}

	public StateGraph getGraph() {
		if (this.graph == null) {
			try {
				this.graph = initGraph();
			}
			catch (GraphStateException e) {
				throw new RuntimeException(e);
			}
		}
		return this.graph;
	}

	public synchronized CompiledGraph getAndCompileGraph() {
		if (compiledGraph != null) {
			return compiledGraph;
		}

		StateGraph graph = getGraph();
		try {
			if (this.compileConfig == null) {
				this.compiledGraph = graph.compile();
			}
			else {
				this.compiledGraph = graph.compile(this.compileConfig);
			}
		}
		catch (GraphStateException e) {
			throw new RuntimeException(e);
		}
		return this.compiledGraph;
	}

	/**
	 * Schedule the agent task with trigger.
	 * @param trigger the schedule configuration
	 * @param input the agent input
	 * @return a ScheduledAgentTask instance for managing the scheduled task
	 */
	public ScheduledAgentTask schedule(Trigger trigger, Map<String, Object> input)
			throws GraphStateException, GraphRunnerException {
		ScheduleConfig scheduleConfig = ScheduleConfig.builder().trigger(trigger).inputs(input).build();
		return schedule(scheduleConfig);
	}

	/**
	 * Schedule the agent task with trigger.
	 * @param scheduleConfig the schedule configuration
	 * @return a ScheduledAgentTask instance for managing the scheduled task
	 */
	public ScheduledAgentTask schedule(ScheduleConfig scheduleConfig) throws GraphStateException {
		CompiledGraph compiledGraph = getAndCompileGraph();
		return compiledGraph.schedule(scheduleConfig);
	}

	public StateSnapshot getCurrentState(RunnableConfig config) throws GraphRunnerException {
		return compiledGraph.getState(config);
	}

	// ------------------- Invoke with OverAllState as return value -------------------

	public Optional<OverAllState> invoke(String message) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvoke(inputs, null);
	}

	public Optional<OverAllState> invoke(String message, RunnableConfig config) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvoke(inputs, config);
	}

	public Optional<OverAllState> invoke(ChatMessage message) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvoke(inputs, null);
	}

	public Optional<OverAllState> invoke(ChatMessage message, RunnableConfig config) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvoke(inputs, config);
	}

	public Optional<OverAllState> invoke(List<ChatMessage> messages) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(messages);
		return doInvoke(inputs, null);
	}

	public Optional<OverAllState> invoke(List<ChatMessage> messages, RunnableConfig config)
			throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(messages);
		return doInvoke(inputs, config);
	}

	/**
	 * Invokes the agent with the given inputs map.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @return the resulting overall state, or empty if none
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Optional<OverAllState> invoke(Map<String, Object> inputs) throws GraphRunnerException {
		return doInvoke(normalizeMessageInput(inputs), null);
	}

	/**
	 * Invokes the agent with the given inputs map and runtime config.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @param config runtime configuration controlling execution behavior
	 * @return the resulting overall state, or empty if none
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Optional<OverAllState> invoke(Map<String, Object> inputs, RunnableConfig config)
			throws GraphRunnerException {
		return doInvoke(normalizeMessageInput(inputs), config);
	}

	// ------------------- Invoke methods with Output as return value -------------------

	public Optional<NodeOutput> invokeAndGetOutput(String message) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvokeAndGetOutput(inputs, null);
	}

	public Optional<NodeOutput> invokeAndGetOutput(String message, RunnableConfig config) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvokeAndGetOutput(inputs, config);
	}

	public Optional<NodeOutput> invokeAndGetOutput(ChatMessage message) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvokeAndGetOutput(inputs, null);
	}

	public Optional<NodeOutput> invokeAndGetOutput(ChatMessage message, RunnableConfig config)
			throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doInvokeAndGetOutput(inputs, config);
	}

	public Optional<NodeOutput> invokeAndGetOutput(List<ChatMessage> messages) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(messages);
		return doInvokeAndGetOutput(inputs, null);
	}

	public Optional<NodeOutput> invokeAndGetOutput(List<ChatMessage> messages, RunnableConfig config)
			throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(messages);
		return doInvokeAndGetOutput(inputs, config);
	}

	/**
	 * Invokes the agent with the given inputs map and returns the node output.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @return the node output, or empty if none
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Optional<NodeOutput> invokeAndGetOutput(Map<String, Object> inputs) throws GraphRunnerException {
		return doInvokeAndGetOutput(normalizeMessageInput(inputs), null);
	}

	/**
	 * Invokes the agent with the given inputs map and runtime config, returns the node
	 * output.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @param config runtime configuration controlling execution behavior
	 * @return the node output, or empty if none
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Optional<NodeOutput> invokeAndGetOutput(Map<String, Object> inputs, RunnableConfig config)
			throws GraphRunnerException {
		return doInvokeAndGetOutput(normalizeMessageInput(inputs), config);
	}

	// ------------------- ChatMessage Stream methods -------------------

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * a plain text input.
	 * <p>
	 * This is a convenience API built on top of {@code stream(...)} that extracts and
	 * emits {@link ChatMessage} instances directly instead of low-level
	 * {@code NodeOutput} objects. It is intended for use cases that only care about the
	 * generated messages and do not require access to graph orchestration or node
	 * execution details.
	 * @param message the input message as plain text
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(String message) throws GraphRunnerException {
		return stream(message).transform(this::extractMessages);
	}

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * a plain text input and a custom {@link RunnableConfig}.
	 * @param message the input message as plain text
	 * @param config runtime configuration controlling execution behavior
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(String message, RunnableConfig config) throws GraphRunnerException {
		return stream(message, config).transform(this::extractMessages);
	}

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * a {@link ChatMessage} as input.
	 * @param message the user message input
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(ChatMessage message) throws GraphRunnerException {
		return stream(message).transform(this::extractMessages);
	}

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * a {@link ChatMessage} as input and a custom {@link RunnableConfig}.
	 * @param message the user message input
	 * @param config runtime configuration controlling execution behavior
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(ChatMessage message, RunnableConfig config) throws GraphRunnerException {
		return stream(message, config).transform(this::extractMessages);
	}

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * a list of input {@link ChatMessage} instances.
	 * @param messages the input messages
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(List<ChatMessage> messages) throws GraphRunnerException {
		return stream(messages).transform(this::extractMessages);
	}

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * a list of input {@link ChatMessage} instances and a custom {@link RunnableConfig}.
	 * @param messages the input messages
	 * @param config runtime configuration controlling execution behavior
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(List<ChatMessage> messages, RunnableConfig config)
			throws GraphRunnerException {
		return stream(messages, config).transform(this::extractMessages);
	}

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * an inputs map.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(Map<String, Object> inputs) throws GraphRunnerException {
		return stream(inputs).transform(this::extractMessages);
	}

	/**
	 * Streams the execution result as a {@link Flux} of {@link ChatMessage} objects using
	 * an inputs map and a custom {@link RunnableConfig}.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @param config runtime configuration controlling execution behavior
	 * @return a {@link Flux} emitting {@link ChatMessage} objects as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<ChatMessage> streamMessages(Map<String, Object> inputs, RunnableConfig config)
			throws GraphRunnerException {
		return stream(inputs, config).transform(this::extractMessages);
	}

	// ------------------- Stream methods -------------------

	public Flux<NodeOutput> stream(String message) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doStream(inputs, buildStreamConfig(null));
	}

	public Flux<NodeOutput> stream(String message, RunnableConfig config) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doStream(inputs, config);
	}

	public Flux<NodeOutput> stream(ChatMessage message) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doStream(inputs, buildStreamConfig(null));
	}

	public Flux<NodeOutput> stream(ChatMessage message, RunnableConfig config) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return doStream(inputs, config);
	}

	public Flux<NodeOutput> stream(List<ChatMessage> messages) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(messages);
		return doStream(inputs, buildStreamConfig(null));
	}

	public Flux<NodeOutput> stream(List<ChatMessage> messages, RunnableConfig config) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(messages);
		return doStream(inputs, config);
	}

	/**
	 * Streams the graph execution with the given inputs map.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @return a {@link Flux} emitting {@link NodeOutput} as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<NodeOutput> stream(Map<String, Object> inputs) throws GraphRunnerException {
		return doStream(normalizeMessageInput(inputs), buildStreamConfig(null));
	}

	/**
	 * Streams the graph execution with the given inputs map and runtime config.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and
	 * {@code input}, use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for
	 * the agent. Other keys can be arbitrary and are passed as graph state, e.g. for
	 * prompt placeholders or any other state values.
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @param config runtime configuration controlling execution behavior
	 * @return a {@link Flux} emitting {@link NodeOutput} as they are produced
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public Flux<NodeOutput> stream(Map<String, Object> inputs, RunnableConfig config) throws GraphRunnerException {
		return doStream(normalizeMessageInput(inputs), config);
	}

	protected Optional<OverAllState> doInvoke(Map<String, Object> input, RunnableConfig runnableConfig) {
		CompiledGraph compiledGraph = getAndCompileGraph();
		return compiledGraph.invoke(input, buildNonStreamConfig(runnableConfig));
	}

	protected Optional<NodeOutput> doInvokeAndGetOutput(Map<String, Object> input, RunnableConfig runnableConfig) {
		CompiledGraph compiledGraph = getAndCompileGraph();
		return compiledGraph.invokeAndGetOutput(input, buildNonStreamConfig(runnableConfig));
	}

	protected Flux<NodeOutput> doStream(Map<String, Object> input, RunnableConfig runnableConfig) {
		CompiledGraph compiledGraph = getAndCompileGraph();
		return compiledGraph.stream(input, buildStreamConfig(runnableConfig));
	}

	protected RunnableConfig buildNonStreamConfig(RunnableConfig config) {
		RunnableConfig.Builder builder = config == null ? RunnableConfig.builder() : RunnableConfig.builder(config);

		builder.addMetadata("_stream_", false).addMetadata("_AGENT_", name);
		applyExecutorConfig(builder);

		return builder.build();
	}

	protected RunnableConfig buildStreamConfig(RunnableConfig config) {
		RunnableConfig.Builder builder = config == null ? RunnableConfig.builder() : RunnableConfig.builder(config);

		builder.addMetadata("_AGENT_", name);
		applyExecutorConfig(builder);

		return builder.build();
	}

	/**
	 * Applies executor configuration to the RunnableConfig builder. This method sets the
	 * default executor for parallel nodes from the agent's configuration.
	 * @param builder the RunnableConfig builder to apply executor configuration to
	 */
	protected void applyExecutorConfig(RunnableConfig.Builder builder) {
		if (executor != null) {
			builder.defaultParallelExecutor(executor);
		}
	}

	protected Map<String, Object> buildMessageInput(Object message) {
		List<ChatMessage> messages = chatMessages(message);

		Map<String, Object> inputs = new HashMap<>();
		inputs.put("messages", messages);

		ChatMessage lastUserMessage = null;
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage msg = messages.get(i);
			if (msg.role() == ChatRole.USER) {
				lastUserMessage = msg;
				break;
			}
		}
		if (lastUserMessage != null) {
			inputs.put("input", lastUserMessage.text());
		}
		return inputs;
	}

	/**
	 * Copies external inputs and validates message types before entering the internal
	 * runtime.
	 */
	protected Map<String, Object> normalizeMessageInput(Map<String, Object> input) {
		Map<String, Object> normalized = new HashMap<>(
				java.util.Objects.requireNonNull(input, "input must not be null"));
		if (normalized.containsKey("messages")) {
			normalized.put("messages", chatMessages(normalized.get("messages")));
		}
		return normalized;
	}

	private static List<ChatMessage> chatMessages(Object value) {
		java.util.Objects.requireNonNull(value, "messages must not be null");
		if (value instanceof String text) {
			return text.isEmpty() ? List.of()
					: List.of(MessageProvenance.forEntryUser(ChatMessage.user(text)));
		}
		if (value instanceof ChatMessage message) {
			return List.of(MessageProvenance.forEntryUser(message));
		}
		if (value instanceof List<?> messages && messages.stream().allMatch(ChatMessage.class::isInstance)) {
			return messages.stream()
				.map(ChatMessage.class::cast)
				.map(MessageProvenance::forEntryUser)
				.toList();
		}
		throw new IllegalArgumentException("Agent messages must be ARGI ChatMessage values");
	}

	protected abstract StateGraph initGraph() throws GraphStateException;

	/**
	 * Extracts neutral {@link ChatMessage} values from a stream of {@link NodeOutput}.
	 * <p>
	 * This helper method filters the incoming {@link NodeOutput} stream to retain only
	 * {@link StreamingOutput} instances whose {@link OutputType} is intended to expose
	 * messages at the Agent API level ({@code AGENT_MODEL_STREAMING} or
	 * {@code AGENT_TOOL_FINISHED}). A multi-tool turn emits each result independently.
	 * <p>
	 * All other {@link NodeOutput} types (such as tool or hook intermediate outputs) are
	 * intentionally filtered out to avoid leaking graph-level implementation details to
	 * Agent API consumers.
	 * @param stream the stream of {@link NodeOutput} produced during graph execution
	 * @return a {@link Flux} emitting only user-facing {@link ChatMessage} values
	 */
	private Flux<ChatMessage> extractMessages(Flux<NodeOutput> stream) {
		return stream.concatMapIterable(output -> {
			if (output instanceof StreamingOutput<?> streamed) {
				return streamed.messages()
					.stream()
					.filter(message -> isMessageOutputType(streamed.getOutputType())
							|| isUserFacingHookMessage(streamed.getOutputType(), message))
					.toList();
			}
			return List.<ChatMessage>of();
		});
	}

	/**
	 * Returns true for user-facing messages produced by hooks in the streaming path. The
	 * main case is the assistant message generated by {@code ReturnDirectModelHook} when
	 * a {@code returnDirect} tool finishes. Only tool-call-free assistant messages are
	 * surfaced; hook bookkeeping updates (e.g. HITL-approved tool-call messages) stay
	 * hidden.
	 */
	private boolean isUserFacingHookMessage(OutputType type, ChatMessage message) {
		return type == OutputType.AGENT_HOOK_FINISHED && message.role() == ChatRole.ASSISTANT
				&& message.toolCalls().isEmpty();
	}

	/**
	 * Checks whether the given {@link OutputType} indicates a message-type output.
	 * <p>
	 * include {@link OutputType#AGENT_MODEL_STREAMING} and
	 * {@link OutputType#AGENT_TOOL_FINISHED}.
	 * @param type the {@link OutputType} to check
	 * @return true if the output type is a message-type output, false otherwise
	 */
	private boolean isMessageOutputType(OutputType type) {
		return type == OutputType.AGENT_MODEL_STREAMING || type == OutputType.AGENT_TOOL_FINISHED;
	}

}
