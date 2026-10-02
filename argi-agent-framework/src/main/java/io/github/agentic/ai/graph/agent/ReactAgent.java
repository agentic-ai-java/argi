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

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.KeyStrategyFactory;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.SubGraphNode;
import io.github.agentic.ai.graph.action.EdgeAction;
import io.github.agentic.ai.graph.action.NodeActionWithConfig;
import io.github.agentic.ai.graph.agent.exception.AgentException;
import io.github.agentic.ai.graph.agent.factory.AgentBuilderFactory;
import io.github.agentic.ai.graph.agent.factory.DefaultAgentBuilderFactory;
import io.github.agentic.ai.graph.agent.hook.AbstractInterruptableModelHook;
import io.github.agentic.ai.graph.agent.hook.AgentHook;
import io.github.agentic.ai.graph.agent.hook.Hook;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.InstructionAgentHook;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.ModelHook;
import io.github.agentic.ai.graph.agent.hook.ToolInjection;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesAgentHook;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectModelHook;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.StreamingModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.node.AgentLlmNode;
import io.github.agentic.ai.graph.agent.node.AgentToolNode;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointExecutionQueue;
import io.github.agentic.ai.graph.exception.GraphRunnerException;
import io.github.agentic.ai.graph.exception.GraphStateException;
import io.github.agentic.ai.graph.internal.node.Node;
import io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction;
import io.github.agentic.ai.graph.serializer.AgentInstructionMessage;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;
import io.github.agentic.ai.graph.utils.TypeRef;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.github.agentic.ai.graph.RunnableConfig.AGENT_MODEL_NAME;
import static io.github.agentic.ai.graph.RunnableConfig.AGENT_TOOL_NAME;
import static io.github.agentic.ai.graph.OverAllState.MARK_FOR_REMOVAL;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncEdgeAction.edge_async;
import static io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig.node_async;
import static io.github.agentic.ai.graph.agent.hook.InterruptionHook.INTERRUPTION_FEEDBACK_KEY;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.resumeSubGraphId;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.subGraphId;
import static java.lang.String.format;


public class ReactAgent extends BaseAgent {
	Logger logger = LoggerFactory.getLogger(ReactAgent.class);

	private final ConcurrentMap<String, Map<String, Object>> threadIdStateMap;

	private final AgentLlmNode llmNode;

	private final AgentToolNode toolNode;

	private List<? extends Hook> hooks;

	private List<ModelInterceptor> modelInterceptors;

	private List<ToolInterceptor> toolInterceptors;

	private List<StreamingModelInterceptor> streamingInterceptors;

	private String instruction;

	private StateSerializer stateSerializer;

    private final Boolean hasTools;

	public ReactAgent(AgentLlmNode llmNode, AgentToolNode toolNode, CompileConfig compileConfig, Builder builder) {
		super(builder.name, builder.description, builder.includeContents, builder.returnReasoningContents, builder.outputKey, builder.outputKeyStrategy);
		this.threadIdStateMap = new ConcurrentHashMap<>();

		this.instruction = builder.instruction;
		this.llmNode = llmNode;
		this.toolNode = toolNode;
		this.compileConfig = compileConfig;
		this.hooks = builder.hooks;
		this.modelInterceptors = builder.modelInterceptors;
		this.toolInterceptors = builder.toolInterceptors;
		this.streamingInterceptors = builder.streamingInterceptors;
		this.includeContents = builder.includeContents;
		this.inputSchema = builder.inputSchema;
		this.inputType = builder.inputType;
		this.outputSchema = builder.outputSchema;
		this.outputType = builder.outputType;

		// Set state serializer from builder, or use default
        // Default to Jackson serializer for better compatibility and features
        this.stateSerializer = Objects.requireNonNullElseGet(builder.stateSerializer, () -> new SpringAIJacksonStateSerializer(OverAllState::new));

		// Set executor configuration from builder
		this.executor = builder.executor;

		// Set interceptors to nodes
		// Collect interceptors from hooks and merge with current interceptors
		List<ModelInterceptor> mergedModelInterceptors = collectAndMergeModelInterceptors();
		List<ToolInterceptor> mergedToolInterceptors = collectAndMergeToolInterceptors();

		if (mergedModelInterceptors != null && !mergedModelInterceptors.isEmpty()) {
			this.llmNode.setModelInterceptors(mergedModelInterceptors);
		}
		if (mergedToolInterceptors != null && !mergedToolInterceptors.isEmpty()) {
			this.toolNode.setToolInterceptors(mergedToolInterceptors);
		}
		if (this.streamingInterceptors != null && !this.streamingInterceptors.isEmpty()) {
			this.llmNode.setStreamingInterceptors(this.streamingInterceptors);
		}

        // Set tools flag if tool interceptors are present.
        hasTools = toolNode.getToolCallbacks() != null && !toolNode.getToolCallbacks().isEmpty();
	}

	public static Builder builder() {
		return new DefaultAgentBuilderFactory().builder();
	}

	public static Builder builder(AgentBuilderFactory agentBuilderFactory) {
		return agentBuilderFactory.builder();
	}

	public AssistantMessage call(String message) throws GraphRunnerException {
		return doMessageInvoke(message, null);
	}

	public AssistantMessage call(String message, RunnableConfig config) throws GraphRunnerException {
		return doMessageInvoke(message, config);
	}

	public AssistantMessage call(UserMessage message) throws GraphRunnerException {
		return doMessageInvoke(message, null);
	}

	public AssistantMessage call(UserMessage message, RunnableConfig config) throws GraphRunnerException {
		return doMessageInvoke(message, config);
	}

