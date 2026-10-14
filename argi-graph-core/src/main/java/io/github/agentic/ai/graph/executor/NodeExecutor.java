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

package io.github.agentic.ai.graph.executor;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.GraphRunnerContext;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.action.Command;
import io.github.agentic.ai.graph.action.InterruptableAction;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.exception.RunnableErrors;
import io.github.agentic.ai.graph.internal.node.NodeUsage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.graph.streaming.GraphFlux;
import io.github.agentic.ai.graph.streaming.ParallelGraphFlux;
import io.github.agentic.ai.graph.streaming.StreamingOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.agentic.ai.api.chat.ToolCall;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.github.agentic.ai.graph.GraphRunnerContext.INTERRUPT_AFTER;
import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.ERROR;
import static io.github.agentic.ai.graph.StateGraph.NODE_AFTER;
import static io.github.agentic.ai.graph.StateGraph.NODE_BEFORE;
import static io.github.agentic.ai.graph.internal.node.ParallelNode.getExecutor;

/**
 * Node executor that processes node execution and result handling. This class
 * demonstrates inheritance by extending BaseGraphExecutor. It also demonstrates
 * polymorphism through its specific implementation of execute.
 */
public class NodeExecutor extends BaseGraphExecutor {

	private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

	private record GraphFluxExecution<T>(GraphFlux<T> graphFlux, AtomicReference<Object> nodeData,
			AtomicReference<T> rawResult, Flux<GraphResponse<NodeOutput>> responses) {
	}

	private final MainGraphExecutor mainGraphExecutor;

	public NodeExecutor(MainGraphExecutor mainGraphExecutor) {
		this.mainGraphExecutor = mainGraphExecutor;
	}

	/**
	 * Implementation of the execute method. This demonstrates polymorphism as it provides
	 * a specific implementation for node execution.
	 * @param context the graph runner context
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with execution result
	 */
	@Override
	public Flux<GraphResponse<NodeOutput>> execute(GraphRunnerContext context, AtomicReference<Object> resultValue) {
		return executeNode(context, resultValue);
	}

	/**
	 * Executes a node and handles its result.
	 * @param context the graph runner context
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with node execution result
	 */
	private Flux<GraphResponse<NodeOutput>> executeNode(GraphRunnerContext context,
			AtomicReference<Object> resultValue) {
		try {
			context.assertExecutionActive();
			context.setCurrentNodeId(context.getNextNodeId());
			String currentNodeId = context.getCurrentNodeId();
			AsyncNodeActionWithConfig action = context.getNodeAction(currentNodeId);

			if (action == null) {
				return Flux.just(GraphResponse.error(RunnableErrors.missingNode.exception(currentNodeId)));
			}

			if (action instanceof InterruptableAction) {
				context.assertExecutionActive();
				context.getConfig().metadata(RunnableConfig.STATE_UPDATE_METADATA_KEY).ifPresent(updateFromFeedback -> {
					if (updateFromFeedback instanceof Map<?, ?>) {
						context.mergeIntoCurrentState((Map<String, Object>) updateFromFeedback);
					}
					else {
						throw new RuntimeException();
					}
				});
				Optional<InterruptionMetadata> interruptMetadata = ((InterruptableAction) action)
					.interrupt(currentNodeId, context.cloneState(context.getCurrentStateData()), context.getConfig());
				if (interruptMetadata.isPresent()) {
					resultValue.set(interruptMetadata.get());
					return Flux.just(GraphResponse.done(interruptMetadata.get()));
				}
			}

			context.doListeners(NODE_BEFORE, null);

			context.assertExecutionActive();
			CompletableFuture<Map<String, Object>> future = action.apply(context.getOverallState(),
					context.getConfig());

			return Mono.fromFuture(future)
				.flatMapMany(updateState -> handleActionResult(context, updateState, resultValue))
				.onErrorResume(error -> {
					context.doListeners(ERROR, new Exception(error));
					return Flux.just(GraphResponse.error(error));
				});

		}
		catch (Exception e) {
			return Flux.just(GraphResponse.error(e));
		}
	}

