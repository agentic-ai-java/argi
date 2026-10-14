/*
 * Copyright 2025-2026 the original author or authors.
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

import io.github.agentic.ai.graph.action.AsyncCommandAction;
import io.github.agentic.ai.graph.action.Command;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.action.MultiCommand;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.checkpoint.savers.VersionedMemoryCheckpointSaver;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncEdgeAction.edge_async;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression tests for checkpoint updates and routing with non-idempotent strategies.
 */
@Timeout(15)
class CompiledGraphUpdateStateTest {

	@ParameterizedTest(name = "without asNode, versioned={0}")
	@ValueSource(booleans = { false, true })
	void updateWithoutAsNodePreservesExistingMessages(boolean versioned) throws Exception {
		assertMessagesAreNotAppendedAgain(versioned, false, false);
	}

	@ParameterizedTest(name = "direct edge, versioned={0}")
	@ValueSource(booleans = { false, true })
	void updateAsNodeWithDirectEdgePreservesExistingMessages(boolean versioned) throws Exception {
		assertMessagesAreNotAppendedAgain(versioned, true, false);
	}

	@ParameterizedTest(name = "conditional edge, versioned={0}")
	@ValueSource(booleans = { false, true })
	void updateAsNodeWithConditionalEdgePreservesExistingMessages(boolean versioned) throws Exception {
		assertMessagesAreNotAppendedAgain(versioned, true, true);
	}

	@ParameterizedTest(name = "conditional delta, versioned={0}")
	@ValueSource(booleans = { false, true })
	void conditionalUpdatesAreAppliedOnceAndLegitimateDuplicatesArePreserved(boolean versioned) throws Exception {
		final BaseCheckpointSaver saver = saver(versioned);
		final AtomicInteger routeCalls = new AtomicInteger();
		final StateGraph workflow = new StateGraph(() -> Map.of("messages", new AppendStrategy(), "count", sumStrategy()))
			.addNode("resume", node_async(state -> Map.of()))
			.addEdge(START, "resume")
			.addConditionalEdges("resume", (AsyncCommandAction) (state, config) -> {
				routeCalls.incrementAndGet();
				assertEquals("next", state.value("human_feedback").orElseThrow());
				assertEquals(List.of("seed", "seed"), state.value("messages").orElseThrow());
				assertEquals(12, state.value("count").orElseThrow());
				return completedFuture(new Command("finish", Map.of("messages", List.of("edge"), "count", 3)));
			}, Map.of("finish", END));
		final CompiledGraph graph = compile(workflow, saver);
		final RunnableConfig seeded = seed(saver, Map.of("messages", List.of("seed"), "count", 10));
		final OverAllState originalState = graph.getState(seeded).state();
		final RunnableConfig updated = graph.updateState(seeded,
				Map.of("messages", List.of("seed"), "count", 2, "human_feedback", "next"), "resume");

		assertEquals(1, routeCalls.get());
		assertEquals(END, updated.nextNode().orElseThrow());
		final OverAllState state = graph.getState(updated).state();
		assertEquals(List.of("seed", "seed", "edge"), state.value("messages").orElseThrow());
		assertEquals(15, state.value("count").orElseThrow());
		assertEquals(List.of("seed"), originalState.value("messages").orElseThrow());
		assertEquals(10, originalState.value("count").orElseThrow());
		assertNotEquals(seeded.checkPointId(), updated.checkPointId());
	}

	@ParameterizedTest(name = "direct additive strategy, versioned={0}")
	@ValueSource(booleans = { false, true })
	void directRoutingDoesNotReapplyAnAdditiveStrategy(boolean versioned) throws Exception {
		final BaseCheckpointSaver saver = saver(versioned);
		final StateGraph workflow = new StateGraph(() -> Map.of("count", sumStrategy()))
			.addNode("resume", node_async(state -> Map.of()))
			.addEdge(START, "resume")
			.addEdge("resume", END);
		final CompiledGraph graph = compile(workflow, saver);
		final RunnableConfig seeded = seed(saver, Map.of("count", 10));
		final RunnableConfig updated = graph.updateState(seeded, Map.of("count", 2), "resume");

		assertEquals(12, graph.getState(updated).state().value("count").orElseThrow());
		assertEquals(END, updated.nextNode().orElseThrow());
	}

	@ParameterizedTest(name = "parallel conditional route, versioned={0}")
	@ValueSource(booleans = { false, true })
	void parallelConditionalRoutingDoesNotReapplyState(boolean versioned) throws Exception {
		final BaseCheckpointSaver saver = saver(versioned);
		final AtomicInteger routeCalls = new AtomicInteger();
		final StateGraph workflow = new StateGraph(() -> Map.of("messages", new AppendStrategy()))
			.addNode("resume", node_async(state -> Map.of()))
			.addNode("left", node_async(state -> Map.of("messages", List.of("left"))))
			.addNode("right", node_async(state -> Map.of("messages", List.of("right"))))
			.addEdge(START, "resume")
			.addParallelConditionalEdges("resume", (state, config) -> {
				routeCalls.incrementAndGet();
				return completedFuture(new MultiCommand(List.of("left", "right")));
			}, Map.of("left", "left", "right", "right"))
			.addEdge("left", END)
			.addEdge("right", END);
		final CompiledGraph graph = compile(workflow, saver);
		final RunnableConfig seeded = seed(saver, Map.of("messages", List.of("seed")));
		final RunnableConfig updated = graph.updateState(seeded, Map.of("human_feedback", "next"), "resume");

		assertEquals(List.of("seed"), graph.getState(updated).state().value("messages").orElseThrow());
		assertEquals(0, routeCalls.get(), "Parallel dispatch must remain deferred until resume");
		final NodeOutput last = graph.stream(null, updated.withResume()).blockLast(Duration.ofSeconds(5));
		assertEquals(END, last.node());
		assertEquals(1, routeCalls.get());
		final List<String> messages = last.state().<List<String>>value("messages").orElseThrow();
		assertEquals(3, messages.size());
		assertEquals(Set.of("seed", "left", "right"), Set.copyOf(messages));
	}