	public AssistantMessage call(List<Message> messages) throws GraphRunnerException {
		return doMessageInvoke(messages, null);
	}

	public AssistantMessage call(List<Message> messages, RunnableConfig config) throws GraphRunnerException {
		return doMessageInvoke(messages, config);
	}

	/**
	 * Calls the agent with the given inputs map and returns the assistant message.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and {@code input},
	 * use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for the
	 * agent. Other keys can be arbitrary and are passed as graph state, e.g. for prompt
	 * placeholders or any other state values.
	 *
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @return the assistant message response
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public AssistantMessage call(Map<String, Object> inputs) throws GraphRunnerException {
		return doMessageInvoke(inputs, null);
	}

	/**
	 * Calls the agent with the given inputs map and runtime config, returns the assistant message.
	 * <p>
	 * When you need to pass additional parameters beyond {@code messages} and {@code input},
	 * use this overload.
	 * <p>
	 * Reserved keys: {@code messages} and {@code input} are used as question/input for the
	 * agent. Other keys can be arbitrary and are passed as graph state, e.g. for prompt
	 * placeholders or any other state values.
	 *
	 * @param inputs the input map (reserved: messages, input; other keys as state)
	 * @param config runtime configuration controlling execution behavior
	 * @return the assistant message response
	 * @throws GraphRunnerException if the graph execution fails
	 */
	public AssistantMessage call(Map<String, Object> inputs, RunnableConfig config) throws GraphRunnerException {
		return doMessageInvoke(inputs, config);
	}

	public void interrupt(RunnableConfig config) {
		updateAgentState(List.of(), config);
	}

	public void interrupt(List<Message> messages, RunnableConfig config) {
		updateAgentState(messages, config);
	}

	public void interrupt(String userMessage, RunnableConfig config) {
		updateAgentState(List.of(UserMessage.builder().text(userMessage).build()), config);
	}

	/**
	 * Updates the agent thread state with interruption feedback.
	 * This method is thread-safe and can be called concurrently with apply() in InterruptionHook.
	 * 
	 * Thread-safety guarantees:
	 * - threadIdStateMap is a ConcurrentHashMap, ensuring thread-safe access
	 * - computeIfAbsent ensures atomic creation of the inner map if it doesn't exist
	 * - The inner map is always a ConcurrentHashMap, ensuring thread-safe put() operations
	 * 
	 * Concurrency behavior:
	 * - If called before apply() processes feedback: the new value will be processed
	 * - If called after apply() removes feedback: the new value will be set for next iteration
	 * - If called concurrently with apply(): the atomic operations ensure no data loss
	 */
	public void updateAgentState(Object state, RunnableConfig config) {
		String threadId = config.threadId().orElseThrow(() -> new IllegalArgumentException("threadId must be provided in RunnableConfig for interruption."));
		// computeIfAbsent is atomic - ensures thread-safe creation of inner map
		// The inner map is always ConcurrentHashMap, ensuring thread-safe put() operations
		Map<String, Object> stateStatus = threadIdStateMap.computeIfAbsent(threadId, k -> new ConcurrentHashMap<>());
		stateStatus.put(INTERRUPTION_FEEDBACK_KEY, state);
	}

	private AssistantMessage doMessageInvoke(Object message, RunnableConfig config) throws GraphRunnerException {
		Map<String, Object> inputs = buildMessageInput(message);
		return extractAssistantMessage(doInvoke(inputs, config));
	}

	private AssistantMessage doMessageInvoke(Map<String, Object> inputs, RunnableConfig config) throws GraphRunnerException {
		return extractAssistantMessage(doInvoke(inputs, config));
	}

	private AssistantMessage extractAssistantMessage(Optional<OverAllState> state) {
		if (StringUtils.hasLength(outputKey)) {
			return state.flatMap(s -> s.value(outputKey))
					.map(msg -> (AssistantMessage) msg)
					.orElseThrow(() -> new IllegalStateException("Output key " + outputKey + " not found in agent state"));
		}

		// Add a validation instance when performing message conversion to
		// avoid potential type conversion exceptions.
		return state.flatMap(s -> s.value("messages"))
				.stream()
				.flatMap(messageList -> ((List<?>) messageList).stream()
						.filter(msg -> msg instanceof AssistantMessage)
						.map(msg -> (AssistantMessage) msg))
				.reduce((first, second) -> second)
				.orElseThrow(() -> new AgentException("No AssistantMessage found in 'messages' state"));
	}

	public StateGraph getStateGraph() {
		return getGraph();
	}

	public CompiledGraph getCompiledGraph() {
		return compiledGraph;
	}

	@Override
	protected Flux<NodeOutput> doStream(Map<String, Object> input, RunnableConfig runnableConfig) {
		CompiledGraph compiledGraph = getAndCompileGraph();
		RunnableConfig config = buildStreamConfig(runnableConfig);
		Optional<BaseCheckpointSaver> saver = compiledGraph.compileConfig.checkpointSaver();
		// Capture the pre-turn snapshot after this subscription acquires its execution slot:
		// the graph itself only executes on subscribe, so the rewind base must be
		// read in the same per-subscription scope. A snapshot taken when the Flux
		// was created would let a cancellation roll the thread back over any state
		// committed between assembly and subscription, and every re-subscription of
		// a cold Flux would reuse that same stale read.
		Supplier<Flux<NodeOutput>> execution = () -> {
			Optional<Checkpoint> preTurnCheckpoint = saver.flatMap(s -> {
				try {
					return s.get(config);
				}
				catch (Exception e) {
					return Optional.empty();
				}
			});
			return compiledGraph.stream(input, config)
					.doFinally(signal -> {
						// Rewind in doFinally rather than doOnCancel: cancellation is
						// cooperative, so the cancelled run may still persist one more
						// checkpoint while unwinding. doFinally runs after the upstream
						// has fully terminated, making the rewind the last writer for
						// this execution instead of a write that a late checkpoint put
						// can overwrite.
						if (signal == SignalType.CANCEL) {
							rewindToPreTurnCheckpoint(saver, config, preTurnCheckpoint);
						}
					});
		};
		return saver.map(checkpointSaver -> CheckpointExecutionQueue.serialize(checkpointSaver, config, execution))
				.orElseGet(() -> Flux.defer(execution));
	}

