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
package io.github.agentic.ai.agent.orchestration.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.workflow.graph.KeyStrategy;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.workflow.graph.action.NodeActionWithConfig;
import io.github.agentic.ai.agent.orchestration.interceptor.InterceptorChain;
import io.github.agentic.ai.agent.orchestration.interceptor.ToolCallExecutionContext;
import io.github.agentic.ai.agent.orchestration.interceptor.ToolCallHandler;
import io.github.agentic.ai.agent.orchestration.interceptor.ToolCallRequest;
import io.github.agentic.ai.agent.outcome.BusinessOutcomeMapperRegistry;
import io.github.agentic.ai.agent.orchestration.interceptor.ToolOutcome;
import io.github.agentic.ai.agent.orchestration.interceptor.ToolCallResponse;
import io.github.agentic.ai.agent.orchestration.interceptor.ToolInterceptor;
import io.github.agentic.ai.agent.orchestration.tool.DefaultCancellationToken;
import io.github.agentic.ai.agent.orchestration.tool.ToolCancelledException;
import io.github.agentic.ai.agent.orchestration.tool.ToolStateCollector;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchSpec;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchValidator;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchViolationException;
import io.github.agentic.ai.agent.tool.patch.strict.StateIsolation;
import io.github.agentic.ai.agent.tool.patch.strict.StrictMergeRules;
import io.github.agentic.ai.agent.tool.patch.strict.StrictPatchAdmission;
import io.github.agentic.ai.agent.tool.patch.strict.ToolUpdateUnit;
import io.github.agentic.ai.agent.validation.ToolArgumentValidator;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;
import io.github.agentic.ai.workflow.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.workflow.graph.internal.node.ParallelNode;
import io.github.agentic.ai.workflow.graph.state.RemoveByHash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.github.agentic.ai.workflow.graph.RunnableConfig.AGENT_TOOL_NAME;
import static io.github.agentic.ai.agent.orchestration.DefaultBuilder.POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING;
import static io.github.agentic.ai.agent.orchestration.hook.returndirect.ReturnDirectConstants.FINISH_REASON_METADATA_KEY;
import static io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;
import static io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants.AGENT_STATE_CONTEXT_KEY;
import static io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY;
import static io.github.agentic.ai.workflow.graph.checkpoint.BaseCheckpointSaver.THREAD_ID_DEFAULT;
import static io.github.agentic.ai.agent.orchestration.hook.returndirect.ReturnDirectConstants.FINISH_REASON;

/**
 * Node that executes tool calls from assistant messages.
 *
 * <p>
 * Supports both sequential and parallel tool execution. When parallel execution is
 * enabled, multiple tools can run concurrently up to the configured limit.
 * </p>
 *
 * <p>
 * Async tools implementing {@code Tool} are automatically detected and executed
 * asynchronously with proper timeout handling.
 * </p>
 *
 * @author disaster
 * @since 1.0.0
 * @see io.github.agentic.ai.api.tool.Tool
 * @see ToolStateCollector
 */
public class AgentToolNode implements NodeActionWithConfig {

	private static final Logger logger = LoggerFactory.getLogger(AgentToolNode.class);

	private final String agentName;

	private final boolean enableActingLog;

	private final boolean parallelToolExecution;

	private final int maxParallelTools;

	private final Duration toolExecutionTimeout;

	private final boolean validateToolArguments;

	private final boolean throwOnToolError;

	private Map<String, Object> toolContext;

	private List<ToolInterceptor> toolInterceptors = new ArrayList<>();

	private final AgentToolBindingStore toolBindings;

	private final ToolRegistry toolRegistry;

	private final BusinessOutcomeMapperRegistry businessOutcomeMappers;

	private final ToolContextPatchPolicy toolContextPatchPolicy;

	private final boolean strictToolState;

	private final ThreadPoolExecutor neutralInvocationExecutor;

	public AgentToolNode(Builder builder) {
		this.agentName = builder.agentName;
		this.toolRegistry = builder.toolRegistry;
		this.enableActingLog = builder.enableActingLog;
		this.toolContext = builder.toolContext;
		this.toolBindings = builder.toolBindingStore;
		this.parallelToolExecution = builder.parallelToolExecution;
		this.maxParallelTools = builder.maxParallelTools;
		// A separate bounded pool prevents nested waits from starving the graph executor.
		// Daemon workers retire after 30 idle seconds. Saturation rejects immediately;
		// the rejection follows the configured tool-error policy rather than blocking.
		this.neutralInvocationExecutor = new ThreadPoolExecutor(maxParallelTools, maxParallelTools, 30,
				TimeUnit.SECONDS, new ArrayBlockingQueue<>(maxParallelTools), task -> {
					Thread thread = new Thread(task, "argi-tool-invocation");
					thread.setDaemon(true);
					return thread;
				});
		this.neutralInvocationExecutor.allowCoreThreadTimeOut(true);
		this.toolExecutionTimeout = builder.toolExecutionTimeout;
		this.validateToolArguments = builder.validateToolArguments;
		this.throwOnToolError = builder.throwOnToolError;
		this.businessOutcomeMappers = builder.businessOutcomeMappers;
		this.toolContextPatchPolicy = builder.toolContextPatchPolicy;
		this.strictToolState = builder.strictToolState;
	}

	public void setToolInterceptors(List<ToolInterceptor> toolInterceptors) {
		this.toolInterceptors = toolInterceptors;
	}

	/** Exposes immutable neutral bindings, including the built-in tool wrappers. */
	public List<io.github.agentic.ai.api.tool.Tool> getTools() {
		var tools = new ArrayList<io.github.agentic.ai.api.tool.Tool>();
		toolRegistry.definitions().forEach(definition -> tools.add(toolRegistry.find(definition.name()).orElseThrow()));
		return List.copyOf(tools);
	}