	/**
	 * Handles the action result and returns appropriate response.
	 * @param context the graph runner context
	 * @param updateState the updated state from the action
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with action result handling
	 */
	private Flux<GraphResponse<NodeOutput>> handleActionResult(GraphRunnerContext context,
			Map<String, Object> updateState, AtomicReference<Object> resultValue) {
		try {
			context.assertExecutionActive();

			// Check for Flux
			Optional<Flux<GraphResponse<NodeOutput>>> embedFlux = getEmbedFlux(context, updateState);
			if (embedFlux.isPresent()) {
				return handleEmbeddedFlux(mainGraphExecutor, context, embedFlux.get(), updateState, resultValue);
			}

			// Check for ParallelGraphFlux (returned from ParallelNode)
			Optional<ParallelGraphFlux> embedParallelGraphFlux = getEmbedParallelGraphFlux(updateState);
			if (embedParallelGraphFlux.isPresent()) {
				return handleParallelGraphFlux(context, embedParallelGraphFlux.get(), updateState, resultValue);
			}

			// Check for GraphFlux (backward compatibility)
			Optional<GraphFlux<?>> embedGraphFlux = getEmbedGraphFlux(updateState, context);
			if (embedGraphFlux.isPresent()) {
				return handleGraphFlux(context, embedGraphFlux.get(), updateState, resultValue);
			}

			// Check for interruptAfter hook (after apply() but before state merge)
			String currentNodeId = context.getCurrentNodeId();
			AsyncNodeActionWithConfig action = context.getNodeAction(currentNodeId);
			if (action instanceof InterruptableAction) {
				context.assertExecutionActive();
				Optional<InterruptionMetadata> interruptMetadata = ((InterruptableAction) action).interruptAfter(
						currentNodeId, context.cloneState(context.getCurrentStateData()), updateState,
						context.getConfig());
				if (interruptMetadata.isPresent()) {
					// Merge state first to ensure correct state on resume
					context.mergeIntoCurrentState(updateState);
					// Determine next node before creating checkpoint
					Command nextCommand = context.nextNodeId(currentNodeId, context.getCurrentStateData());
					context.setNextNodeId(nextCommand.gotoNode());
					// Build checkpoint with correct nextNodeId
					context.buildNodeOutputAndAddCheckpoint(updateState);
					// Call NODE_AFTER listeners
					context.doListeners(NODE_AFTER, null);
					// Return interruption
					resultValue.set(interruptMetadata.get());
					return Flux.just(GraphResponse.done(interruptMetadata.get()));
				}
			}

			context.assertExecutionActive();
			context.mergeIntoCurrentState(updateState);

			if (context.getCompiledGraph().compileConfig.interruptBeforeEdge()
					&& context.getCompiledGraph().compileConfig.interruptsAfter()
						.contains(context.getCurrentNodeId())) {
				context.setNextNodeId(INTERRUPT_AFTER);
			}
			else {
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				context.setNextNodeId(nextCommand.gotoNode());
			}
			NodeOutput output = context.buildNodeOutputAndAddCheckpoint(updateState);

			context.assertExecutionActive();
			context.doListeners(NODE_AFTER, null);
			// Continue with the main execution handler (expanded iteratively by
			// GraphRunner)
			return Flux.just(GraphResponse.of(output),
					GraphResponse.continueWith(() -> mainGraphExecutor.execute(context, resultValue)));
		}
		catch (Exception e) {
			return Flux.just(GraphResponse.error(e));
		}
	}