	/**
	 * A cancelled streaming turn must not leave a half-persisted multi-step fragment (a dangling
	 * assistant tool_call or an orphan tool response) in the checkpoint: the next turn would load
	 * an invalid message sequence that LLM APIs reject. Rewind the thread to the checkpoint taken
	 * before this turn started, so a cancelled turn is treated as if it never happened (issue #25).
	 */
	private void rewindToPreTurnCheckpoint(Optional<BaseCheckpointSaver> saver, RunnableConfig config,
			Optional<Checkpoint> preTurnCheckpoint) {
		if (saver.isEmpty()) {
			return;
		}
		try {
			// No pre-turn checkpoint means this was the first turn on the thread: rewind to an
			// empty state so the cancelled turn leaves nothing behind.
			Checkpoint rewindCheckpoint = preTurnCheckpoint.map(Checkpoint::copyOf)
				.orElseGet(() -> Checkpoint.builder().nodeId(StateGraph.START).nextNodeId(StateGraph.END)
						.state(new HashMap<>()).build());
			saver.get().put(config, rewindCheckpoint);
		}
		catch (Exception e) {
			// Rewinding is best-effort: a failure here must not mask the cancellation itself.
		}
	}

	@Override
	public Node asNode(boolean includeContents, boolean returnReasoningContents) {
		if (this.compiledGraph == null) {
			this.compiledGraph = getAndCompileGraph();
		}
		return new AgentSubGraphNode(this.name, includeContents, returnReasoningContents, this.compiledGraph, this.instruction);
	}