	/** Whether this node has executable tool bindings. */
	public boolean hasTools() {
		return !toolRegistry.definitions().isEmpty();
	}

	@Override
	public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
		config.assertExecutionActive();
		List<ChatMessage> messages = (List<ChatMessage>) state.value("messages").orElseThrow();
		ChatMessage lastMessage = messages.get(messages.size() - 1);
		// A resumed run must use the exact bindings that advertised this assistant turn.
		for (int index = messages.size() - 1; index >= 0; index--) {
			ChatMessage assistant = messages.get(index);
			if (assistant.role() == ChatRole.ASSISTANT) {
				toolBindings.restore(assistant, config, new AgentToolBindings(toolRegistry));
				break;
			}
		}

		if (lastMessage.role() == ChatRole.ASSISTANT) {
			ChatMessage assistantMessage = lastMessage;
			List<io.github.agentic.ai.api.chat.ToolCall> toolCalls = assistantMessage.toolCalls();

			if (enableActingLog) {
				logger.info("[ThreadId {}] Agent {} acting with {} tools.", config.threadId().orElse(THREAD_ID_DEFAULT),
						agentName, toolCalls.size());
			}

			// Choose execution mode based on configuration
			if (parallelToolExecution && toolCalls.size() > 1) {
				return executeToolCallsParallel(toolCalls, state, config);
			}
			else {
				return executeToolCallsSequential(toolCalls, state, config);
			}
		}
		else if (lastMessage.role() == ChatRole.TOOL) {
			return handlePartialToolResponses(messages, state, config);
		}
		else {
			throw new IllegalStateException("Last message must have the ASSISTANT or TOOL role");
		}
	}

	/**
	 * Sequential execution of tool calls (original behavior).
	 *
	 * <p>
	 * Each tool gets its own isolated state update map. This prevents a subsequent tool's
	 * timeout from clearing state updates from previously successful tools. The isolation
	 * is achieved by:
	 * <ol>
	 * <li>Creating a new {@code ConcurrentHashMap} for each tool execution</li>
	 * <li>Immediately merging successful updates into {@code mergedUpdates}</li>
	 * <li>If a tool times out, only its isolated map is cleared (line 549), not the
	 * already-merged data</li>
	 * </ol>
	 * </p>
	 *
	 * <p>
	 * This behavior is now consistent with parallel execution mode, which uses
	 * {@link ToolStateCollector} for per-tool state isolation.
	 * </p>
	 *
	 * <h3>State Merge Semantics</h3>
	 * <p>
	 * Sequential mode uses <b>last-write-wins</b> semantics for state updates. When
	 * multiple tools write to the same key, the last tool's value overwrites previous
	 * values. This preserves the original behavior from before parallel execution was
	 * introduced.
	 * </p>
	 * <p>
	 * Note: This differs from parallel execution mode, which respects
	 * {@link io.github.agentic.ai.workflow.graph.KeyStrategy} for merge operations - unless
	 * {@code strictToolState(true)} is enabled, in which case sequential merges go through
	 * the same KeyStrategy map and the divergence disappears. Without strict mode, use
	 * parallel execution for deterministic merge behavior (e.g., APPEND for lists).
	 * </p>
	 *
	 * @see #executeToolCallsParallel(List, OverAllState, RunnableConfig)
	 */
	private Map<String, Object> executeToolCallsSequential(List<io.github.agentic.ai.api.chat.ToolCall> toolCalls,
			OverAllState state, RunnableConfig config) {

		config.assertExecutionActive();
		Map<String, Object> updatedState = new HashMap<>();
		Map<String, Object> mergedUpdates = new HashMap<>(); // Accumulated results from
																// successful tools
		List<ChatMessage> toolResponses = new ArrayList<>();

		OverAllState toolState = toolVisibleState(state);
		Boolean returnDirect = null;
		for (io.github.agentic.ai.api.chat.ToolCall toolCall : toolCalls) {
			config.assertExecutionActive();
			// Each tool gets its own isolated update map
			// If this tool times out, clear() only affects this map, not mergedUpdates
			Map<String, Object> toolSpecificUpdate = new ConcurrentHashMap<>();
			ToolCallResponse response = executeToolCallWithInterceptors(toolCall, toolState, config, toolSpecificUpdate,
					false);
			config.assertExecutionActive();
			toolResponses.add(response.toMessage());
			returnDirect = shouldReturnDirect(toolCall, returnDirect, config);
			config.assertExecutionActive();
			// Merge immediately - subsequent timeout clear() won't affect already-merged
			// data. A configured patch policy gates the merge boundary: patches of failed
			// calls and invalid patches never reach the next turn's state.
			if (admitsPatch(toolCall.name(), response, toolSpecificUpdate)) {
				if (strictToolState) {
					mergeThroughStrategies(mergedUpdates, toolSpecificUpdate, state);
				}
				else {
					mergedUpdates.putAll(toolSpecificUpdate);
				}
			}
		}
		config.assertExecutionActive();

		List<ChatMessage> toolResponseMessage = AgentChatProtocol.toolResponse(toolResponses,
				Boolean.TRUE.equals(returnDirect) ? Map.of(FINISH_REASON_METADATA_KEY, FINISH_REASON) : Map.of());

		if (enableActingLog) {
			logger.info("[ThreadId {}] Agent {} acting returned: {}", config.threadId().orElse(THREAD_ID_DEFAULT),
					agentName, toolResponseMessage);
		}

		updatedState.put("messages", toolResponseMessage);
		updatedState.putAll(mergedUpdates);
		return updatedState;
	}

	/**
	 * Parallel execution of tool calls with concurrency limiting.
	 *
	 * <p>
	 * <b>Important:</b> The state snapshot created via {@code state.snapShot()} is a
	 * shallow copy. Mutable objects within the state (like Lists) are shared. Tools
	 * should NOT directly modify collections retrieved from the state. Use the
	 * {@code extraStateFromToolCall} map to write updates. With
	 * {@code strictToolState(true)} this advisory becomes structural: tools receive a
	 * read-only view whose collections reject mutation.
	 * </p>
	 *
	 * <h3>State Merge Semantics</h3>
	 * <p>
	 * Parallel mode uses {@link ToolStateCollector} which respects the
	 * {@link io.github.agentic.ai.workflow.graph.KeyStrategy} configured for each state key. This
	 * provides deterministic merge behavior even when multiple tools write to the same
	 * key concurrently:
	 * <ul>
	 * <li>Keys with {@code APPEND} strategy: values from all tools are appended</li>
	 * <li>Keys with {@code REPLACE} strategy: last value wins (non-deterministic
	 * order)</li>
	 * </ul>
	 * </p>
	 * <p>
	 * Note: This differs from sequential execution mode, which uses simple
	 * last-write-wins semantics via {@code Map.putAll()}. The difference is intentional -
	 * parallel mode was designed with KeyStrategy support from the start, while
	 * sequential mode preserves the original behavior for backward compatibility.
	 * </p>
	 * @param toolCalls the tool calls to execute
	 * @param state the current state (will be snapshot for thread safety)
	 * @param config the runtime configuration
	 * @return the merged state updates from all tool executions
	 * @see ToolStateCollector
	 * @see #executeToolCallsSequential(List, OverAllState, RunnableConfig)
	 */
	private Map<String, Object> executeToolCallsParallel(List<io.github.agentic.ai.api.chat.ToolCall> toolCalls,
			OverAllState state, RunnableConfig config) {

		config.assertExecutionActive();

		Executor executor = getToolExecutor(config);

		// Create state snapshot for thread safety
		// Note: snapShot() is a shallow copy, shared mutable objects (like messages List)
		// should not be directly modified by tools
		OverAllState stateSnapshot = toolVisibleState(state.snapShot().orElse(state));

		ToolStateCollector stateCollector = new ToolStateCollector(toolCalls.size(), state.keyStrategies());

		// Use AtomicReferenceArray + CAS to prevent race condition between
		// successful completion and timeout/error handling
		AtomicReferenceArray<ToolCallResponse> orderedResponses = new AtomicReferenceArray<>(toolCalls.size());
		List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

		// Pre-create cancellation tokens for cancellable tools so they can be accessed
		// from the exceptionally handler when outer timeout triggers
		Map<Integer, DefaultCancellationToken> cancellationTokens = new ConcurrentHashMap<>();

		// Use Semaphore to limit concurrency
		Semaphore semaphore = new Semaphore(maxParallelTools);
		long timeoutNanos = toolExecutionTimeout.toNanos();
		long batchStartedAt = System.nanoTime();

		List<CompletableFuture<Void>> futures = IntStream.range(0, toolCalls.size()).mapToObj(index -> {
			io.github.agentic.ai.api.chat.ToolCall toolCall = toolCalls.get(index);
			Map<String, Object> toolSpecificUpdate = stateCollector.createToolUpdateMap(index);
			long submittedAt = batchStartedAt;

			return CompletableFuture.runAsync(() -> {
				try {
					config.assertExecutionActive();
					// Executor queueing and permit acquisition share the tool's timeout
					// budget.
					long remainingNanos = timeoutNanos - (System.nanoTime() - submittedAt);
					if (remainingNanos <= 0 || !semaphore.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
						throw new CompletionException(new TimeoutException());
					}
					try {
						config.assertExecutionActive();
						if (orderedResponses.get(index) != null || System.nanoTime() - submittedAt >= timeoutNanos) {
							throw new CompletionException(new TimeoutException());
						}
						config.assertExecutionActive();
						ToolCallResponse response = executeToolCallWithInterceptors(toolCall, stateSnapshot, config,
								toolSpecificUpdate, true, cancellationTokens, index, submittedAt + timeoutNanos);
						config.assertExecutionActive();
						// CAS: only set if still null (not already timed out)
						orderedResponses.compareAndSet(index, null, response);
					}
					finally {
						semaphore.release();
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					failures.add(e);
					// CAS: only set error if still null
					orderedResponses.compareAndSet(index, null, ToolCallResponse.error(toolCall.id(), toolCall.name(),
							"Tool execution was interrupted", failureMetadata(e)));
				}
			}, executor)
				.orTimeout(Math.max(0, timeoutNanos - (System.nanoTime() - submittedAt)), TimeUnit.NANOSECONDS)
				.exceptionally(ex -> {
					// CAS: only set error if still null (tool hasn't completed
					// successfully)
					Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
					LeaseLostException leaseLoss = leaseLost(cause);
					if (leaseLoss != null) {
						failures.add(leaseLoss);
						throw new CompletionException(leaseLoss);
					}
					ToolCallResponse errorResponse = ToolCallResponse.error(toolCall.id(), toolCall.name(),
							extractErrorMessage(cause), failureMetadata(cause));
					if (orderedResponses.compareAndSet(index, null, errorResponse)) {
						if (cause instanceof TimeoutException) {
							stateCollector.discardToolUpdateMap(index);
							// Cancel the tool's cancellation token to notify it to stop
							// gracefully
							DefaultCancellationToken token = cancellationTokens.get(index);
							if (token != null) {
								token.cancel();
								logger.debug("Cancelled tool {} at index {} due to outer timeout", toolCall.name(),
										index);
							}
						}
						failures.add(ex);
					}
					return null;
				});
		}).toList();

		// Wait for all tools to complete
		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		}
		catch (CompletionException ex) {
			LeaseLostException leaseLoss = leaseLost(ex);
			if (leaseLoss != null) {
				throw leaseLoss;
			}
			throw ex;
		}

		// Build result - collect responses from AtomicReferenceArray
		config.assertExecutionActive();
		if (throwOnToolError && !failures.isEmpty()) {
			Throwable failure = failures.iterator().next();
			while (failure instanceof CompletionException && failure.getCause() != null) {
				failure = failure.getCause();
			}
			propagateToolFailure(failure);
		}
		Map<String, Object> updatedState = new HashMap<>();
		List<ChatMessage> toolResponses = new ArrayList<>();
		Boolean returnDirect = null;
		for (int i = 0; i < orderedResponses.length(); i++) {
			ToolCallResponse response = orderedResponses.get(i);
			if (response == null) {
				// Fallback: create error response for missing result (should not happen
				// normally)
				io.github.agentic.ai.api.chat.ToolCall toolCall = toolCalls.get(i);
				response = ToolCallResponse.error(toolCall.id(), toolCall.name(),
						"Tool execution did not produce a response",
						Map.of(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_EXECUTION));
				logger.warn("Tool {} at index {} has null response, using error fallback", toolCall.name(), i);
			}
			toolResponses.add(response.toMessage());
			returnDirect = shouldReturnDirect(toolCalls.get(i), returnDirect, config);
		}
		config.assertExecutionActive();

		updatedState.put("messages", AgentChatProtocol.toolResponse(toolResponses,
				Boolean.TRUE.equals(returnDirect) ? Map.of(FINISH_REASON_METADATA_KEY, FINISH_REASON) : Map.of()));
		// Same merge-boundary gate as sequential mode, applied per tool index: rejected
		// patches are discarded before mergeAll() so they cannot pollute the state.
		discardRejectedPatches(toolCalls, orderedResponses, stateCollector);
		if (strictToolState) {
			enforceDeclaredMergeStrategies(toolCalls, stateCollector, state);
		}
		updatedState.putAll(stateCollector.mergeAll());

		if (enableActingLog) {
			logger.info("[ThreadId {}] Agent {} parallel tool execution completed. {} tools, {} failures.",
					config.threadId().orElse(THREAD_ID_DEFAULT), agentName, toolCalls.size(), failures.size());
		}

		return updatedState;
	}

	/**
	 * Handle partial tool responses (TOOL message branch). Supports both parallel and
	 * sequential execution of remaining tools.
	 */
	private Map<String, Object> handlePartialToolResponses(List<ChatMessage> messages, OverAllState state,
			RunnableConfig config) {
		config.assertExecutionActive();
		int assistantIndex = messages.size() - 1;
		while (assistantIndex >= 0 && messages.get(assistantIndex).role() == ChatRole.TOOL) {
			assistantIndex--;
		}
		if (assistantIndex < 0 || messages.get(assistantIndex).role() != ChatRole.ASSISTANT) {
			throw new IllegalStateException("Tool results must follow their assistant turn");
		}
		ChatMessage assistant = messages.get(assistantIndex);
		List<ChatMessage> existing = messages.subList(assistantIndex + 1, messages.size());
		Set<String> completed = existing.stream().map(ChatMessage::toolCallId).collect(Collectors.toSet());
		var remaining = assistant.toolCalls().stream().filter(call -> !completed.contains(call.id())).toList();
		if (remaining.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> result = parallelToolExecution && remaining.size() > 1
				? executeToolCallsParallel(remaining, state, config)
				: executeToolCallsSequential(remaining, state, config);
		config.assertExecutionActive();
		List<ChatMessage> all = new ArrayList<>(existing);
		all.addAll((List<ChatMessage>) result.get("messages"));
		Boolean direct = null;
		for (var call : assistant.toolCalls()) {
			direct = shouldReturnDirect(call, direct, config);
		}
		List<Object> updates = new ArrayList<>();
		for (ChatMessage message : all) {
			Map<String, Object> metadata = new HashMap<>(message.metadata());
			metadata.remove(FINISH_REASON_METADATA_KEY);
			if (Boolean.TRUE.equals(direct)) {
				metadata.put(FINISH_REASON_METADATA_KEY, FINISH_REASON);
			}
			updates.add(message.withMetadata(metadata));
		}
		existing.forEach(message -> updates.add(new RemoveByHash<>(message)));
		result.put("messages", updates);
		return result;
	}

	private Boolean shouldReturnDirect(io.github.agentic.ai.api.chat.ToolCall toolCall, Boolean returnDirect,
			RunnableConfig config) {
		var neutralTool = activeToolRegistry(config).find(toolCall.name());
		if (neutralTool.isPresent()) {
			return (returnDirect == null || returnDirect) && neutralTool.get().returnDirect();
		}
		return false;
	}

	/**
	 * Execute a tool call with interceptor chain support. Supports both sync and async
	 * tool callbacks.
	 * @param toolCall the tool call to execute
	 * @param state the current state
	 * @param config the runtime configuration
	 * @param extraStateFromToolCall map to collect state updates from tool execution
	 * @param inParallelExecution true if called from parallel execution context, false
	 * for sequential
	 * @return the tool call response
	 */
	private ToolCallResponse executeToolCallWithInterceptors(io.github.agentic.ai.api.chat.ToolCall toolCall,
			OverAllState state, RunnableConfig config, Map<String, Object> extraStateFromToolCall,
			boolean inParallelExecution) {
		return executeToolCallWithInterceptors(toolCall, state, config, extraStateFromToolCall, inParallelExecution,
				null, -1, System.nanoTime() + toolExecutionTimeout.toNanos());
	}

	/**
	 * Execute a tool call with interceptor chain support, with optional cancellation
	 * token tracking for parallel execution.
	 * @param toolCall the tool call to execute
	 * @param state the current state
	 * @param config the runtime configuration
	 * @param extraStateFromToolCall map to collect state updates from tool execution
	 * @param inParallelExecution true if called from parallel execution context, false
	 * for sequential
	 * @param cancellationTokens optional map to store cancellation tokens for parallel
	 * execution (may be null)
	 * @param toolIndex the index of this tool in the parallel execution (used as key in
	 * cancellationTokens)
	 * @return the tool call response
	 */
	private ToolCallResponse executeToolCallWithInterceptors(io.github.agentic.ai.api.chat.ToolCall toolCall,
			OverAllState state, RunnableConfig config, Map<String, Object> extraStateFromToolCall,
			boolean inParallelExecution, Map<Integer, DefaultCancellationToken> cancellationTokens, int toolIndex,
			long deadlineNanos) {

		config.assertExecutionActive();
		// Create ToolCallRequest
		ToolCallRequest request = ToolCallRequest.builder()
			.toolCall(new io.github.agentic.ai.api.chat.ToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()))
			.context(config.metadata().orElse(new HashMap<>()))
			.executionContext(new ToolCallExecutionContext(config, state))
			.build();

		// Create base handler that actually executes the tool
		ToolCallHandler baseHandler = req -> {
			config.assertExecutionActive();
			ToolRegistry activeRegistry = activeToolRegistry(config);
			if (activeRegistry.find(req.getToolName()).isPresent()) {
				return executeNeutralTool(activeRegistry, req, state, config, extraStateFromToolCall,
						cancellationTokens, toolIndex, deadlineNanos);
			}
			logger.warn(POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING, req.getToolName());
			return ToolCallResponse.builder()
				.content("Tool not available: " + req.getToolName())
				.toolName(req.getToolName())
				.toolCallId(req.getToolCallId())
				.status("error")
				.metadata(Map.of("error", true, "unresolvedToolName", req.getToolName(),
						ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_UNRESOLVED))
				.build();
		};

		// Chain interceptors if any
		ToolCallHandler chainedHandler = InterceptorChain.chainToolInterceptors(toolInterceptors, baseHandler);

		// Execute the chained handler
		ToolCallResponse response = chainedHandler.call(request);
		config.assertExecutionActive();
		return response;
	}

	/**
	 * Executes tool stages under a single deadline and the graph's state and lease
	 * boundaries.
	 */
	private ToolCallResponse executeNeutralTool(ToolRegistry registry, ToolCallRequest request, OverAllState state,
			RunnableConfig config, Map<String, Object> updates,
			Map<Integer, DefaultCancellationToken> cancellationTokens, int toolIndex, long deadlineNanos) {
		var definition = registry.definitions()
			.stream()
			.filter(tool -> tool.name().equals(request.getToolName()))
			.findFirst()
			.orElseThrow();
		if (validateToolArguments) {
			var failure = ToolArgumentValidator.validate(definition.inputSchema(), request.getArguments());
			if (failure.isPresent()) {
				var invalid = failure.get();
				return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
						"Invalid tool arguments: " + invalid.errorCode() + " at " + invalid.fieldPath(),
						Map.of("errorCode", invalid.errorCode(), "fieldPath", invalid.fieldPath()));
			}
		}
		Map<String, Object> attributes = new HashMap<>(toolContext);
		attributes.putAll(request.getContext());
		// Framework context keys override metadata and interceptor rewrites. Trusted
		// identity is read only from the actual invocation's RunnableConfig.
		attributes.put(AGENT_STATE_CONTEXT_KEY, state);
		attributes.put(AGENT_CONFIG_CONTEXT_KEY, config);
		Map<String, Object> invocationUpdates = new ConcurrentHashMap<>();
		attributes.put(AGENT_STATE_FOR_UPDATE_CONTEXT_KEY, invocationUpdates);
		ToolCall call = new ToolCall(request.getToolCallId(), request.getToolName(), request.getArguments());
		DefaultCancellationToken token = new DefaultCancellationToken();
		AtomicReference<CompletableFuture<ToolResult>> source = new AtomicReference<>();
		CompletableFuture<ToolResult> awaited = new CompletableFuture<>();
		FutureTask<Void> invocation = new FutureTask<>(() -> {
			try {
				config.assertExecutionActive();
				if (token.isCancelled()) {
					throw new CancellationException("Tool execution was cancelled before invocation");
				}
				if (deadlineNanos - System.nanoTime() <= 0) {
					throw new TimeoutException("Tool deadline expired before invocation");
				}
				CompletableFuture<ToolResult> future = registry
					.execute(call, new io.github.agentic.ai.api.tool.ToolContext(attributes))
					.toCompletableFuture();
				source.set(future);
				if (token.isCancelled()) {
					future.cancel(true);
				}
				future.whenComplete((result, error) -> {
					if (error == null) {
						awaited.complete(result);
					}
					else {
						awaited.completeExceptionally(error);
					}
				});
			}
			catch (Throwable failure) {
				awaited.completeExceptionally(failure);
			}
			return null;
		});
		token.onCancel(() -> {
			awaited.cancel(true);
			invocation.cancel(true);
			neutralInvocationExecutor.remove(invocation);
			CompletableFuture<ToolResult> future = source.get();
			if (future != null) {
				future.cancel(true);
			}
		});
		if (cancellationTokens != null && toolIndex >= 0) {
			cancellationTokens.put(toolIndex, token);
		}
		AutoCloseable lossRegistration = () -> {
		};
		try {
			if (config.executionGuard().isPresent()) {
				ExecutionGuard guard = config.executionGuard().orElseThrow();
				lossRegistration = guard.onLoss(() -> {
					try {
						guard.assertActive();
						awaited.completeExceptionally(new CancellationException("Execution lease was lost"));
					}
					catch (LeaseLostException failure) {
						awaited.completeExceptionally(failure);
					}
					token.cancel();
				});
			}
			config.assertExecutionActive();
			if (token.isCancelled()) {
				throw new CancellationException("Tool execution was cancelled before invocation");
			}
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0) {
				throw new TimeoutException("Tool deadline expired before invocation");
			}
			neutralInvocationExecutor.execute(invocation);
			ToolResult result = awaited.get(remainingNanos, TimeUnit.NANOSECONDS);
			config.assertExecutionActive();
			if (token.isCancelled()) {
				throw new CancellationException("Tool execution was cancelled before state publication");
			}
			// A registered outcome mapper is the only component allowed to reinterpret a
			// successful raw result as a business failure; unregistered tools are never guessed.
			ToolResult outcome = businessOutcomeMappers.mapOutcome(call, result);
			if (outcome.error() && !result.error() && businessOutcomeMappers.hasMapper(call.name())) {
				return ToolCallResponse.from(call, outcome, ToolOutcome.BUSINESS_FAILED);
			}
			updates.putAll(invocationUpdates);
			return ToolCallResponse.from(call, outcome);
		}
		catch (InterruptedException failure) {
			updates.clear();
			token.cancel();
			Thread.currentThread().interrupt();
			config.assertExecutionActive();
			return ToolCallResponse.error(call.id(), call.name(), "Tool execution was interrupted",
					failureMetadata(failure));
		}
		catch (Exception failure) {
			Throwable cause = failure;
			while ((cause instanceof ExecutionException || cause instanceof CompletionException)
					&& cause.getCause() != null) {
				cause = cause.getCause();
			}
			LeaseLostException leaseLoss = leaseLost(cause);
			if (leaseLoss != null) {
				updates.clear();
				token.cancel();
				throw leaseLoss;
			}
			if (cause instanceof TimeoutException || cause instanceof CancellationException) {
				updates.clear();
				token.cancel();
			}
			config.assertExecutionActive();
			if (throwOnToolError) {
				if (cause instanceof RuntimeException runtime) {
					throw runtime;
				}
				throw new IllegalStateException("Tool execution failed: " + call.name(), cause);
			}
			return ToolCallResponse.error(call.id(), call.name(), extractErrorMessage(cause), failureMetadata(cause));
		}
		finally {
			try {
				lossRegistration.close();
			}
			catch (Exception failure) {
				logger.debug("Failed to close execution guard loss registration", failure);
			}
			if (cancellationTokens != null && toolIndex >= 0) {
				cancellationTokens.remove(toolIndex, token);
			}
		}
	}

	private void propagateToolFailure(Throwable failure) {
		if (throwOnToolError) {
			if (failure instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("Tool execution failed", failure);
		}
	}

	/**
	 * Applies the configured patch policy to every collected response before the parallel
	 * merge, discarding the update maps of rejected calls. No-op without a policy and
	 * without strict mode; in strict mode the admission runs even without a policy.
	 */
	private void discardRejectedPatches(List<io.github.agentic.ai.api.chat.ToolCall> toolCalls,
			AtomicReferenceArray<ToolCallResponse> orderedResponses, ToolStateCollector stateCollector) {
		if (!strictToolState && toolContextPatchPolicy == null) {
			return;
		}
		for (int i = 0; i < orderedResponses.length(); i++) {
			ToolCallResponse response = orderedResponses.get(i);
			if (response == null) {
				continue;
			}
			if (!admitsPatch(toolCalls.get(i).name(), response, stateCollector.toolUpdateMap(i))) {
				stateCollector.discardToolUpdateMap(i);
			}
		}
	}

	/**
	 * The state a tool may read in strict mode: an {@link OverAllState} whose data map and
	 * every reachable {@code List}/{@code Map} — nested to any depth — reject mutation,
	 * so state writes can escape only through the patch channel. Without strict mode the
	 * state passes through untouched.
	 * @param state the state the node was invoked with
	 * @return the state a tool may read
	 */
	private OverAllState toolVisibleState(OverAllState state) {
		if (!strictToolState) {
			return state;
		}
		var view = new OverAllState(StateIsolation.readOnlyView(state.data()), state.getStore());
		view.registerKeyAndStrategy(state.keyStrategies());
		return view;
	}

	/**
	 * Merges one admitted patch into the accumulated sequential updates through the
	 * declared {@link KeyStrategy} map - the same rules the parallel collector applies -
	 * closing the historical sequential/parallel divergence for strict mode.
	 * @param merged the accumulated updates of earlier tools
	 * @param patch the admitted patch of the tool that just completed
	 * @param state the node's state, source of the declared strategies
	 */
	private void mergeThroughStrategies(Map<String, Object> merged, Map<String, Object> patch,
			OverAllState state) {
		Map<String, KeyStrategy> strategies = state.keyStrategies();
		for (Map.Entry<String, Object> entry : patch.entrySet()) {
			ToolStateCollector.mergeEntry(merged, strategies, entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Strict parallel merge rule: a key written by several tools without a declared
	 * {@link KeyStrategy} is a policy violation, not a silent replace-last-in-tool-order.
	 * ABORT runs throw the same violation exception as patch validation; DISCARD drops
	 * every admitted patch touching the conflicting key, whole patches at a time.
	 * @param toolCalls the calls of the parallel batch, in tool order
	 * @param stateCollector the batch's admitted update maps
	 * @param state the node's state, source of the declared strategies
	 */
	private void enforceDeclaredMergeStrategies(List<io.github.agentic.ai.api.chat.ToolCall> toolCalls,
			ToolStateCollector stateCollector, OverAllState state) {
		// Re-check after every discard round: dropping a patch can resolve further
		// conflicts (or expose the next one), and each round discards at least one
		// non-empty patch, so the loop terminates.
		Map<String, KeyStrategy> strategies = state.keyStrategies();
		while (true) {
			var conflict = StrictMergeRules.checkUndeclaredConflicts(admittedPatches(toolCalls, stateCollector),
					strategies.keySet());
			if (conflict.isEmpty()) {
				return;
			}
			StrictMergeRules.ConflictFailure failure = conflict.get();
			if (toolContextPatchPolicy != null
					&& toolContextPatchPolicy.violationPolicy() == ToolContextPatchPolicy.ViolationPolicy.ABORT_RUN) {
				throw new ToolContextPatchViolationException(new ToolContextPatchValidator.PatchValidationFailure(
						failure.errorCode(), failure.key(), failure.message()));
			}
			logger.warn("Discarding context patches writing state key '{}': written by several tools without a "
					+ "declared merge strategy", failure.key());
			for (int i = 0; i < toolCalls.size(); i++) {
				Map<String, Object> patch = stateCollector.toolUpdateMap(i);
				if (patch != null && patch.containsKey(failure.key())) {
					stateCollector.discardToolUpdateMap(i);
				}
			}
		}
	}

	/**
	 * The update maps still admitted to the parallel merge, in tool order.
	 * @param toolCalls the calls of the parallel batch, in tool order
	 * @param stateCollector the batch's update maps
	 * @return the non-empty admitted patches, never {@code null}
	 */
	private List<Map<String, Object>> admittedPatches(List<io.github.agentic.ai.api.chat.ToolCall> toolCalls,
			ToolStateCollector stateCollector) {
		List<Map<String, Object>> patches = new ArrayList<>();
		for (int i = 0; i < toolCalls.size(); i++) {
			Map<String, Object> patch = stateCollector.toolUpdateMap(i);
			if (patch != null && !patch.isEmpty()) {
				patches.add(patch);
			}
		}
		return patches;
	}

	/**
	 * Merge-boundary admission for one tool's context patch. Without a policy every patch
	 * is admitted (legacy behavior). With a policy, patches of failed calls are discarded
	 * first (their stray writes are dropped, never escalated), then the patch is validated
	 * against the reserved namespace and the tool's declared spec; a violation either
	 * discards the whole patch or aborts the run, as configured.
	 * @param toolName the tool whose patch is being admitted
	 * @param response the tool call's outcome, whose error state gates the patch
	 * @param patch the update map the tool produced, or {@code null} when none exists
	 * @return {@code true} when the patch may be merged into the next turn's state
	 */
	private boolean admitsPatch(String toolName, ToolCallResponse response, Map<String, Object> patch) {
		if (strictToolState) {
			boolean admitted = StrictPatchAdmission.admit(toolContextPatchPolicy,
					new ToolUpdateUnit(toolName, !response.isError(), patch));
			if (!admitted) {
				logger.warn(
						"Discarding context patch of tool {}: strict admission rejected the tool's update unit",
						toolName);
			}
			return admitted;
		}
		if (toolContextPatchPolicy == null || patch == null || patch.isEmpty()) {
			return true;
		}
		if (toolContextPatchPolicy.discardPatchOnFailedResult() && response.isError()) {
			logger.warn("Discarding context patch of failed tool call {}: the tool reported an error", toolName);
			return false;
		}
		ToolContextPatchSpec spec = toolContextPatchPolicy.specFor(toolName);
		var failure = ToolContextPatchValidator.validate(spec, toolName, patch);
		if (failure.isPresent()) {
			if (toolContextPatchPolicy.violationPolicy() == ToolContextPatchPolicy.ViolationPolicy.ABORT_RUN) {
				throw new ToolContextPatchViolationException(failure.get());
			}
			logger.warn("Discarding context patch of tool {}: {} on key {}", toolName, failure.get().errorCode(),
					failure.get().key());
			return false;
		}
		return true;
	}

	/**
	 * Derives the structured failure metadata for an error response from the throwable
	 * that caused it: a {@link ToolCallResponse#FAILURE_KIND_METADATA_KEY failure kind}
	 * and, when available, the {@link ToolCallResponse#EXCEPTION_TYPE_METADATA_KEY class
	 * name} of the underlying exception. Completion and execution wrappers are unwrapped
	 * before classifying the failure.
	 */
	static Map<String, Object> failureMetadata(Throwable error) {
		Map<String, Object> metadata = new HashMap<>();
		Throwable source = error;
		while ((source instanceof ExecutionException || source instanceof CompletionException)
				&& source.getCause() != null) {
			source = source.getCause();
		}
		metadata.put(ToolCallResponse.FAILURE_KIND_METADATA_KEY, failureKindOf(source));
		if (source != null) {
			metadata.put(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, source.getClass().getName());
		}
		return metadata;
	}

	private static String failureKindOf(Throwable error) {
		if (error instanceof TimeoutException) {
			return ToolCallResponse.FAILURE_KIND_TIMEOUT;
		}
		if (error instanceof CancellationException || error instanceof ToolCancelledException
				|| error instanceof InterruptedException) {
			return ToolCallResponse.FAILURE_KIND_CANCELLED;
		}
		return ToolCallResponse.FAILURE_KIND_EXECUTION;
	}

	private LeaseLostException leaseLost(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof LeaseLostException leaseLost) {
				return leaseLost;
			}
			current = current.getCause();
		}
		return null;
	}

	/**
	 * Extract a user-friendly error message from an exception.
	 */
	private String extractErrorMessage(Throwable error) {
		if (error == null) {
			return "Unknown error";
		}
		if (error instanceof TimeoutException) {
			return "Tool execution timed out";
		}
		if (error instanceof CancellationException) {
			return "Tool execution was cancelled";
		}
		if (error instanceof ToolCancelledException) {
			return "Tool execution was cancelled";
		}
		if (error instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			return "Tool execution was interrupted";
		}
		return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
	}

	/**
	 * Get the executor for tool execution from config or use default.
	 */
	private Executor getToolExecutor(RunnableConfig config) {
		return config.metadata(ParallelNode.formatNodeId(AGENT_TOOL_NAME))
			.filter(value -> value instanceof Executor)
			.map(Executor.class::cast)
			.orElseGet(() -> ParallelNode.getExecutor(config, AGENT_TOOL_NAME));
	}

	private ToolRegistry activeToolRegistry(RunnableConfig config) {
		AgentToolBindings bindings = toolBindings.current(config);
		return bindings == null ? toolRegistry : bindings.registry();
	}

	public String getName() {
		return AGENT_TOOL_NAME;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private ToolRegistry toolRegistry = new ToolRegistry(List.of());

		public Builder toolRegistry(ToolRegistry toolRegistry) {
			this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
			return this;
		}

		private String agentName;

		private boolean enableActingLog;

		private boolean parallelToolExecution = false;

		private int maxParallelTools = 5;

		private Duration toolExecutionTimeout = Duration.ofMinutes(5);

		private boolean validateToolArguments = false;

		private BusinessOutcomeMapperRegistry businessOutcomeMappers = BusinessOutcomeMapperRegistry.empty();

		private Map<String, Object> toolContext = new HashMap<>();

		private AgentToolBindingStore toolBindingStore = new AgentToolBindingStore();

		private boolean throwOnToolError;

		private ToolContextPatchPolicy toolContextPatchPolicy;

		private boolean strictToolState;

		/** Controls failure propagation for provider-neutral tool stages. */
		public Builder throwOnToolError(boolean throwOnToolError) {
			this.throwOnToolError = throwOnToolError;
			return this;
		}

		/**
		 * Opt-in policy gating tool context patches at the merge boundary. Default
		 * {@code null}: every patch is merged, matching the historical behavior.
		 * @param toolContextPatchPolicy the patch policy to enforce, or {@code null} to
		 * disable patch admission
		 * @return this builder
		 */
		public Builder toolContextPatchPolicy(ToolContextPatchPolicy toolContextPatchPolicy) {
			this.toolContextPatchPolicy = toolContextPatchPolicy;
			return this;
		}

		/**
		 * Opt-in strict tool-state mode. Tools then see a read-only view of the state
		 * instead of the live map or a shallow snapshot, a failed call's patch is always
		 * discarded regardless of the patch policy, sequential merges honor the declared
		 * KeyStrategies, and parallel merges refuse keys written by several tools without
		 * a declared strategy. Default {@code false}: legacy behavior.
		 * @param strict true to enable strict tool-state semantics
		 * @return this builder
		 */
		public Builder strictToolState(boolean strict) {
			this.strictToolState = strict;
			return this;
		}

		private Builder() {
		}

		public Builder agentName(String agentName) {
			this.agentName = agentName;
			return this;
		}

		public Builder enableActingLog(boolean enableActingLog) {
			this.enableActingLog = enableActingLog;
			return this;
		}

		/**
		 * Enable parallel execution of multiple tool calls. When enabled, tools will
		 * execute concurrently up to {@link #maxParallelTools} limit.
		 * @param parallel true to enable parallel execution
		 * @return this builder
		 */
		public Builder parallelToolExecution(boolean parallel) {
			this.parallelToolExecution = parallel;
			return this;
		}

		/**
		 * Set the maximum number of tools to execute in parallel. This limits concurrent
		 * tool executions to prevent resource exhaustion.
		 * @param max the maximum parallel tools (default: 5)
		 * @return this builder
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
		 * Set the timeout for each tool execution.
		 * @param timeout the timeout duration (default: 5 minutes)
		 * @return this builder
		 */
		public Builder toolExecutionTimeout(Duration timeout) {
			this.toolExecutionTimeout = timeout;
			return this;
		}

		/**
		 * Enable opt-in validation of tool-call arguments against the tool's declared
		 * input schema before dispatch. Default: false (legacy pass-through).
		 * @param validate true to validate tool arguments before dispatch
		 * @return this builder
		 */
		public Builder businessOutcomeMappers(BusinessOutcomeMapperRegistry registry) {
			this.businessOutcomeMappers = Objects.requireNonNull(registry,
					"businessOutcomeMappers must not be null");
			return this;
		}

		public Builder validateToolArguments(boolean validate) {
			this.validateToolArguments = validate;
			return this;
		}

		public Builder toolBindingStore(AgentToolBindingStore toolBindingStore) {
			this.toolBindingStore = Objects.requireNonNull(toolBindingStore, "toolBindingStore must not be null");
			return this;
		}

		public Builder toolContext(Map<String, Object> toolContext) {
			this.toolContext = new HashMap<>(toolContext);
			return this;
		}

		public AgentToolNode build() {
			Objects.requireNonNull(toolExecutionTimeout, "toolExecutionTimeout must not be null");
			if (toolExecutionTimeout.isZero() || toolExecutionTimeout.isNegative()) {
				throw new IllegalArgumentException("toolExecutionTimeout must be positive");
			}
			return new AgentToolNode(this);
		}

	}

}