	@ParameterizedTest(name = "interrupt-update-resume, versioned={0}")
	@ValueSource(booleans = { false, true })
	void interruptedGraphResumesWithExactlyOneManualAndEdgeUpdate(boolean versioned) throws Exception {
		final BaseCheckpointSaver saver = saver(versioned);
		final AtomicInteger routeCalls = new AtomicInteger();
		final StateGraph workflow = new StateGraph(() -> Map.of("messages", new AppendStrategy()))
			.addNode("prepare", node_async(state -> Map.of("messages", List.of("prepared"))))
			.addNode("finish", node_async(state -> Map.of("messages", List.of("done"))))
			.addEdge(START, "prepare")
			.addConditionalEdges("prepare", (AsyncCommandAction) (state, config) -> {
				routeCalls.incrementAndGet();
				assertEquals("next", state.value("human_feedback").orElseThrow());
				return completedFuture(new Command("approved", Map.of("messages", List.of("edge"))));
			}, Map.of("approved", "finish"))
			.addEdge("finish", END);
		final CompiledGraph graph = workflow.compile(CompileConfig.builder()
			.saverConfig(SaverConfig.builder().register(saver).build())
			.interruptAfter("prepare")
			.interruptBeforeEdge(true)
			.build());
		final RunnableConfig config = RunnableConfig.builder().threadId("interrupted-update").build();
		final NodeOutput interruption = graph.stream(Map.of("messages", List.of("seed")), config)
			.blockLast(Duration.ofSeconds(5));
		assertInstanceOf(InterruptionMetadata.class, interruption);
		assertEquals(List.of("seed", "prepared"), graph.getState(config).state().value("messages").orElseThrow());
		assertEquals(0, routeCalls.get());

		final RunnableConfig updated = graph.updateState(config,
				Map.of("human_feedback", "next", "messages", List.of("feedback")), "prepare");
		assertEquals("finish", updated.nextNode().orElseThrow());
		assertEquals("finish", saver.get(updated).orElseThrow().getNextNodeId());
		final NodeOutput last = graph.stream(null, updated.withResume()).blockLast(Duration.ofSeconds(5));

		assertEquals(END, last.node());
		assertEquals(1, routeCalls.get());
		assertEquals(List.of("seed", "prepared", "feedback", "edge", "done"),
				last.state().value("messages").orElseThrow());
		assertEquals(last.state().value("messages"), graph.getState(config).state().value("messages"));
	}

	private static BaseCheckpointSaver saver(boolean versioned) {
		return versioned ? new VersionedMemoryCheckpointSaver() : new MemorySaver();
	}

	private static KeyStrategy sumStrategy() {
		return (oldValue, newValue) -> (oldValue == null ? 0 : ((Number) oldValue).intValue())
				+ ((Number) newValue).intValue();
	}

	private static CompiledGraph compile(StateGraph workflow, BaseCheckpointSaver saver) throws Exception {
		return workflow.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
	}

	private static RunnableConfig seed(BaseCheckpointSaver saver, Map<String, Object> state) throws Exception {
		return saver.put(RunnableConfig.builder().threadId("manual-update").build(),
				Checkpoint.builder().state(state).nodeId(START).nextNodeId("resume").build());
	}

	private void assertMessagesAreNotAppendedAgain(boolean versioned, boolean withAsNode, boolean conditional)
			throws Exception {
		final BaseCheckpointSaver saver = versioned ? new VersionedMemoryCheckpointSaver() : new MemorySaver();
		final StateGraph workflow = new StateGraph(() -> Map.of("messages", new AppendStrategy()))
			.addNode("resume", node_async(state -> Map.of()))
			.addEdge(START, "resume");
		if (conditional) {
			workflow.addConditionalEdges("resume", edge_async(state -> "finish"), Map.of("finish", END));
		}
		else {
			workflow.addEdge("resume", END);
		}
		final CompiledGraph graph = workflow
			.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
		final RunnableConfig config = RunnableConfig.builder().threadId("issue-155").build();
		final RunnableConfig seeded = saver.put(config,
				Checkpoint.builder().state(Map.of("messages", List.of("seed"))).nodeId(START).nextNodeId("resume").build());
		assertEquals(List.of("seed"), graph.getState(seeded).state().value("messages").orElseThrow());

		final Map<String, Object> update = Map.of("human_feedback", "next");
		final RunnableConfig updated = withAsNode ? graph.updateState(seeded, update, "resume")
				: graph.updateState(seeded, update);

		if (withAsNode) {
			assertEquals(END, updated.nextNode().orElseThrow());
		}
		final OverAllState storedState = graph.getState(updated).state();
		assertEquals("next", storedState.value("human_feedback").orElseThrow());
		assertEquals(List.of("seed"), storedState.value("messages").orElseThrow(),
				"Updating only human_feedback must not append the existing messages again ("
						+ saver.getClass().getSimpleName() + ")");
		if (withAsNode) {
			assertEquals(List.of(END), graph.stream(null, updated.withResume())
				.map(NodeOutput::node)
				.collectList()
				.block(Duration.ofSeconds(5)));
		}
	}

}