	/**
	 * Transforms a raw Flux to Flux<GraphResponse<NodeOutput>> with embedded flux
	 * processing logic. This is the core transformation logic extracted from getEmbedFlux
	 * for reuse.
	 * @param context the graph runner context
	 * @param rawFlux the raw flux to transform
	 * @param key the key associated with the flux (for logging and completion result)
	 * @param nodeId the node ID to use for building streaming output
	 * @return Flux of GraphResponse with transformed elements
	 */
	private Flux<GraphResponse<NodeOutput>> transformFluxToGraphResponse(GraphRunnerContext context, Flux<?> rawFlux,
			String key, String nodeId) {
		var lastChatResponseRef = new AtomicReference<ChatResponse>(null);
		var lastGraphResponseRef = new AtomicReference<GraphResponse<NodeOutput>>(null);
		var upstreamAlreadyEmittedCompletion = new AtomicBoolean(false);
		var chatEventsSeen = new AtomicBoolean(false);
		var terminalChatEventSeen = new AtomicBoolean(false);
		var lastUsage = new AtomicReference<io.github.agentic.ai.api.chat.Usage>();

		return rawFlux.filter(element -> {
			// Don't filter out Exception/Throwable - we need to handle them
			return true;
		})
			.switchIfEmpty(Flux.error(new IllegalStateException(
					"Empty flux detected for key '" + key + "'. This may indicate an LLM API error with null result.")))
			.map(element -> {
				if (element instanceof io.github.agentic.ai.api.chat.ChatEvent event) {
					chatEventsSeen.set(true);
					if (terminalChatEventSeen.get()) {
						throw new IllegalStateException("Chat event received after terminal response");
					}
					if (event.usage() != null) {
						lastUsage.set(event.usage());
					}
					if (event.type() == io.github.agentic.ai.api.chat.ChatEvent.Type.RESPONSE) {
						ChatResponse response = event.response();
						if (response == null || response.messages().isEmpty()) {
							throw new IllegalStateException("Terminal chat response must contain a message");
						}
						terminalChatEventSeen.set(true);
						lastChatResponseRef.set(response.usage() != null ? response
								: new ChatResponse(response.id(), response.model(), response.messages(),
										response.toolCalls(), response.finishReason(), lastUsage.get(),
										response.metadata()));
						// Preserve the terminal event without replaying the aggregate as
						// a text delta.
						StreamingOutput<?> terminal = context.buildStreamingOutput(null, event, nodeId, true);
						terminal.setTokenUsage(lastChatResponseRef.get().usage());
						return GraphResponse.<NodeOutput>of(terminal);
					}
					GraphResponse<NodeOutput> response = GraphResponse
						.of(context.buildStreamingOutput(event, nodeId, true));
					lastGraphResponseRef.set(response);
					return response;
				}
				// Handle Exception/Throwable as data elements (not error signals)
				if (element instanceof Throwable throwable) {
					log.error("Exception emitted as data element in embedded Flux stream for key '{}': {}", key,
							throwable.getMessage(), throwable);
					GraphResponse<NodeOutput> errorResponse = GraphResponse.error(throwable);
					lastGraphResponseRef.set(errorResponse);
					return errorResponse;
				}
				if (element instanceof ChatResponse response) {
					// Handle usage-only chunks (null result/output) without dropping
					// usage metadata
					if (response.messages().isEmpty()) {
						ChatResponse previous = lastChatResponseRef.get();
						if (previous != null) {
							lastChatResponseRef.set(new ChatResponse(response.id(), response.model(),
									previous.messages(), previous.toolCalls(), response.finishReason(),
									response.usage(), response.metadata()));
						}
						GraphResponse<NodeOutput> graphResponse = GraphResponse
							.of(context.buildStreamingOutput(response, nodeId, true));
						lastGraphResponseRef.set(graphResponse);
						return graphResponse;
					}
					ChatResponse lastResponse = lastChatResponseRef.get();
					final var currentMessage = response.messages().get(0);

					boolean finalResponse = Boolean.TRUE.equals(response.metadata().get("argiFinalResponse"));
					if (lastResponse == null || finalResponse) {
						lastChatResponseRef.set(response);
					}
					else {
						var lastOutput = lastResponse.messages().get(0);
						var lastMessageText = "";
						if (lastOutput.text() != null) {
							lastMessageText = lastOutput.text();
						}

						final var currentMessageText = currentMessage.text();

						boolean mergeReasoningContent = context.getConfig().mergeReasoningContent();
						Map<String, Object> messageMetadata = mergeReasoningContent
								&& !Boolean.TRUE.equals(response.metadata().get("argiFinalResponse"))
										? mergeMetadataWithReasoningContent(lastOutput.metadata(),
												currentMessage.metadata())
										: currentMessage.metadata();

						var newMessage = currentMessage.withText(lastMessageText.concat(currentMessageText))
							.withMetadata(messageMetadata)
							.withToolCalls(Boolean.TRUE.equals(response.metadata().get("argiFinalResponse"))
									? currentMessage.toolCalls()
									: mergeToolCalls(lastOutput.toolCalls(), currentMessage.toolCalls()));
						ChatResponse newResponse = new ChatResponse(response.id(), response.model(),
								List.of(newMessage), newMessage.toolCalls(), response.finishReason(), response.usage(),
								response.metadata());
						lastChatResponseRef.set(newResponse);
					}
					var visibleMessage = currentMessage;
					if (finalResponse && response.metadata().get("argiTextDelta") instanceof String delta) {
						var parts = new ArrayList<io.github.agentic.ai.api.chat.ContentPart>();
						if (!delta.isEmpty()) {
							parts.add(io.github.agentic.ai.api.chat.ContentPart.text(delta));
						}
						currentMessage.content()
							.stream()
							.filter(part -> part.type() != io.github.agentic.ai.api.chat.ContentPart.Type.TEXT
									&& part.type() != io.github.agentic.ai.api.chat.ContentPart.Type.JSON)
							.forEach(parts::add);
						visibleMessage = new ChatMessage(currentMessage.role(), parts, currentMessage.toolCalls(),
								currentMessage.toolCallId(), currentMessage.toolName(), currentMessage.metadata());
					}
					GraphResponse<NodeOutput> lastGraphResponse = GraphResponse
						.of(context.buildStreamingOutput(visibleMessage, response, nodeId, true));
					lastGraphResponseRef.set(lastGraphResponse);
					return lastGraphResponse;
				}
				else if (element instanceof GraphResponse) {
					GraphResponse<NodeOutput> graphResponse = (GraphResponse<NodeOutput>) element;
					if (graphResponse.isDone() || graphResponse.resultValue().isPresent()) {
						upstreamAlreadyEmittedCompletion.set(true);
					}
					lastGraphResponseRef.set(graphResponse);
					return graphResponse;
				}
				else if (element instanceof NodeOutput nodeOutput) {
					GraphResponse<NodeOutput> graphResponse = GraphResponse.of(nodeOutput);
					lastGraphResponseRef.set(graphResponse);
					return graphResponse;
				}
				else {
					try {
						log.info(
								"Received element of type '{}' in embedded Flux for key '{}', wrapping in StreamingOutput.",
								element.getClass().getName(), key);
						StreamingOutput<?> streamingOutput = context.buildStreamingOutput(element, nodeId, true);
						GraphResponse<NodeOutput> graphResponse = GraphResponse.of(streamingOutput);
						lastGraphResponseRef.set(graphResponse);
						return graphResponse;
					}
					catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				}
			})
			.filter(response -> {
				if (response.getOutput() == null || response.isError()) {
					return true;
				}
				var output = response.getOutput().getNow(null);
				if (output instanceof StreamingOutput<?> streaming
						&& streaming.getOriginData() instanceof ChatResponse chatResponse
						&& Boolean.TRUE.equals(chatResponse.metadata().get("argiFinalResponse"))) {
					// The terminal aggregate updates state and usage without an extra
					// visible blank chunk.
					if (chatResponse.messages().isEmpty()) {
						return false;
					}
					var message = streaming.messages().get(0);
					return message.hasToolCalls() || message.text() != null && !message.text().isEmpty()
							|| message.content()
								.stream()
								.anyMatch(part -> part.type() != io.github.agentic.ai.api.chat.ContentPart.Type.TEXT
										&& part.type() != io.github.agentic.ai.api.chat.ContentPart.Type.JSON);
				}
				return true;
			})
			// Error responses are terminal even when upstream represents failures as
			// data.
			.takeUntil(GraphResponse::isError)
			.onErrorResume(error -> {
				// Handle actual error signals from the Flux
				log.error("Error signal occurred in embedded Flux stream for key '{}': {}", key, error.getMessage());
				GraphResponse<NodeOutput> errorResponse = GraphResponse.error(error);
				lastGraphResponseRef.set(errorResponse);
				return Flux.just(errorResponse);
			})
			.concatWith(Flux.defer(() -> {
				if (lastGraphResponseRef.get() != null && lastGraphResponseRef.get().isError()) {
					return Flux.empty();
				}
				if (upstreamAlreadyEmittedCompletion.get()) {
					return Flux.empty();
				}
				if (chatEventsSeen.get() && !terminalChatEventSeen.get()) {
					return Flux.just(GraphResponse
						.error(new IllegalStateException("Chat stream completed without a terminal response")));
				}
				if (lastChatResponseRef.get() == null) {
					GraphResponse<NodeOutput> lastGraphResponse = lastGraphResponseRef.get();
					if (lastGraphResponse != null && lastGraphResponse.resultValue().isPresent()) {
						Object result = lastGraphResponse.resultValue().get();

						// don't re-emit InterruptionMetadata, it will be handled by
						// MainGraphExecutor
						if (result instanceof InterruptionMetadata) {
							return Flux.empty();
						}

						if (result instanceof Map resultMap) {
							if (!resultMap.containsKey(key) && resultMap.containsKey("messages")) {
								List<Object> messages = (List<Object>) resultMap.get("messages");
								Object lastMessage = messages.get(messages.size() - 1);
								if (lastMessage instanceof ChatMessage lastAssistantMessage
										&& lastAssistantMessage.role() == ChatRole.ASSISTANT) {
									resultMap.put(key, lastAssistantMessage.text());
								}
							}
						}
						return Flux.just(lastGraphResponse);
					}
					return Flux.empty();
				}
				else {
					ChatResponse lastChatResponse = lastChatResponseRef.get();
					// FINISHED StreamingOutput omits message for agent/graph LLM nodes
					// (tool/hook unchanged); full text still in done(state).
					ChatMessage messageForCompletion = lastChatResponse.messages().get(0);
					if (shouldOmitMessageOnStreamCompletion(nodeId)) {
						messageForCompletion = null;
					}
					GraphResponse<NodeOutput> aggregatedResponse = GraphResponse
						.of(context.buildStreamingOutput(messageForCompletion, lastChatResponse, nodeId, false));
					// Then emit the completion response
					Map<String, Object> completionResult = new HashMap<>();
					completionResult.put(key, lastChatResponse.messages().get(0));
					if (!key.equals("messages")) {
						completionResult.put("messages", lastChatResponse.messages().get(0));
					}
					GraphResponse<NodeOutput> doneResponse = GraphResponse.done(completionResult);
					return Flux.just(aggregatedResponse, doneResponse);
				}
			}));
	}

