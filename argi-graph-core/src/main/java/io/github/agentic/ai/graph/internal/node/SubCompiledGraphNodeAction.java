/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.agentic.ai.graph.internal.node;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.checkpoint.CheckpointExecutionQueue;
import io.github.agentic.ai.graph.utils.TypeRef;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.outputKeyToParent;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.resumeSubGraphId;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.subGraphId;
import static io.github.agentic.ai.graph.StateGraph.START;
import static java.lang.String.format;

/**
 * Represents an action to perform a subgraph on a given state with a specific
 * configuration.
 *
 * <p>
 * This record encapsulates the behavior required to execute a compiled graph using a
 * provided state. It implements the {@link AsyncNodeActionWithConfig} interface, ensuring
 * that the execution is handled asynchronously with the ability to configure settings.
 * </p>
 *
 * {@link OverAllState}.
 *
 * @param subGraph sub graph instance
 * @see CompiledGraph
 * @see AsyncNodeActionWithConfig
 */
public record SubCompiledGraphNodeAction(String nodeId, CompileConfig parentCompileConfig,
		CompiledGraph subGraph) implements AsyncNodeActionWithConfig, ResumableSubGraphAction {

	private static final Object UNCHANGED = new Object();

	public String getResumeSubGraphId() {
		return resumeSubGraphId(nodeId);
	}

	/**
	 * Executes the given graph with the provided state and configuration.
	 * @param state The current state of the system, containing input data and
	 * intermediate results.
	 * @param config The configuration for the graph execution.
	 * @return A {@link CompletableFuture} that will complete with a result of type
	 * {@code Map<String, Object>}. If an exception occurs during execution, the future
	 * will be completed exceptionally.
	 */
	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
		final boolean resumeSubgraph = config.metadata(resumeSubGraphId(nodeId), new TypeRef<Boolean>() {
		}).orElse(false);

		// Build from the parent config so the subgraph receives run-scoped context,
		// while RunnableConfig.Builder keeps the child context isolated.
		RunnableConfig subGraphRunnableConfig = RunnableConfig.builder(config).checkPointId(null).nextNode(null).build();

		var parentSaver = parentCompileConfig.checkpointSaver();
		var subGraphSaver = subGraph.compileConfig.checkpointSaver();

		if (subGraphSaver.isPresent()) {
			if (parentSaver.isEmpty()) {
				return CompletableFuture
					.failedFuture(new IllegalStateException("Missing CheckpointSaver in parent graph!"));
			}

			// Check saver are the same instance
			if (parentSaver.get() == subGraphSaver.get()) {
				subGraphRunnableConfig = RunnableConfig.builder(config)
					.threadId(config.threadId()
						.map(threadId -> format("%s_%s", threadId, subGraphId(nodeId)))
						.orElseGet(() -> subGraphId(nodeId)))
					.nextNode(null)
					.checkPointId(null)
					.build();
			}
		}

		final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

		try {
			final RunnableConfig childConfig = subGraphRunnableConfig;
			Supplier<Flux<GraphResponse<NodeOutput>>> execution = () -> Flux.defer(() -> {
				try {
					RunnableConfig executionConfig = resumeSubgraph
							? subGraph.updateState(childConfig, state.data()) : childConfig;
					AtomicReference<Map<String, Object>> subGraphInputState =
							new AtomicReference<>(new HashMap<>(state.data()));
					return subGraph.graphResponseStream(state, executionConfig)
						.map(response -> toParentDeltaResponse(response, subGraphInputState));
				}
				catch (Exception e) {
					return Flux.error(e);
				}
			});
			// Resuming updates the child's checkpoint before running it. Both operations
			// must wait for the same child-thread permit, including the state read.
			var fluxStream = subGraphSaver.isPresent()
					? CheckpointExecutionQueue.serialize(subGraphSaver.get(), childConfig, execution)
					: execution.get();

			future.complete(Map.of(outputKeyToParent(nodeId), fluxStream));

		}
		catch (Exception e) {

			future.completeExceptionally(e);
		}

		return future;
	}

	private static GraphResponse<NodeOutput> toParentDeltaResponse(GraphResponse<NodeOutput> response,
			AtomicReference<Map<String, Object>> subGraphInputState) {
		if (response.getOutput() != null && !response.getOutput().isCompletedExceptionally()) {
			NodeOutput output = response.getOutput().join();
			if (START.equals(output.node())) {
				subGraphInputState.set(new HashMap<>(output.state().data()));
			}
		}
		if (!response.isDone()) {
			return response;
		}

		Optional<Object> resultValue = response.resultValue();
		if (resultValue.isEmpty() || !(resultValue.get() instanceof Map<?, ?> resultMap)) {
			return response;
		}

		return GraphResponse.done(deltaState(subGraphInputState.get(), resultMap), response.getAllMetadata());
	}

	private static Map<String, Object> deltaState(Map<String, Object> baseState, Map<?, ?> finalState) {
		Map<String, Object> delta = new HashMap<>();
		for (Map.Entry<?, ?> entry : finalState.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException("Subgraph done result map keys must be String");
			}
			Object valueDelta = deltaValue(baseState.get(key), entry.getValue());
			if (valueDelta != UNCHANGED) {
				delta.put(key, valueDelta);
			}
		}
		return delta;
	}

	private static Object deltaValue(Object baseValue, Object finalValue) {
		if (Objects.equals(baseValue, finalValue)) {
			return UNCHANGED;
		}
		if (baseValue instanceof List<?> baseList && finalValue instanceof List<?> finalList
				&& finalList.size() >= baseList.size()
				&& finalList.subList(0, baseList.size()).equals(baseList)) {
			return new ArrayList<>(finalList.subList(baseList.size(), finalList.size()));
		}
		return finalValue;
	}
}
