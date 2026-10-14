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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.action.Command;
import io.github.agentic.ai.graph.internal.edge.EdgeValue;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.VersionedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.VersionedCheckpointScope;
import io.github.agentic.ai.graph.exception.RunnableErrors;
import io.github.agentic.ai.graph.internal.node.ParallelNode;
import io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction;
import io.github.agentic.ai.graph.state.StateSnapshot;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.state.ReplaceAllWith;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.graph.utils.SystemClock;
import io.github.agentic.ai.graph.utils.TypeRef;

import io.github.agentic.ai.api.chat.Usage;

import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.NODE_AFTER;
import static io.github.agentic.ai.graph.StateGraph.NODE_BEFORE;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.StateGraph.ERROR;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

/**
 * Context class to manage the state during graph execution
 */
public class GraphRunnerContext {

	public static final String INTERRUPT_AFTER = "__INTERRUPTED__";

	private static final Logger log = LoggerFactory.getLogger(GraphRunner.class);

	final CompiledGraph compiledGraph;

	final AtomicInteger iteration = new AtomicInteger(0);

	OverAllState overallState;

	RunnableConfig config;

	String currentNodeId;

	String nextNodeId;

	Usage tokenUsage;

	String resumeFrom;

	/**
	 * Set when the downstream subscriber cancels the run. In-flight async node
	 * completions may still land afterwards; once set, no further checkpoint must be
	 * persisted, so a cancelled turn cannot leave a half-persisted fragment (e.g. a
	 * dangling assistant tool_call) that would make the next turn's message sequence
	 * invalid (issue #25).
	 */
	private volatile boolean cancelled;

	private final Object checkpointWriteMonitor = new Object();

	private final VersionedCheckpointScope checkpointScope;

	public void markCancelled() {
		this.cancelled = true;
		// Wait for a checkpoint write that passed the cancellation check to finish.
		// The cancellation rewind runs after this method returns, so it is guaranteed
		// to be the final write for this execution.
		synchronized (checkpointWriteMonitor) {
			// The monitor boundary provides the required happens-before relationship.
		}
	}

	public boolean isCancelled() {
		return cancelled;
	}

	ReturnFromEmbed returnFromEmbed;

	public GraphRunnerContext(OverAllState initialState, RunnableConfig config, CompiledGraph compiledGraph)
			throws Exception {
		this(initialState, config, compiledGraph, null);
	}