	/**
	 * Merges metadata from last and current streaming chunks, aggregating
	 * {@code reasoning} by concatenation (each chunk carries partial content). Other
	 * metadata keys are taken from current; fallback to last when absent.
	 */
	private static Map<String, Object> mergeMetadataWithReasoningContent(Map<String, Object> lastMetadata,
			Map<String, Object> currentMetadata) {
		Map<String, Object> merged = new LinkedHashMap<>();
		if (lastMetadata != null) {
			merged.putAll(lastMetadata);
		}
		if (currentMetadata != null) {
			merged.putAll(currentMetadata);
		}
		// Aggregate reasoning metadata: concatenate last + current (streaming chunks are
		// incremental)
		String key = ChatMetadataKeys.REASONING;
		String last = (lastMetadata != null && lastMetadata.containsKey(key)) ? String.valueOf(lastMetadata.get(key))
				: "";
		String current = (currentMetadata != null && currentMetadata.containsKey(key))
				? String.valueOf(currentMetadata.get(key)) : "";
		if (!last.isEmpty() || !current.isEmpty()) {
			String aggregated = last.isEmpty() ? current : (current.isEmpty() ? last : last + current);
			merged.put(key, aggregated);
		}
		return merged;
	}

	/**
	 * Merges tool calls from two streamed assistant messages.
	 * <p>
	 * Streaming chunks from some providers may split tool-call fields across multiple
	 * chunks (for example: first chunk has name, later chunk has only arguments and even
	 * no id). We merge by id when possible, and fall back to positional merge to keep a
	 * single tool call record complete.
	 * @return the merged list of tool calls
	 */
	private List<ToolCall> mergeToolCalls(List<ToolCall> lastToolCalls, List<ToolCall> currentToolCalls) {
		if (lastToolCalls == null || lastToolCalls.isEmpty()) {
			return currentToolCalls != null ? currentToolCalls : List.of();
		}
		if (currentToolCalls == null || currentToolCalls.isEmpty()) {
			return lastToolCalls;
		}

		List<ToolCall> merged = new ArrayList<>(lastToolCalls);
		for (int i = 0; i < currentToolCalls.size(); i++) {
			ToolCall current = currentToolCalls.get(i);
			if (current == null) {
				continue;
			}
			int mergeIndex = findToolCallMergeIndex(merged, current, i);
			if (mergeIndex >= 0) {
				merged.set(mergeIndex, mergeToolCallFields(merged.get(mergeIndex), current));
			}
			else {
				merged.add(current);
			}
		}
		return merged;
	}

	private int findToolCallMergeIndex(List<ToolCall> mergedCalls, ToolCall currentToolCall, int currentIndex) {
		String currentId = normalized(currentToolCall.id());
		if (StringUtils.hasText(currentId)) {
			for (int i = 0; i < mergedCalls.size(); i++) {
				if (currentId.equals(normalized(mergedCalls.get(i).id()))) {
					return i;
				}
			}
		}

		if (currentIndex < mergedCalls.size()) {
			return currentIndex;
		}

		String currentName = normalized(currentToolCall.name());
		if (StringUtils.hasText(currentName)) {
			int matchedIndex = -1;
			for (int i = 0; i < mergedCalls.size(); i++) {
				if (currentName.equals(normalized(mergedCalls.get(i).name()))) {
					if (matchedIndex >= 0) {
						return -1;
					}
					matchedIndex = i;
				}
			}
			if (matchedIndex >= 0) {
				return matchedIndex;
			}
		}

		return -1;
	}