	@Override
	protected StateGraph initGraph() throws GraphStateException {

		if (hooks == null) {
			hooks = new ArrayList<>();
		}

		// Always inject default InstructionAgentHook so instruction is handled in beforeAgent
		List<Hook> effectiveHooks = new ArrayList<>();
		effectiveHooks.add(InstructionAgentHook.create());
		if (shouldInjectReturnDirectHook(hooks)) {
			effectiveHooks.add(new ReturnDirectModelHook());
		}
		effectiveHooks.addAll(hooks);

		// Validate hook uniqueness
		Set<String> hookNames = new HashSet<>();
		for (Hook hook : effectiveHooks) {
			if (!hookNames.add(Hook.getFullHookName(hook))) {
				throw new IllegalArgumentException("Duplicate hook instances found");
			}

			// set agent name to every hook node.
			hook.setAgentName(this.name);
			hook.setAgent(this);
		}

		// Create graph with state serializer
		StateGraph graph = new StateGraph(name, buildMessagesKeyStrategyFactory(effectiveHooks), stateSerializer);

		graph.addNode(AGENT_MODEL_NAME, node_async(this.llmNode));
		if (hasTools) {
			graph.addNode(AGENT_TOOL_NAME, node_async(this.toolNode));
		}

		// some hooks may need tools so they can do some initialization/cleanup on start/end of agent loop
		setupToolsForHooks(effectiveHooks, toolNode);

		// Categorize hooks by position
		List<Hook> beforeAgentHooks = filterHooksByPosition(effectiveHooks, HookPosition.BEFORE_AGENT);
		List<Hook> afterAgentHooks = filterHooksByPosition(effectiveHooks, HookPosition.AFTER_AGENT);
		List<Hook> beforeModelHooks = filterHooksByPosition(effectiveHooks, HookPosition.BEFORE_MODEL);
		List<Hook> afterModelHooks = filterHooksByPosition(effectiveHooks, HookPosition.AFTER_MODEL);

		// Add hook nodes for beforeAgent hooks
		for (Hook hook : beforeAgentHooks) {
			if (hook instanceof AgentHook agentHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".before", agentHook::beforeAgent);
			} else if (hook instanceof MessagesAgentHook messagesAgentHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".before", MessagesAgentHook.beforeAgentAction(messagesAgentHook));
			}
		}

		// Add hook nodes for afterAgent hooks
		for (Hook hook : afterAgentHooks) {
			if (hook instanceof AgentHook agentHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".after", agentHook::afterAgent);
			} else if (hook instanceof MessagesAgentHook messagesAgentHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".after", MessagesAgentHook.afterAgentAction(messagesAgentHook));
			}
		}

		// Add hook nodes for beforeModel hooks
		for (Hook hook : beforeModelHooks) {
			if (hook instanceof AbstractInterruptableModelHook interruptableModelHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".beforeModel", interruptableModelHook);
			} else if (hook instanceof ModelHook modelHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".beforeModel", modelHook::beforeModel);
			} else if (hook instanceof MessagesModelHook messagesModelHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".beforeModel", MessagesModelHook.beforeModelAction(messagesModelHook));
			}
		}

		// Add hook nodes for afterModel hooks
		for (Hook hook : afterModelHooks) {
			if (hook instanceof AbstractInterruptableModelHook interruptableModelHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".afterModel", interruptableModelHook);
			} else if (hook instanceof ModelHook modelHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".afterModel", modelHook::afterModel);
			} else if (hook instanceof MessagesModelHook messagesModelHook) {
				graph.addNode(Hook.getFullHookName(hook) + ".afterModel", MessagesModelHook.afterModelAction(messagesModelHook));
			}
		}

		// Determine node flow
		String entryNode = determineEntryNode(beforeAgentHooks, beforeModelHooks);
		String loopEntryNode = determineLoopEntryNode(beforeModelHooks);
		String loopExitNode = determineLoopExitNode(afterModelHooks);
		String exitNode = determineExitNode(afterAgentHooks);

		// Set up edges
		graph.addEdge(START, entryNode);
		setupHookEdges(graph, beforeAgentHooks, afterAgentHooks, beforeModelHooks, afterModelHooks,
				entryNode, loopEntryNode, loopExitNode, exitNode, this);
		return graph;
	}

	private boolean shouldInjectReturnDirectHook(List<? extends Hook> hooks) {
		String returnDirectHookName = new ReturnDirectModelHook().getName();
		return hooks.stream()
				.noneMatch(hook -> hook instanceof ReturnDirectModelHook
						|| Objects.equals(hook.getName(), returnDirectHookName));
	}

	/**
	 * Setup and inject tools for hooks that implement ToolInjection interface.
	 * Only the tool matching the hook's required tool name or type will be injected.
	 *
	 * @param hooks the list of hooks
	 * @param toolNode the agent tool node containing available tools
	 */
	private void setupToolsForHooks(List<? extends Hook> hooks, AgentToolNode toolNode) {
		if (hooks == null || hooks.isEmpty() || toolNode == null) {
			return;
		}

		List<ToolCallback> availableTools = toolNode.getToolCallbacks();
		if (availableTools == null || availableTools.isEmpty()) {
			return;
		}

		for (Hook hook : hooks) {
			if (hook instanceof ToolInjection toolInjection) {
                ToolCallback toolToInject = findToolForHook(toolInjection, availableTools);
				if (toolToInject != null) {
					toolInjection.injectTool(toolToInject);
				}
			}
		}
	}

	/**
	 * Find the matching tool based on hook's requirements.
	 * Matching priority: 1) by name, 2) by type, 3) first available tool
	 *
	 * @param toolInjection the hook that needs a tool
	 * @param availableTools all available tool callbacks
	 * @return the matching tool, or null if no match found
	 */
	private ToolCallback findToolForHook(ToolInjection toolInjection, List<ToolCallback> availableTools) {
		String requiredToolName = toolInjection.getRequiredToolName();
		Class<? extends ToolCallback> requiredToolType = toolInjection.getRequiredToolType();

		// Priority 1: Match by tool name
		if (requiredToolName != null) {
			for (ToolCallback tool : availableTools) {
				String toolName = tool.getToolDefinition().name();
				if (requiredToolName.equals(toolName)) {
					return tool;
				}
			}
		}

		// Priority 2: Match by tool type
		if (requiredToolType != null) {
			for (ToolCallback tool : availableTools) {
				if (requiredToolType.isInstance(tool)) {
					return tool;
				}
			}
		}

		// Priority 3: If no specific requirement, return the first available tool
		if (requiredToolName == null && requiredToolType == null && !availableTools.isEmpty()) {
			return availableTools.get(0);
		}

		return null;
	}

	/**
	 * Filter hooks by their position based on @HookPositions annotation.
	 * A hook will be included if its getHookPositions() contains the specified position.
	 * If a hook implements Prioritized interface, it will be sorted by its order.
	 * Hooks that don't implement Prioritized will maintain their original order.
	 *
	 * @param hooks the list of hooks to filter
	 * @param position the position to filter by
	 * @return list of hooks that should execute at the specified position
	 */
	private static List<Hook> filterHooksByPosition(List<? extends Hook> hooks, HookPosition position) {
		List<Hook> filtered = hooks.stream()
				.filter(hook -> {
					HookPosition[] positions = hook.getHookPositions();
					return Arrays.asList(positions).contains(position);
				})
				.collect(Collectors.toList());
		
		// Separate hooks that implement Prioritized from those that don't
		List<Hook> prioritizedHooks = new ArrayList<>();
		List<Hook> nonPrioritizedHooks = new ArrayList<>();
		
		for (Hook hook : filtered) {
			if (hook instanceof Prioritized) {
				prioritizedHooks.add(hook);
			} else {
				nonPrioritizedHooks.add(hook);
			}
		}
		
		// Sort prioritized hooks by their order
		prioritizedHooks.sort(Comparator.comparingInt(h -> ((Prioritized) h).getOrder()));
		
		// Combine: prioritized hooks first (sorted), then non-prioritized hooks (original order)
		List<Hook> result = new ArrayList<>(prioritizedHooks);
		result.addAll(nonPrioritizedHooks);
		
		return result;
	}

	private static String determineEntryNode(
			List<Hook> agentHooks,
			List<Hook> modelHooks) {

		if (!agentHooks.isEmpty()) {
			return Hook.getFullHookName(agentHooks.get(0)) + ".before";
		} else if (!modelHooks.isEmpty()) {
			return Hook.getFullHookName(modelHooks.get(0)) + ".beforeModel";
		} else {
			return AGENT_MODEL_NAME;
		}
	}

	private static String determineLoopEntryNode(
			List<Hook> modelHooks) {

		if (!modelHooks.isEmpty()) {
			return Hook.getFullHookName(modelHooks.get(0)) + ".beforeModel";
		} else {
			return AGENT_MODEL_NAME;
		}
	}

	private static String determineLoopExitNode(
			List<Hook> modelHooks) {

		if (!modelHooks.isEmpty()) {
			return Hook.getFullHookName(modelHooks.get(0)) + ".afterModel";
		} else {
			return AGENT_MODEL_NAME;
		}
	}

	private static String determineExitNode(
			List<Hook> agentHooks) {

		if (!agentHooks.isEmpty()) {
			return Hook.getFullHookName(agentHooks.get(agentHooks.size() - 1)) + ".after";
		} else {
			return StateGraph.END;
		}
	}

	private static void setupHookEdges(
			StateGraph graph,
			List<Hook> beforeAgentHooks,
			List<Hook> afterAgentHooks,
			List<Hook> beforeModelHooks,
			List<Hook> afterModelHooks,
			String entryNode,
			String loopEntryNode,
			String loopExitNode,
			String exitNode,
			ReactAgent agentInstance) throws GraphStateException {

		// Chain before_agent hook
		chainHook(graph, beforeAgentHooks, ".before", loopEntryNode, loopEntryNode, exitNode);

		// Chain before_model hook
		chainHook(graph, beforeModelHooks, ".beforeModel", AGENT_MODEL_NAME, loopEntryNode, exitNode);

		// Chain after_model hook (reverse order)
		if (!afterModelHooks.isEmpty()) {
			chainModelHookReverse(graph, afterModelHooks, ".afterModel", AGENT_MODEL_NAME, loopEntryNode, exitNode);
		}

		// Chain after_agent hook (reverse order)
		if (!afterAgentHooks.isEmpty()) {
			chainAgentHookReverse(graph, afterAgentHooks, ".after", exitNode, loopEntryNode, exitNode);
		}

		// Add tool routing if tools exist
		if (agentInstance.hasTools) {
			setupToolRouting(graph, loopExitNode, loopEntryNode, exitNode, agentInstance);
		} else if (!loopExitNode.equals(AGENT_MODEL_NAME)) {
			// No tools but have after_model - connect to exit
			addHookEdge(graph, loopExitNode, exitNode, loopEntryNode, exitNode, afterModelHooks.get(afterModelHooks.size() - 1).canJumpTo());
		} else {
			// No tools and no after_model - direct to exit
			graph.addEdge(loopExitNode, exitNode);
		}
	}

	private static void chainModelHookReverse(
			StateGraph graph,
			List<Hook> hooks,
			String nameSuffix,
			String defaultNext,
			String modelDestination,
			String endDestination) throws GraphStateException {

		graph.addEdge(defaultNext, Hook.getFullHookName(hooks.get(hooks.size() - 1)) + nameSuffix);

		for (int i = hooks.size() - 1; i > 0; i--) {
			Hook m1 = hooks.get(i);
			Hook m2 = hooks.get(i - 1);
			addHookEdge(graph,
					Hook.getFullHookName(m1) + nameSuffix,
					Hook.getFullHookName(m2) + nameSuffix,
					modelDestination, endDestination,
					m1.canJumpTo());
		}
	}

	private static void chainAgentHookReverse(
			StateGraph graph,
			List<Hook> hooks,
			String nameSuffix,
			String defaultNext,
			String modelDestination,
			String endDestination) throws GraphStateException {
		if (!hooks.isEmpty()) {
			Hook first = hooks.get(0);
			addHookEdge(graph,
					Hook.getFullHookName(first) + nameSuffix,
					StateGraph.END,
					modelDestination, endDestination,
					first.canJumpTo());
		}

		for (int i = hooks.size() - 1; i > 0; i--) {
			Hook m1 = hooks.get(i);
			Hook m2 = hooks.get(i - 1);
			addHookEdge(graph,
					Hook.getFullHookName(m1) + nameSuffix,
					Hook.getFullHookName(m2) + nameSuffix,
					modelDestination, endDestination,
					m1.canJumpTo());
		}
	}

	private static void chainHook(
			StateGraph graph,
			List<Hook> hooks,
			String nameSuffix,
			String defaultNext,
			String modelDestination,
			String endDestination) throws GraphStateException {

		for (int i = 0; i < hooks.size() - 1; i++) {
			Hook m1 = hooks.get(i);
			Hook m2 = hooks.get(i + 1);
			addHookEdge(graph,
					Hook.getFullHookName(m1) + nameSuffix,
					Hook.getFullHookName(m2) + nameSuffix,
					modelDestination, endDestination,
					m1.canJumpTo());
		}

		if (!hooks.isEmpty()) {
			Hook last = hooks.get(hooks.size() - 1);
			addHookEdge(graph,
					Hook.getFullHookName(last) + nameSuffix,
					defaultNext,
					modelDestination, endDestination,
					last.canJumpTo());
		}
	}

	private static void addHookEdge(
			StateGraph graph,
			String name,
			String defaultDestination,
			String modelDestination,
			String endDestination,
			List<JumpTo> canJumpTo) throws GraphStateException {

		if (canJumpTo != null && !canJumpTo.isEmpty()) {
			EdgeAction router = state -> {
				JumpTo jumpTo = readAndClearJumpTo(state);
				return resolveJump(jumpTo, modelDestination, endDestination, defaultDestination);
			};

			Map<String, String> destinations = new HashMap<>();
			destinations.put(defaultDestination, defaultDestination);

			if (canJumpTo.contains(JumpTo.end)) {
				destinations.put(endDestination, endDestination);
			}
			if (canJumpTo.contains(JumpTo.tool)) {
				destinations.put(AGENT_TOOL_NAME, AGENT_TOOL_NAME);
			}
			if (canJumpTo.contains(JumpTo.model) && !name.equals(modelDestination)) {
				destinations.put(modelDestination, modelDestination);
			}

			graph.addConditionalEdges(name, edge_async(router), destinations);
		} else {
			graph.addEdge(name, defaultDestination);
		}
	}

	private static void setupToolRouting(
			StateGraph graph,
			String loopExitNode,
			String loopEntryNode,
			String exitNode,
			ReactAgent agentInstance) throws GraphStateException {

		// Model to tools routing
		graph.addConditionalEdges(loopExitNode, edge_async(agentInstance.makeModelToTools(loopEntryNode, exitNode)), Map.of(AGENT_TOOL_NAME, AGENT_TOOL_NAME, exitNode, exitNode, loopEntryNode, loopEntryNode));

		// Tools to model routing
		graph.addConditionalEdges(AGENT_TOOL_NAME, edge_async(agentInstance.makeToolsToModelEdge(loopEntryNode, exitNode)), Map.of(loopEntryNode, loopEntryNode, exitNode, exitNode));
	}

	private static JumpTo readAndClearJumpTo(OverAllState state) {
		Object jumpToValue = state.value("jump_to").orElse(null);
		if (jumpToValue != null) {
			state.updateState(Map.of("jump_to", MARK_FOR_REMOVAL));
		}
		if (jumpToValue instanceof JumpTo jumpTo) {
			return jumpTo;
		}
		if (jumpToValue instanceof String jumpTo) {
			return JumpTo.fromStringOrNull(jumpTo);
		}
		return null;
	}

	private static String resolveJump(JumpTo jumpTo, String modelDestination, String endDestination, String defaultDestination) {
		if (jumpTo == null) {
			return defaultDestination;
		}

		return switch (jumpTo) {
			case model -> modelDestination;
			case end -> endDestination;
			case tool -> AGENT_TOOL_NAME;
		};
	}

	private KeyStrategyFactory buildMessagesKeyStrategyFactory(List<? extends Hook> hooks) {
		return () -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			if (outputKey != null && !outputKey.isEmpty()) {
				keyStrategyHashMap.put(outputKey, outputKeyStrategy == null ? new ReplaceStrategy() : outputKeyStrategy);
			}
			keyStrategyHashMap.put("messages", new AppendStrategy());

			// Iterate through hooks and collect their key strategies
			if (hooks != null) {
				for (Hook hook : hooks) {
					Map<String, KeyStrategy> hookStrategies = hook.getKeyStrategys();
					if (hookStrategies != null && !hookStrategies.isEmpty()) {
						keyStrategyHashMap.putAll(hookStrategies);
					}
				}
			}

			return keyStrategyHashMap;
		};
	}

	private EdgeAction makeModelToTools(String modelDestination, String endDestination) {
		return state -> {
			// Priority 1: Check for jump_to instruction from hooks
			// This allows afterModel hooks to control workflow execution
			JumpTo jumpTo = readAndClearJumpTo(state);

			// If a valid jump_to instruction exists, execute it immediately
			if (jumpTo != null) {
				return switch (jumpTo) {
					case model -> modelDestination;
					case end -> endDestination;
					case tool -> AGENT_TOOL_NAME;
				};
			}
			
			// Priority 2: Check message content for tool calls
			List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
			if (messages.isEmpty()) {
				logger.warn("No messages found in state when routing from model to tools");
				return endDestination;
			}
			Message lastMessage = messages.get(messages.size() - 1);

			// 1. Check the last message type
			if (lastMessage instanceof AssistantMessage assistantMessage) {
				// 2. If last message is AssistantMessage
				if (assistantMessage.hasToolCalls()) {
					return AGENT_TOOL_NAME;
				} else {
					return endDestination;
				}
			} else if (lastMessage instanceof ToolResponseMessage) {
				// 3. If last message is ToolResponseMessage
				if (messages.size() < 2) {
					// Should not happen in a valid ReAct loop, but as a safeguard.
					throw new RuntimeException("Less than 2 messages in state when last message is ToolResponseMessage");
				}

				Message secondLastMessage = messages.get(messages.size() - 2);
				if (secondLastMessage instanceof AssistantMessage) {
					AssistantMessage assistantMessage = (AssistantMessage) secondLastMessage;
					ToolResponseMessage toolResponseMessage = (ToolResponseMessage) lastMessage;

					if (assistantMessage.hasToolCalls()) {
						Set<String> requestedToolIds = assistantMessage.getToolCalls().stream()
								.map(AssistantMessage.ToolCall::id)
								.collect(java.util.stream.Collectors.toSet());

						Set<String> executedToolIds = toolResponseMessage.getResponses().stream()
								.map(ToolResponseMessage.ToolResponse::id)
								.collect(java.util.stream.Collectors.toSet());

						if (executedToolIds.containsAll(requestedToolIds)) {
							return modelDestination; // All requested tools were executed or responded
						} else {
							return AGENT_TOOL_NAME; // Some tools are still pending
						}
					}
				}
			}

			return endDestination;
		};
	}

	private EdgeAction makeToolsToModelEdge(String modelDestination, String endDestination) {
		return state -> {
			// Tool execution completed successfully, route back to the loop entry. The
			// default ReturnDirectModelHook handles direct tool responses before the next
			// model call.
			return modelDestination;
		};
	}

	private ToolResponseMessage fetchLastToolResponseMessage(OverAllState state) {
		List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());

		ToolResponseMessage toolResponseMessage = null;

		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof ToolResponseMessage) {
				toolResponseMessage = (ToolResponseMessage) messages.get(i);
				break;
			}
		}

		return toolResponseMessage;
	}

	/**
	 * Collects model interceptors from hooks (ModelHook and AgentHook) and merges them
	 * with the current model interceptors.
	 * <p>
	 * If interceptors with the same name exist, the ones from ReactAgent configuration
	 * take priority over those from hooks.
	 *
	 * @return merged list of model interceptors, or null if no interceptors exist
	 */
	private List<ModelInterceptor> collectAndMergeModelInterceptors() {
		List<ModelInterceptor> result = new ArrayList<>();
		Set<String> addedNames = new HashSet<>();

		// Add current model interceptors if they exist (higher priority)
		if (this.modelInterceptors != null && !this.modelInterceptors.isEmpty()) {
			for (ModelInterceptor interceptor : this.modelInterceptors) {
				result.add(interceptor);
				addedNames.add(interceptor.getName());
			}
		}

		// Collect interceptors from hooks (skip if name already exists)
		if (this.hooks != null && !this.hooks.isEmpty()) {
			for (Hook hook : this.hooks) {
				List<ModelInterceptor> hookInterceptors = hook.getModelInterceptors();
				if (hookInterceptors != null && !hookInterceptors.isEmpty()) {
					for (ModelInterceptor interceptor : hookInterceptors) {
						String name = interceptor.getName();
						if (!addedNames.contains(name)) {
							result.add(interceptor);
							addedNames.add(name);
						} else {
							logger.info("Skipping model interceptor '{}' from hook '{}' because an interceptor with the same name already exists in ReactAgent configuration", name, hook.getName());
						}
					}
				}
			}
		}

		return result.isEmpty() ? null : result;
	}

	/**
	 * Collects tool interceptors from hooks (ModelHook and AgentHook) and merges them
	 * with the current tool interceptors.
	 * <p>
	 * If interceptors with the same name exist, the ones from ReactAgent configuration
	 * take priority over those from hooks.
	 *
	 * @return merged list of tool interceptors, or null if no interceptors exist
	 */
	private List<ToolInterceptor> collectAndMergeToolInterceptors() {
		List<ToolInterceptor> result = new ArrayList<>();
		Set<String> addedNames = new HashSet<>();

		// Add current tool interceptors if they exist (higher priority)
		if (this.toolInterceptors != null && !this.toolInterceptors.isEmpty()) {
			for (ToolInterceptor interceptor : this.toolInterceptors) {
				result.add(interceptor);
				addedNames.add(interceptor.getName());
			}
		}

		// Collect interceptors from hooks (skip if name already exists)
		if (this.hooks != null && !this.hooks.isEmpty()) {
			for (Hook hook : this.hooks) {
				List<ToolInterceptor> hookInterceptors = hook.getToolInterceptors();
				if (hookInterceptors != null && !hookInterceptors.isEmpty()) {
					for (ToolInterceptor interceptor : hookInterceptors) {
						String name = interceptor.getName();
						if (!addedNames.contains(name)) {
							result.add(interceptor);
							addedNames.add(name);
						} else {
							logger.info("Skipping tool interceptor '{}' from hook '{}' because an interceptor with the same name already exists in ReactAgent configuration", name, hook.getName());
						}
					}
				}
			}
		}

		return result.isEmpty() ? null : result;
	}

	public String instruction() {
		return instruction;
	}

	public void setInstruction(String instruction) {
		this.instruction = instruction;
		llmNode.setInstruction(instruction);
	}

	public void setSystemPrompt(String systemPrompt) {
		llmNode.setSystemPrompt(systemPrompt);
	}

	public Map<String, Object> getThreadState(String threadId) {
		return threadIdStateMap.get(threadId);
	}

	public class AgentToSubCompiledGraphNodeAdapter implements NodeActionWithConfig, ResumableSubGraphAction {

		private String nodeId;

		private boolean includeContents;

		private boolean returnReasoningContents;

		private String instruction;

		private CompiledGraph childGraph;

		private CompileConfig parentCompileConfig;

		public AgentToSubCompiledGraphNodeAdapter(String nodeId, boolean includeContents, boolean returnReasoningContents,
				CompiledGraph childGraph, String instruction, CompileConfig parentCompileConfig) {
			this.nodeId = nodeId;
			this.includeContents = includeContents;
			this.returnReasoningContents = returnReasoningContents;
			this.instruction = instruction;
			this.childGraph = childGraph;
			this.parentCompileConfig = parentCompileConfig;
		}

		@Override
		public String getResumeSubGraphId() {
			return resumeSubGraphId(nodeId);
		}

		@Override
		public Map<String, Object> apply(OverAllState parentState, RunnableConfig config) throws Exception {
			final boolean resumeSubgraph = config.metadata(resumeSubGraphId(nodeId), new TypeRef<Boolean>() {}).orElse(false);

			RunnableConfig subGraphRunnableConfig = getSubGraphRunnableConfig(config);
			Flux<GraphResponse<NodeOutput>> subGraphResult;
			Object parentMessages = null;

			// Instruction is always injected by InstructionAgentHook in beforeAgent; do not add here
			if (includeContents) {
				Map<String, Object> stateForChild = new HashMap<>(parentState.data());
				List<Object> newMessages;
				if (stateForChild.get("messages") != null) {
					newMessages = new ArrayList<>((List<Object>)stateForChild.remove("messages"));
				} else {
					newMessages = new ArrayList<>();
				}
				stateForChild.put("messages", newMessages);
				subGraphResult = childGraph.graphResponseStream(stateForChild, subGraphRunnableConfig);
			} else {
				Map<String, Object> stateForChild = new HashMap<>(parentState.data());
				parentMessages = stateForChild.remove("messages");
				subGraphResult = childGraph.graphResponseStream(stateForChild, subGraphRunnableConfig);
			}

			Map<String, Object> result = new HashMap<>();

			String outputKeyToParent = StringUtils.hasLength(ReactAgent.this.outputKey) ? ReactAgent.this.outputKey : "messages";
			result.put(outputKeyToParent, getGraphResponseFlux(parentState, subGraphResult, null));
			return result;
		}

		private @NonNull Flux<GraphResponse<NodeOutput>> getGraphResponseFlux(OverAllState parentState, Flux<GraphResponse<NodeOutput>> subGraphResult, AgentInstructionMessage instructionMessage) {
			// Use buffer(2, 1) to create sliding windows: [elem0, elem1], [elem1, elem2], ..., [elemN-1, elemN], [elemN]
			// For windows with 2 elements, emit the first (previous element)
			// For the last window with 1 element, process it specially
			return subGraphResult
					.buffer(2, 1)
					.flatMap(window -> {
						if (window.size() == 1) {
							// Last window: process the last element with message filtering
							return Flux.just(processLastResponse(window.get(0), parentState, instructionMessage));
						} else {
							// Regular window: emit the first element (previous, delayed by one)
							return Flux.just(window.get(0));
						}
					}, 1); // Concurrency of 1 to maintain order
		}

		/**
		 * Process the last response by filtering messages based on parent state and returnReasoningContents flag.
		 *
		 * @param lastResponse the last response from sub-graph
		 * @param parentState the parent state containing messages to filter out
		 * @return processed GraphResponse with filtered messages
		 */
		private GraphResponse<NodeOutput> processLastResponse(GraphResponse<NodeOutput> lastResponse, OverAllState parentState, AgentInstructionMessage instructionMessage) {
			if (lastResponse == null) {
				return lastResponse;
			}
			
			if (lastResponse.resultValue().isPresent()) {
				Object resultValue = lastResponse.resultValue().get();
				if (resultValue instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> resultMap = (Map<String, Object>) resultValue;
					if (resultMap.get("messages") instanceof List) {
						@SuppressWarnings("unchecked")
						List<Object> messages = new ArrayList<>((List<Object>) resultMap.get("messages"));
						if (!messages.isEmpty()) {
							parentState.value("messages").ifPresent(parentMsgs -> {
								if (parentMsgs instanceof List) {
									messages.removeAll((List<?>) parentMsgs);
								}
							});

							List<Object> finalMessages;
							if (returnReasoningContents) {
								finalMessages = messages;
							} else {
								if (!messages.isEmpty()) {
									if (instructionMessage != null) {
										finalMessages = new ArrayList<>();
										finalMessages.add(instructionMessage);
										finalMessages.add(messages.get(messages.size() - 1));
									} else {
										finalMessages = List.of(messages.get(messages.size() - 1));
									}
								} else {
									finalMessages = List.of();
								}
							}

							Map<String, Object> newResultMap = new HashMap<>(resultMap);
							newResultMap.put("messages", finalMessages);
							return GraphResponse.done(newResultMap);
						}
					}
				}
			}
			return lastResponse;
		}

		private RunnableConfig getSubGraphRunnableConfig(RunnableConfig config) {
			RunnableConfig subGraphRunnableConfig = RunnableConfig.builder(config)
					.checkPointId(null)
					.nextNode(null)
					.addMetadata("_AGENT_", subGraphId(nodeId)) // subGraphId is the same as the name of the agent that created it
					.build();
			subGraphRunnableConfig.clearContext();
			var parentSaver = parentCompileConfig.checkpointSaver();
			var subGraphSaver = childGraph.compileConfig.checkpointSaver();

			if (subGraphSaver.isPresent()) {
				if (parentSaver.isEmpty()) {
					throw new IllegalStateException("Missing CheckpointSaver in parent graph!");
				}

				// Check saver are the same instance
				if (parentSaver.get() == subGraphSaver.get()) {
					subGraphRunnableConfig = RunnableConfig.builder(config)
							.threadId(config.threadId()
									.map(threadId -> format("%s_%s", threadId, subGraphId(nodeId)))
									.orElseGet(() -> subGraphId(nodeId)))
							.nextNode(null)
							.checkPointId(null)
							.addMetadata("_AGENT_", subGraphId(nodeId)) // subGraphId is the same as the name of the agent that created it
							.build();
					subGraphRunnableConfig.clearContext();
				}
			}
			return subGraphRunnableConfig;
		}
	}

	/**
	 * Internal class that adapts a ReactAgent to be used as a SubGraph Node.
	 */
	private class AgentSubGraphNode extends Node implements SubGraphNode {

		private final CompiledGraph subGraph;

		public AgentSubGraphNode(String id, boolean includeContents, boolean returnReasoningContents, CompiledGraph subGraph, String instruction) {
			super(Objects.requireNonNull(id, "id cannot be null"),
					(config) -> node_async(new AgentToSubCompiledGraphNodeAdapter(id, includeContents, returnReasoningContents, subGraph, instruction, config)));
			this.subGraph = subGraph;
		}

		@Override
		public StateGraph subGraph() {
			return subGraph.stateGraph;
		}

		@Override
		public Map<String, KeyStrategy> keyStrategies() {
			return subGraph.getKeyStrategyMap();
		}
	}
}