	public GraphRunnerContext(OverAllState initialState, RunnableConfig config, CompiledGraph compiledGraph,
			VersionedCheckpointScope checkpointScope) throws Exception {
		this.compiledGraph = compiledGraph;
		this.config = config;
		this.checkpointScope = checkpointScope;

		if (config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY).isPresent()
				|| config.checkPointId().isPresent()) {
			initializeFromResume(initialState, config);
		}
		else {
			initializeFromStart(initialState, config);
		}
		bindExecutionGuard();
	}

	private void initializeFromResume(OverAllState initialState, RunnableConfig config) throws Exception {
		log.trace("RESUME REQUEST");

		var saver = compiledGraph.compileConfig.checkpointSaver()
			.orElseThrow(() -> new IllegalStateException("Resume request without a configured checkpoint saver!"));
		Optional<Checkpoint> checkpointOptional = checkpointSnapshot(saver, config).checkpoint();
		if (checkpointOptional.isEmpty()) {
			// 多 agent 编排下，父图恢复时只有被中断的子 agent 有 checkpoint；其它子 agent
			// 从未中断、没有 checkpoint，应作为新会话初始化而不是抛异常。
			log.debug(
					"Resume config present but no checkpoint found for this (sub)graph; falling back to fresh start.");
			initializeFromStart(initialState, config);
			return;
		}
		var checkpoint = checkpointOptional.get();

		var startCheckpointNextNodeAction = compiledGraph.getNodeAction(checkpoint.getNextNodeId());
		if (startCheckpointNextNodeAction instanceof ResumableSubGraphAction resumableAction) {
			// RESUME FORM SUBGRAPH DETECTED
			this.config = RunnableConfig.builder(config)
				.checkPointId(null) // Reset checkpoint id
				.addMetadata(resumableAction.getResumeSubGraphId(), true) // add metadata
																			// for
				// sub graph
				.build();
			this.config.clearContext();
			bindExecutionGuard();
		}
		else {
			// Reset checkpoint id
			this.config = config.withCheckPointId(null);
			bindExecutionGuard();
		}

		this.currentNodeId = null;
		this.nextNodeId = checkpoint.getNextNodeId();
		this.overallState = stateCreate(cloneState(checkpoint.getState()).data(), initialState);
		this.overallState.input(initialState.data());
		this.resumeFrom = checkpoint.getNodeId();

		log.trace("RESUME FROM {}", checkpoint.getNodeId());
	}

	private void initializeFromStart(OverAllState initialState, RunnableConfig config) throws Exception {
		log.trace("START");

		Map<String, Object> inputs = initialState.data();
		if (!CollectionUtils.isEmpty(inputs)) {
			// Simple validation without accessing protected method
			log.debug("Initializing with inputs: {}", inputs.keySet());
		}

		// Use CompiledGraph's getInitialState method
		if (checkpointScope != null && compiledGraph.compileConfig.checkpointSaver()
			.orElse(null) instanceof VersionedCheckpointSaver saver) {
			checkpointScope.validate(saver, config);
			Map<String, Object> base = checkpointScope.snapshot().checkpoint().map(Checkpoint::getState).map(state -> {
				try {
					return cloneState(state).data();
				}
				catch (Exception ex) {
					throw new IllegalStateException("Failed to clone checkpoint state", ex);
				}
			}).orElseGet(HashMap::new);
			this.overallState = stateCreate(OverAllState.updateState(base, inputs, getKeyStrategyMap()), initialState);
		}
		else {
			this.overallState = stateCreate(compiledGraph.getInitialState(inputs, config), initialState);
		}
		this.currentNodeId = START;
		this.nextNodeId = null;
	}

	// FIXME, duplicated method with CompiledGraph.stateCreate, need to have a
	// unified way of when and how to do OverallState creation.
	// This temporary fix is to make sure the message provided by user is always the
	// last element in the messages list.
	private OverAllState stateCreate(Map<String, Object> inputs, OverAllState initialState) {
		// Creates a new OverAllState instance using key strategies from the graph and
		// provided input data.
		return OverAllStateBuilder.builder()
			.withKeyStrategies(initialState.keyStrategies())
			.withData(inputs)
			.withStore(initialState.getStore())
			.build();
	}

	private CheckpointSnapshot checkpointSnapshot(BaseCheckpointSaver saver, RunnableConfig config) throws Exception {
		if (saver instanceof VersionedCheckpointSaver versionedSaver) {
			if (checkpointScope == null) {
				throw new IllegalStateException("Missing versioned checkpoint scope");
			}
			checkpointScope.validate(versionedSaver, config);
			return checkpointScope.snapshot(config);
		}
		Optional<Checkpoint> checkpoint = saver.get(config);
		return new CheckpointSnapshot(checkpoint, checkpoint.isPresent() ? 1 : 0);
	}

	// Helper methods
	public boolean shouldStop() {
		return nextNodeId == null && currentNodeId == null;
	}

	public boolean isMaxIterationsReached() {
		return iteration.incrementAndGet() > compiledGraph.getMaxIterations();
	}

	public boolean isStartNode() {
		return START.equals(currentNodeId);
	}

	public boolean isEndNode() {
		return END.equals(nextNodeId);
	}

	public boolean shouldInterrupt() {
		return shouldInterruptBefore(nextNodeId, currentNodeId) || shouldInterruptAfter(currentNodeId, nextNodeId);
	}

	private boolean shouldInterruptBefore(String nodeId, String previousNodeId) {
		if (previousNodeId == null)
			return false;
		return compiledGraph.compileConfig.interruptsBefore().contains(nodeId);
	}

	private boolean shouldInterruptAfter(String nodeId, String previousNodeId) {
		if (nodeId == null || Objects.equals(nodeId, previousNodeId))
			return false;
		return (compiledGraph.compileConfig.interruptBeforeEdge() && Objects.equals(nodeId, INTERRUPT_AFTER))
				|| compiledGraph.compileConfig.interruptsAfter().contains(nodeId);
	}

	// ================================================================================================================
	// Node Action Methods
	// ================================================================================================================

	public AsyncNodeActionWithConfig getNodeAction(String nodeId) {
		return compiledGraph.getNodeAction(nodeId);
	}

	public Command getEntryPoint() throws Exception {
		var entryPoint = compiledGraph.getEdge(START);
		return nextNodeId(entryPoint, overallState.data(), "entryPoint");
	}

	public Command nextNodeId(String nodeId, Map<String, Object> state) throws Exception {
		return nextNodeId(compiledGraph.getEdge(nodeId), state, nodeId);
	}

	private Command nextNodeId(EdgeValue route, Map<String, Object> state, String nodeId) throws Exception {
		if (route == null) {
			throw RunnableErrors.missingEdge.exception(nodeId);
		}
		if (route.id() != null) {
			return new Command(route.id(), state);
		}
		if (route.value() != null) {
			var edgeCondition = route.value();

			// Check if this is a multi-command action
			if (edgeCondition.isMultiCommand()) {
				// Multi-command action - route to ConditionalParallelNode
				// The ConditionalParallelNode is dynamically created in CompiledGraph
				String conditionalParallelNodeId = ParallelNode.formatNodeId(nodeId);
				// Return Command pointing to ConditionalParallelNode
				// The ConditionalParallelNode will handle the MultiCommand internally
				return new Command(conditionalParallelNodeId, state);
			}
			else {
				// Single Command action
				var singleAction = edgeCondition.singleAction();
				var command = singleAction.apply(this.overallState, config).get();

				// Single Command case
				var newRoute = command.gotoNode();
				String result = route.value().mappings().get(newRoute);
				if (result == null) {
					throw RunnableErrors.missingNodeInEdgeMapping.exception(nodeId, newRoute);
				}
				this.mergeIntoCurrentState(command.update());
				return new Command(result, state);
			}
		}
		throw RunnableErrors.executionError.exception(format("invalid edge value for nodeId: [%s] !", nodeId));
	}

	// ================================================================================================================
	// Checkpoint Methods
	// ================================================================================================================

	public Optional<Checkpoint> addCheckpoint(String nodeId, String nextNodeId) throws Exception {
		if (cancelled) {
			// The run was cancelled by the subscriber: in-flight completions must not
			// persist
			// any further state, otherwise a cancelled turn is only half-recorded (issue
			// #25).
			return Optional.empty();
		}
		if (compiledGraph.compileConfig.checkpointSaver().isPresent()) {
			var cp = Checkpoint.builder()
				.nodeId(nodeId)
				.state(cloneState(overallState.data()))
				.nextNodeId(nextNodeId)
				.build();
			synchronized (checkpointWriteMonitor) {
				// Re-check after the (potentially expensive) state clone and while
				// holding
				// the write monitor. Cancellation sets the flag before waiting on this
				// same
				// monitor, closing the check-then-put race.
				if (cancelled) {
					return Optional.empty();
				}
				// Force checkPointId to null to ensure we append a new checkpoint instead
				// of
				// replacing the current one
				RunnableConfig appendConfig = RunnableConfig.builder(config).checkPointId(null).build();
				BaseCheckpointSaver saver = compiledGraph.compileConfig.checkpointSaver().get();
				if (saver instanceof VersionedCheckpointSaver versionedSaver) {
					if (checkpointScope == null) {
						throw new IllegalStateException("Missing versioned checkpoint scope");
					}
					checkpointScope.validate(versionedSaver, appendConfig);
					this.config = checkpointScope.put(appendConfig, cp);
					bindExecutionGuard();
				}
				else {
					this.config = saver.put(appendConfig, cp);
					bindExecutionGuard();
				}
				return Optional.of(cp);
			}
		}
		return Optional.empty();
	}

	public BaseCheckpointSaver.Tag releaseCheckpoint() throws Exception {
		BaseCheckpointSaver saver = compiledGraph.compileConfig.checkpointSaver()
			.orElseThrow(() -> new IllegalStateException("Missing CheckpointSaver!"));
		if (saver instanceof VersionedCheckpointSaver versionedSaver) {
			if (checkpointScope == null) {
				throw new IllegalStateException("Missing versioned checkpoint scope");
			}
			checkpointScope.validate(versionedSaver, config);
			return checkpointScope.release(config);
		}
		return saver.release(config);
	}

	// ================================================================================================================
	// Output Building Methods
	// ================================================================================================================

	public NodeOutput buildOutput(String nodeId, Optional<Checkpoint> checkpoint) throws Exception {
		if (checkpoint.isPresent() && config.streamMode() == CompiledGraph.StreamMode.SNAPSHOTS) {
			return StateSnapshot.of(getKeyStrategyMap(), checkpoint.get(), config,
					compiledGraph.stateGraph.getStateSerializer().stateFactory());
		}
		return buildNodeOutput(nodeId);
	}

	public StreamingOutput<?> buildStreamingOutput(ChatMessage message, Object originData, String nodeId,
			boolean streaming) {
		OutputType outputType = OutputType.from(streaming, nodeId);
		// Reasoning models stream the thinking phase as blank-content chunks carrying
		// reasoning metadata; stamp an explicit marker so consumers can route thinking vs
		// final-answer chunks (issue #34).
		if (outputType == OutputType.AGENT_MODEL_STREAMING) {
			message = stampReasoningMarker(message);
		}
		StreamingOutput<?> output = new StreamingOutput<>(message == null ? List.of() : List.of(message), originData,
				nodeId, (String) config.metadata("_AGENT_").orElse(""), streamingStateSnapshot(), outputType);
		if (originData instanceof ChatResponse response) {
			output.setTokenUsage(response.usage());
		}
		output.setSubGraph(true);
		return output;
	}

	/**
	 * Metadata key marking whether a streamed model chunk belongs to the reasoning
	 * (thinking) phase rather than the final answer.
	 */
	public static final String IS_REASONING_METADATA_KEY = "isReasoning";

	/**
	 * Metadata key carrying the reasoning (thinking) text of a chunk, normalized from the
	 * neutral assistant message metadata.
	 */
	public static final String REASONING_CONTENT_METADATA_KEY = ChatMetadataKeys.REASONING;

	private ChatMessage stampReasoningMarker(ChatMessage message) {
		if (message == null || message.role() != ChatRole.ASSISTANT) {
			return message;
		}
		ChatMessage assistantMessage = message;
		String reasoningContent = extractReasoningContent(assistantMessage);
		boolean isReasoning = reasoningContent != null && !reasoningContent.isBlank()
				&& (assistantMessage.text() == null || assistantMessage.text().isEmpty());
		Map<String, Object> metadata = new HashMap<>(assistantMessage.metadata());
		metadata.put(IS_REASONING_METADATA_KEY, isReasoning);
		if (reasoningContent != null) {
			metadata.putIfAbsent(REASONING_CONTENT_METADATA_KEY, reasoningContent);
		}
		return assistantMessage.withMetadata(metadata);
	}

	private static String extractReasoningContent(ChatMessage message) {
		Object reasoningContent = message.metadata().get(REASONING_CONTENT_METADATA_KEY);
		return reasoningContent == null ? null : reasoningContent.toString();
	}

	public StreamingOutput<?> buildStreamingOutput(Object originData, String nodeId, boolean streaming) {
		if (originData instanceof ChatResponse response) {
			ChatMessage message = response.messages().isEmpty() ? null : response.messages().get(0);
			return buildStreamingOutput(message, response, nodeId, streaming);
		}
		// Create StreamingOutput with chunk only
		OutputType outputType = OutputType.from(streaming, nodeId);
		StreamingOutput<?> output = new StreamingOutput<>(originData, nodeId,
				(String) config.metadata("_AGENT_").orElse(""), streamingStateSnapshot(), outputType);
		output.setSubGraph(true);
		return output;
	}

	private OverAllState streamingStateSnapshot() {
		// Stream results can be merged back into state. Never retain the live state map,
		// which would make the output refer to itself after that merge.
		return this.overallState.snapShot().orElseThrow();
	}

	// Normal NodeOutput builders for nodes with normal message output.

	public NodeOutput buildNodeOutput(String nodeId) throws Exception {
		return NodeOutput.of(nodeId, (String) config.metadata("_AGENT_").orElse(""),
				cloneState(this.overallState.data()), this.tokenUsage);
	}

	public OverAllState cloneState(Map<String, Object> data) throws Exception {
		return compiledGraph.cloneState(data);
	}

	// ================================================================================================================
	// Lifecycle Methods
	// ================================================================================================================

	public void doListeners(String scene, Exception e) {
		assertExecutionActive();
		for (GraphLifecycleListener listener : compiledGraph.compileConfig.lifecycleListeners()) {
			try {
				switch (scene) {
					case START:
						listener.onStart(getCurrentNodeId(), getCurrentStateData(), config);
						break;
					case END:
						listener.onComplete(END, getCurrentStateData(), config);
						break;
					case NODE_BEFORE:
						listener.before(getCurrentNodeId(), getCurrentStateData(), config, SystemClock.now());
						break;
					case NODE_AFTER:
						listener.after(getCurrentNodeId(), getCurrentStateData(), config, SystemClock.now());
						break;
					case ERROR:
						listener.onError(getCurrentNodeId(), getCurrentStateData(), e, config);
						break;
				}
			}
			catch (Exception ex) {
				log.error("Error in listener", ex);
			}
		}
	}

	/**
	 * This method updates both the current state data and the overall state.
	 * @param updateState the state updates to apply
	 */
	public void mergeIntoCurrentState(Map<String, Object> updateState) {
		assertExecutionActive();
		// Usage is output metadata, not part of checkpointed application state.
		Map<String, Object> filteredState = findTokenUsageInDeltaState(updateState);

		this.overallState.updateState(filteredState);
	}

	/**
	 * Extracts per-node usage from the reserved metadata key.
	 */
	private Map<String, Object> findTokenUsageInDeltaState(Map<String, Object> updateState) {
		Map<String, Object> filteredState = new HashMap<>();
		for (Map.Entry<String, Object> entry : updateState.entrySet()) {
			Object value = entry.getValue();
			if (entry.getKey().equals(NodeOutput.TOKEN_USAGE_KEY)) {
				if (!(value instanceof Usage usage)) {
					throw new IllegalArgumentException("Node token usage must be an ARGI Usage value");
				}
				this.tokenUsage = usage;
			}
			else {
				filteredState.put(entry.getKey(), value);
			}
		}
		return filteredState;
	}

	// ================================================================================================================
	// Getter and Setter Methods
	// ================================================================================================================

	public String getCurrentNodeId() {
		return currentNodeId;
	}

	public void setCurrentNodeId(String nodeId) {
		this.currentNodeId = nodeId;
		this.tokenUsage = Usage.unknown();
	}

	public String getNextNodeId() {
		return nextNodeId;
	}

	public void setNextNodeId(String nodeId) {
		this.nextNodeId = nodeId;
	}

	public Map<String, Object> getCurrentStateData() {
		return overallState.data();
	}

	public OverAllState getOverallState() {
		return overallState;
	}

	public void setOverallState(OverAllState state) {
		this.overallState = state;
	}

	public Map<String, KeyStrategy> getKeyStrategyMap() {
		return compiledGraph.getKeyStrategyMap();
	}

	public CompiledGraph getCompiledGraph() {
		return compiledGraph;
	}

	public RunnableConfig getConfig() {
		return config;
	}

	public void setConfig(RunnableConfig config) {
		this.config = config;
		bindExecutionGuard();
	}

	public void assertExecutionActive() {
		config.assertExecutionActive();
	}

	private void bindExecutionGuard() {
		if (checkpointScope == null) {
			this.config = this.config.withoutExecutionGuard();
			return;
		}
		this.config = RunnableConfig.builder(this.config)
			.executionGuard(checkpointScope.executionGuard().orElse(null))
			.build();
	}

	public String getResumeFrom() {
		return resumeFrom;
	}

	public void setResumeFrom(String resumeFrom) {
		this.resumeFrom = resumeFrom;
	}

	public Optional<String> getResumeFromAndReset() {
		final var result = ofNullable(resumeFrom);
		resumeFrom = null;
		return result;
	}

	public Optional<ReturnFromEmbed> getReturnFromEmbedAndReset() {
		var result = ofNullable(returnFromEmbed);
		returnFromEmbed = null;
		return result;
	}

	public void setReturnFromEmbedWithValue(Object value) {
		returnFromEmbed = new ReturnFromEmbed(value);
	}

	public record ReturnFromEmbed(Object value) {
		public <T> Optional<T> value(TypeRef<T> ref) {
			return ofNullable(value).flatMap(ref::cast);
		}
	}

	/**
	 * FIXME Below are duplicated methods. Need to have a unified way of streaming output
	 * to end user.
	 */
	public NodeOutput buildNodeOutputAndAddCheckpoint(Map<String, Object> updateStates) throws Exception {
		Optional<Checkpoint> cp = addCheckpoint(currentNodeId, nextNodeId);
		return buildOutput(currentNodeId, updateStates, cp, false);
	}

	public NodeOutput buildOutput(String nodeId, Map<String, Object> updateStates, Optional<Checkpoint> checkpoint,
			boolean streaming) throws Exception {
		if (checkpoint.isPresent() && config.streamMode() == CompiledGraph.StreamMode.SNAPSHOTS) {
			return StateSnapshot.of(getKeyStrategyMap(), checkpoint.get(), config,
					compiledGraph.stateGraph.getStateSerializer().stateFactory());
		}
		return buildNodeOutput(nodeId, updateStates, streaming);
	}

	public NodeOutput buildNodeOutput(String nodeId, Map<String, Object> updateStates, boolean streaming)
			throws Exception {
		List<ChatMessage> messages = List.of();

		// Check if updateStates is not empty
		if (updateStates != null && !updateStates.isEmpty()) {
			// Check if "messages" key exists and is a List
			Object messagesObj = updateStates.get("messages");
			// Replacement updates contain history; expose only the latest turn.
			boolean replacement = messagesObj instanceof ReplaceAllWith<?>;
			if (messagesObj instanceof ReplaceAllWith<?> replaceAll) {
				messagesObj = replaceAll.newValues();
			}
			if (messagesObj instanceof List<?> messagesList && !messagesList.isEmpty()) {
				var turn = new java.util.ArrayList<ChatMessage>();
				for (Object element : messagesList) {
					if (element instanceof ChatMessage msg) {
						turn.add(msg);
					}
				}
				if (replacement && !turn.isEmpty()) {
					int first = turn.size() - 1;
					if (turn.get(first).role() == io.github.agentic.ai.api.chat.ChatRole.TOOL) {
						while (first > 0 && turn.get(first - 1).role() == io.github.agentic.ai.api.chat.ChatRole.TOOL) {
							first--;
						}
					}
					messages = List.copyOf(turn.subList(first, turn.size()));
				}
				else {
					messages = List.copyOf(turn);
				}
			}
			else if (messagesObj instanceof ChatMessage singleMessage) {
				messages = List.of(singleMessage);
			}
		}

		OutputType outputType = OutputType.from(streaming, nodeId);

		if (!messages.isEmpty()) {
			return new StreamingOutput<>(messages, nodeId, (String) config.metadata("_AGENT_").orElse(""),
					cloneState(this.overallState.data()), tokenUsage, outputType);
		}
		else {
			return new StreamingOutput<>(nodeId, (String) config.metadata("_AGENT_").orElse(""),
					cloneState(this.overallState.data()), tokenUsage, outputType);
		}
	}

}