	private ToolCall mergeToolCallFields(ToolCall previous, ToolCall current) {
		return new io.github.agentic.ai.api.chat.ToolCall(firstNonBlank(current.id(), previous.id()),
				firstNonBlank(current.name(), previous.name()),
				mergeToolArguments(previous.arguments(), current.arguments()));
	}

	private static String mergeToolArguments(String previousArguments, String currentArguments) {
		if (!StringUtils.hasText(previousArguments)) {
			return currentArguments;
		}
		if (!StringUtils.hasText(currentArguments)) {
			return previousArguments;
		}

		if (currentArguments.equals(previousArguments)) {
			return currentArguments;
		}
		if (currentArguments.startsWith(previousArguments) || currentArguments.contains(previousArguments)) {
			return currentArguments;
		}
		if (previousArguments.startsWith(currentArguments) || previousArguments.contains(currentArguments)) {
			return previousArguments;
		}

		// Typical streaming delta case: append incremental argument fragment.
		return previousArguments + currentArguments;
	}

	private static String firstNonBlank(String primary, String fallback) {
		return StringUtils.hasText(primary) ? primary : fallback;
	}

	private static String normalized(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	/**
	 * Whether the FINISHED {@link StreamingOutput} after a {@link ChatResponse} flux
	 * should omit {@link ChatMessage} text (agent model and ordinary graph LLM nodes).
	 * Tool/hook streams keep the message.
	 */
	private static boolean shouldOmitMessageOnStreamCompletion(String nodeId) {
		return nodeId.startsWith(RunnableConfig.AGENT_MODEL_NAME) || (!nodeId.startsWith(RunnableConfig.AGENT_TOOL_NAME)
				&& !nodeId.startsWith(RunnableConfig.AGENT_HOOK_NAME_PREFIX));
	}

	/**
	 * Processes a Flux<GraphResponse<NodeOutput>> with embedded flux handling logic. This
	 * is the core processing logic extracted from handleEmbeddedFlux for reuse.
	 * @param mainGraphExecutor the main graph executor
	 * @param context the graph runner context
	 * @param embedFlux the embedded flux to process
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with processed result
	 */
	private Flux<GraphResponse<NodeOutput>> processGraphResponseFlux(MainGraphExecutor mainGraphExecutor,
			GraphRunnerContext context, Flux<GraphResponse<NodeOutput>> embedFlux, Map<String, Object> partialState,
			AtomicReference<Object> resultValue) {
		AtomicReference<GraphResponse<NodeOutput>> lastData = new AtomicReference<>();

		Flux<GraphResponse<NodeOutput>> processedFlux = embedFlux.map(data -> {
			if (data.getOutput() != null && !data.getOutput().isCompletedExceptionally()) {
				var output = data.getOutput().join();
				output.setSubGraph(true);
				GraphResponse<NodeOutput> newData = GraphResponse.of(output);
				lastData.set(newData);
				return newData;
			}
			lastData.set(data);
			return data;
		})
			// filter out InterruptionMetadata emitted directly by upstream to avoid
			// duplicate sending
			// retain regular procedural events
			.filter(data -> {
				var value = data.resultValue();
				return value.isEmpty() || !(value.get() instanceof InterruptionMetadata);
			});

		Mono<Void> updateContextMono = Mono.fromRunnable(() -> {
			var data = lastData.get();
			if (data == null) {
				log.error("No data returned from last streaming node execution '{}', will goto END node directly.",
						context.getCurrentNodeId());
				context.setNextNodeId(END);
				context.doListeners(NODE_AFTER, null);
				return;
			}

			var nodeResultValue = data.resultValue();

			if (nodeResultValue.isPresent() && nodeResultValue.get() instanceof InterruptionMetadata) {
				context.setReturnFromEmbedWithValue(nodeResultValue.get());
				return;
			}

			Map<String, Object> partialStateWithoutFlux = partialState.entrySet()
				.stream()
				.filter(e -> !(e.getValue() instanceof Flux) && !(e.getValue() instanceof GraphFlux)
						&& !(e.getValue() instanceof ParallelGraphFlux))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			Map<String, Object> updateState = new HashMap<>();
			if (nodeResultValue.isPresent()) {
				Object value = nodeResultValue.get();
				if (value instanceof Map<?, ?>) {
					updateState = (Map<String, Object>) value;
				}
				else if (value instanceof BaseCheckpointSaver.Tag) {
					// When releaseThread=true, completion may return a Tag.
					// Tag is a checkpoint-release receipt, not a state update, so skip
					// merge here.
				}
				else {
					throw new IllegalArgumentException("Node stream must return Map result using Data.done(),");
				}
			}

			Map<String, Object> combinedUpdateState = new HashMap<>(partialStateWithoutFlux);
			combinedUpdateState.putAll(updateState);
			Optional<InterruptionMetadata> interruptAfterMetadata = interruptAfterForStreaming(context,
					combinedUpdateState);

			context.mergeIntoCurrentState(partialStateWithoutFlux);
			context.mergeIntoCurrentState(updateState);

			try {
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				context.setNextNodeId(nextCommand.gotoNode());

				context.buildNodeOutputAndAddCheckpoint(updateState);

				context.doListeners(NODE_AFTER, null);
				interruptAfterMetadata.ifPresent(context::setReturnFromEmbedWithValue);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		return processedFlux.concatWith(Flux.defer(() -> {
			if (lastData.get() != null && lastData.get().isError()) {
				return Flux.empty();
			}
			return updateContextMono
				.thenMany(Flux.just(GraphResponse.continueWith(() -> mainGraphExecutor.execute(context, resultValue))));
		}));
	}

	/**
	 * Gets embed flux from partial state.
	 * @param context the graph runner context
	 * @param partialState the partial state containing flux instances
	 * @return an Optional containing Data with the flux if found, empty otherwise
	 */
	public Optional<Flux<GraphResponse<NodeOutput>>> getEmbedFlux(GraphRunnerContext context,
			Map<String, Object> partialState) {
		return partialState.entrySet().stream().filter(e -> e.getValue() instanceof Flux<?>).findFirst().map(e -> {
			var chatFlux = (Flux<?>) e.getValue();
			return transformFluxToGraphResponse(context, chatFlux, e.getKey(), context.getCurrentNodeId());
		});
	}

	/**
	 * Handles embedded flux processing.
	 * @param context the graph runner context
	 * @param embedFlux the embedded flux to handle
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with embedded flux handling result
	 */
	public Flux<GraphResponse<NodeOutput>> handleEmbeddedFlux(MainGraphExecutor mainGraphExecutor,
			GraphRunnerContext context, Flux<GraphResponse<NodeOutput>> embedFlux, Map<String, Object> partialState,
			AtomicReference<Object> resultValue) {
		return processGraphResponseFlux(mainGraphExecutor, context, embedFlux, partialState, resultValue);
	}

	/**
	 * Gets GraphFlux from partial state.
	 * @param partialState the partial state containing GraphFlux instances
	 * @return an Optional containing GraphFlux if found, empty otherwise
	 */
	private Optional<GraphFlux<?>> getEmbedGraphFlux(Map<String, Object> partialState, GraphRunnerContext context) {
		return partialState.entrySet()
			.stream()
			.filter(e -> e.getValue() instanceof GraphFlux<?>)
			.findFirst()
			.map(e -> copyGraphFluxWithDefaults((GraphFlux<?>) e.getValue(), context.getCurrentNodeId(), e.getKey()));
	}

	private static <T> GraphFlux<T> copyGraphFluxWithDefaults(GraphFlux<T> graphFlux, String defaultNodeId,
			String defaultKey) {
		String nodeId = StringUtils.hasText(graphFlux.getNodeId()) ? graphFlux.getNodeId() : defaultNodeId;
		String key = StringUtils.hasText(graphFlux.getKey()) ? graphFlux.getKey() : defaultKey;
		return GraphFlux.of(nodeId, key, graphFlux.getFlux(), graphFlux.getMapResult(), graphFlux.getChunkResult());
	}

	/**
	 * Gets ParallelGraphFlux from partial state.
	 * @param partialState the partial state containing ParallelGraphFlux instances
	 * @return an Optional containing ParallelGraphFlux if found, empty otherwise
	 */
	private Optional<ParallelGraphFlux> getEmbedParallelGraphFlux(Map<String, Object> partialState) {
		return partialState.entrySet()
			.stream()
			.filter(e -> e.getValue() instanceof ParallelGraphFlux)
			.findFirst()
			.map(e -> (ParallelGraphFlux) e.getValue());
	}

	/**
	 * Handles GraphFlux processing with combined embedded flux transformation and
	 * processing. This method applies both getEmbedFlux transformation logic and
	 * handleEmbeddedFlux processing logic.
	 * @param context the graph runner context
	 * @param graphFlux the GraphFlux to handle
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with GraphFlux handling result
	 */
	private <T> Flux<GraphResponse<NodeOutput>> transformGraphFluxToFlux(GraphRunnerContext context,
			GraphFlux<T> graphFlux, Map<String, Object> partialState, AtomicReference<Object> resultValue,
			AtomicReference<T> rawResultValue) {
		// Use nodeId from GraphFlux instead of context to preserve real node identity
		String effectiveNodeId = graphFlux.getNodeId();
		String key = graphFlux.getKey() != null ? graphFlux.getKey() : "result";
		Flux<T> sourceFlux = graphFlux.getFlux().doOnNext(rawResultValue::set);
		Flux<?> rawFlux = sourceFlux;
		if (graphFlux.hasChunkResult()) {
			rawFlux = sourceFlux.map(graphFlux.getChunkResult());
		}

		// Step 1: Apply getEmbedFlux transformation logic to graphFlux.getFlux()
		Flux<GraphResponse<NodeOutput>> transformedFlux = transformFluxToGraphResponse(context, rawFlux, key,
				effectiveNodeId);

		// Step 2: Apply handleEmbeddedFlux processing logic (directly implemented)

		return transformedFlux.map(data -> {
			if (data.getOutput() != null && !data.getOutput().isCompletedExceptionally()) {
				var output = data.getOutput().join();
				output.setSubGraph(true);
				GraphResponse<NodeOutput> newData = GraphResponse.of(output);
				resultValue.set(newData);
				return newData;
			}
			resultValue.set(data);
			return data;
		})
			// filter out InterruptionMetadata emitted directly by upstream to avoid
			// duplicate sending
			// retain regular procedural events
			.filter(data -> {
				var value = data.resultValue();
				return value.isEmpty() || !(value.get() instanceof InterruptionMetadata);
			});
	}

	/**
	 * Handles GraphFlux processing with node ID preservation.
	 * @param context the graph runner context
	 * @param graphFlux the GraphFlux to handle
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with GraphFlux handling result
	 */
	private <T> Flux<GraphResponse<NodeOutput>> handleGraphFlux(GraphRunnerContext context, GraphFlux<T> graphFlux,
			Map<String, Object> partialState, AtomicReference<Object> resultValue) {

		// Use nodeId from GraphFlux instead of context to preserve real node identity
		String effectiveNodeId = graphFlux.getNodeId();
		AtomicReference<Object> lastDataRef = new AtomicReference<>();
		AtomicReference<T> rawResultRef = new AtomicReference<>();

		// Process the GraphFlux stream with preserved node ID
		Flux<GraphResponse<NodeOutput>> processedFlux = transformGraphFluxToFlux(context, graphFlux, partialState,
				lastDataRef, rawResultRef);

		// Handle completion and result mapping
		Mono<Void> updateContextMono = Mono.fromRunnable(() -> {
			Object lastData = lastDataRef.get();

			if (lastData == null) {
				log.error("No data returned from last streaming node execution '{}', will goto END node directly.",
						context.getCurrentNodeId());
				context.setNextNodeId(END);
				return;
			}

			// Resolve the final GraphFlux result into a graph state update.
			Map<String, Object> resultMap = graphFluxResultState(graphFlux, lastData, rawResultRef.get());

			// Merge non-GraphFlux state
			Map<String, Object> partialStateWithoutGraphFlux = partialState.entrySet()
				.stream()
				.filter(e -> !(e.getValue() instanceof GraphFlux))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			Map<String, Object> combinedUpdateState = new HashMap<>(partialStateWithoutGraphFlux);
			combinedUpdateState.putAll(resultMap);
			Optional<InterruptionMetadata> interruptAfterMetadata = interruptAfterForStreaming(context,
					combinedUpdateState);

			context.mergeIntoCurrentState(partialStateWithoutGraphFlux);
			if (!resultMap.isEmpty()) {
				context.mergeIntoCurrentState(resultMap);
			}

			try {
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				context.setNextNodeId(nextCommand.gotoNode());

				context.buildNodeOutputAndAddCheckpoint(partialStateWithoutGraphFlux);

				context.doListeners(NODE_AFTER, null);
				interruptAfterMetadata.ifPresent(context::setReturnFromEmbedWithValue);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		return processedFlux.concatWith(Flux.defer(() -> {
			if (lastDataRef.get() instanceof GraphResponse<?> response && response.isError()) {
				return Flux.empty();
			}
			return updateContextMono
				.thenMany(Flux.just(GraphResponse.continueWith(() -> mainGraphExecutor.execute(context, resultValue))));
		}));
	}

	private <T> Map<String, Object> graphFluxResultState(GraphFlux<T> graphFlux, Object lastData, T rawResult) {
		if (graphFlux.hasMapResult()) {
			Object mappedResult = graphFlux.getMapResult().apply(rawResult);
			if (mappedResult instanceof Map<?, ?> resultMap) {
				return copyStateMap(resultMap);
			}

			Map<String, Object> state = new HashMap<>();
			state.put(graphFluxStateKey(graphFlux), mappedResult);
			return state;
		}

		if (lastData instanceof GraphResponse<?> graphResponse) {
			Optional<Object> resultValue = graphResponse.resultValue();
			if (resultValue.isPresent()) {
				Object value = resultValue.get();
				if (value instanceof Map<?, ?> resultMap) {
					return copyStateMap(resultMap);
				}
			}
		}

		Map<String, Object> state = new HashMap<>();
		state.put(graphFluxStateKey(graphFlux), lastData);
		return state;
	}

	private static String graphFluxStateKey(GraphFlux<?> graphFlux) {
		return StringUtils.hasText(graphFlux.getKey()) ? graphFlux.getKey() : "result";
	}

	private static Map<String, Object> copyStateMap(Map<?, ?> resultMap) {
		Map<String, Object> state = new HashMap<>();
		for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException("GraphFlux done result map keys must be String");
			}
			state.put(key, entry.getValue());
		}
		return state;
	}

	/**
	 * Checks interruptAfter hook for streaming nodes using the pre-merge state.
	 * <p>
	 * This method must be called <strong>before</strong> the streaming state updates are
	 * merged into the {@link OverAllState} to keep semantics consistent with the
	 * non-streaming interruptAfter hook.
	 * @param context the graph runner context
	 * @param actionResult the streaming node action result (state delta) passed to
	 * interruptAfter
	 * @return interruption metadata if the hook triggers
	 */
	private Optional<InterruptionMetadata> interruptAfterForStreaming(GraphRunnerContext context,
			Map<String, Object> actionResult) {
		String currentNodeId = context.getCurrentNodeId();
		AsyncNodeActionWithConfig action = context.getNodeAction(currentNodeId);

		if (!(action instanceof InterruptableAction interruptableAction)) {
			return Optional.empty();
		}

		try {
			OverAllState stateBeforeMerge = context.cloneState(context.getCurrentStateData());
			return interruptableAction.interruptAfter(currentNodeId, stateBeforeMerge, actionResult,
					context.getConfig());
		}
		catch (Exception e) {
			context.doListeners(ERROR, e);
			throw new RuntimeException("Failed to check interruptAfter hook for streaming node", e);
		}
	}

	/**
	 * Handles ParallelGraphFlux processing with node ID preservation for all parallel
	 * streams.
	 * @param context the graph runner context
	 * @param parallelGraphFlux the ParallelGraphFlux to handle
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with ParallelGraphFlux handling result
	 */
	private Flux<GraphResponse<NodeOutput>> handleParallelGraphFlux(GraphRunnerContext context,
			ParallelGraphFlux parallelGraphFlux, Map<String, Object> partialState, AtomicReference<Object> resultValue)
			throws Exception {

		if (parallelGraphFlux.isEmpty()) {
			// Handle empty ParallelGraphFlux
			Map<String, Object> partialStateWithoutParallelGraphFlux = partialState.entrySet()
				.stream()
				.filter(e -> !(e.getValue() instanceof ParallelGraphFlux))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			return handleNonStreamingResult(context, partialStateWithoutParallelGraphFlux, resultValue);
		}

		// Get executor from context, fallback to Schedulers.parallel() if not available
		// Note: DEFAULT_EXECUTOR from ParallelNode is private, so we use
		// Schedulers.parallel() as fallback
		Executor executor = getExecutor(context.getConfig(), context.getCurrentNodeId());

		// Convert Executor to Scheduler for Reactor, use Schedulers.parallel() as
		// fallback
		Scheduler scheduler = executor != null ? Schedulers.fromExecutor(executor) : Schedulers.parallel();

		// Create merged flux from all GraphFlux instances with preserved node IDs
		// Use subscribeOn(scheduler) to ensure each Flux executes in parallel on the
		// scheduler
		List<GraphFluxExecution<?>> executions = parallelGraphFlux.getGraphFluxes()
			.stream()
			.<GraphFluxExecution<?>>map(
					graphFlux -> createGraphFluxExecution(context, graphFlux, partialState, scheduler))
			.toList();
		List<Flux<GraphResponse<NodeOutput>>> fluxList = executions.stream()
			.map(GraphFluxExecution::responses)
			.toList();

		// Merge all parallel streams while preserving node identities
		// Each Flux is already subscribed on the scheduler, so they will execute in
		// parallel
		Flux<GraphResponse<NodeOutput>> mergedFlux = Flux.merge(fluxList).takeUntil(GraphResponse::isError);

		// Handle completion and result mapping for all nodes
		Mono<Void> updateContextMono = Mono.fromRunnable(() -> {
			Map<String, Object> combinedResultMap = new HashMap<>();

			// Include completed non-streaming branches in the same usage aggregate.
			Map<String, Object> partialStateWithoutParallelGraphFlux = partialState.entrySet()
				.stream()
				.filter(e -> !(e.getValue() instanceof ParallelGraphFlux))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			// Process results from each GraphFlux with node-specific prefixes
			for (GraphFluxExecution<?> execution : executions) {
				combinedResultMap = NodeUsage.merge(combinedResultMap, graphFluxResultState(execution),
						context.getKeyStrategyMap());
			}

			// Check interruptAfter hook for streaming nodes using the pre-merge state.
			Map<String, Object> combinedUpdateState = NodeUsage.merge(partialStateWithoutParallelGraphFlux,
					combinedResultMap, context.getKeyStrategyMap());
			Optional<InterruptionMetadata> interruptAfterMetadata = interruptAfterForStreaming(context,
					combinedUpdateState);

			// Keep ordinary delta semantics (including removals) while committing usage
			// only once.
			Map<String, Object> ordinaryPartialState = new HashMap<>(partialStateWithoutParallelGraphFlux);
			ordinaryPartialState.remove(NodeOutput.TOKEN_USAGE_KEY);
			if (combinedUpdateState.containsKey(NodeOutput.TOKEN_USAGE_KEY)) {
				combinedResultMap.put(NodeOutput.TOKEN_USAGE_KEY, combinedUpdateState.get(NodeOutput.TOKEN_USAGE_KEY));
			}
			context.mergeIntoCurrentState(ordinaryPartialState);
			context.mergeIntoCurrentState(combinedResultMap);

			try {
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				context.setNextNodeId(nextCommand.gotoNode());

				context.buildNodeOutputAndAddCheckpoint(partialStateWithoutParallelGraphFlux);

				context.doListeners(NODE_AFTER, null);
				interruptAfterMetadata.ifPresent(context::setReturnFromEmbedWithValue);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		return mergedFlux.concatWith(Flux.defer(() -> {
			if (executions.stream()
				.anyMatch(execution -> execution.nodeData().get() instanceof GraphResponse<?> response
						&& response.isError())) {
				return Flux.empty();
			}
			return updateContextMono
				.thenMany(Flux.just(GraphResponse.continueWith(() -> mainGraphExecutor.execute(context, resultValue))));
		}));
	}

	private <T> GraphFluxExecution<T> createGraphFluxExecution(GraphRunnerContext context, GraphFlux<T> graphFlux,
			Map<String, Object> partialState, Scheduler scheduler) {
		AtomicReference<Object> nodeData = new AtomicReference<>();
		AtomicReference<T> rawResult = new AtomicReference<>();
		Flux<GraphResponse<NodeOutput>> responses = transformGraphFluxToFlux(context, graphFlux, partialState, nodeData,
				rawResult)
			.subscribeOn(scheduler);
		return new GraphFluxExecution<>(graphFlux, nodeData, rawResult, responses);
	}

	private <T> Map<String, Object> graphFluxResultState(GraphFluxExecution<T> execution) {
		return graphFluxResultState(execution.graphFlux(), execution.nodeData().get(), execution.rawResult().get());
	}

	/**
	 * Handles non-streaming result processing.
	 * @param context the graph runner context
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with non-streaming result
	 */
	private Flux<GraphResponse<NodeOutput>> handleNonStreamingResult(GraphRunnerContext context,
			Map<String, Object> partialState, AtomicReference<Object> resultValue) throws Exception {
		context.mergeIntoCurrentState(partialState);
		if (context.getCompiledGraph().compileConfig.interruptBeforeEdge()
				&& context.getCompiledGraph().compileConfig.interruptsAfter().contains(context.getCurrentNodeId())) {
			context.setNextNodeId(INTERRUPT_AFTER);
		}
		else {
			Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
			context.setNextNodeId(nextCommand.gotoNode());
		}

		NodeOutput output = context.buildNodeOutputAndAddCheckpoint(partialState);
		// Continue with the main execution handler (expanded iteratively by GraphRunner)
		return Flux.just(GraphResponse.of(output),
				GraphResponse.continueWith(() -> mainGraphExecutor.execute(context, resultValue)));
	}

}
