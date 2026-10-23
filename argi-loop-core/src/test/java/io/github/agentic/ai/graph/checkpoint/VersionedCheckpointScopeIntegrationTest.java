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
package io.github.agentic.ai.graph.checkpoint;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.checkpoint.savers.VersionedMemoryCheckpointSaver;
import io.github.agentic.ai.graph.internal.node.SubCompiledGraphNodeAction;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import reactor.core.publisher.Flux;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncEdgeAction.edge_async;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.outputKeyToParent;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.resumeSubGraphId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class VersionedCheckpointScopeIntegrationTest {

	@Test
	void emptyPreTurnRewindWritesEmptyStartEndCheckpointAtPositiveRevision() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("empty-rewind");

		VersionedCheckpointScope.withScope(backend, config, scope -> {
			try {
				scope.put(config, checkpoint("mutated"));
				scope.rewind(config);
				return reactor.core.publisher.Flux.empty();
			}
			catch (Exception ex) {
				return reactor.core.publisher.Flux.error(ex);
			}
		}).blockLast(Duration.ofSeconds(2));

		CheckpointSnapshot snapshot = backend.getVersioned(config);

		assertEquals(2, snapshot.revision());
		Checkpoint checkpoint = snapshot.checkpoint().orElseThrow();
		assertEquals(Map.of(), checkpoint.getState());
		assertEquals(START, checkpoint.getNodeId());
		assertEquals(END, checkpoint.getNextNodeId());
	}

	@Test
	void firstConflictTerminatesScopeAndPreventsFurtherBackendAccess() throws Exception {
		CountingVersionedCheckpointSaver countingSaver =
				new CountingVersionedCheckpointSaver(new VersionedMemoryCheckpointSaver());
		RunnableConfig config = config("terminal-conflict");
		VersionedCheckpointScope staleScope = captureScope(countingSaver, config).get();
		countingSaver.putIfVersion(config, checkpoint("winner"), 0);
		int readsAfterSetup = countingSaver.versionedReads.get();
		int putsAfterSetup = countingSaver.versionedPuts.get();
		int releasesAfterSetup = countingSaver.versionedReleases.get();

		CheckpointConflictException firstConflict = assertThrows(CheckpointConflictException.class,
				() -> staleScope.put(config, checkpoint("stale")));

		assertEquals(0, firstConflict.getExpectedRevision());
		assertEquals(readsAfterSetup, countingSaver.versionedReads.get());
		assertEquals(putsAfterSetup + 1, countingSaver.versionedPuts.get());
		assertEquals(releasesAfterSetup, countingSaver.versionedReleases.get());

		assertSameConflict(firstConflict,
				assertThrows(CheckpointConflictException.class, () -> staleScope.snapshot(config)));
		assertSameConflict(firstConflict,
				assertThrows(CheckpointConflictException.class, () -> staleScope.put(config, checkpoint("again"))));
		assertSameConflict(firstConflict, assertThrows(CheckpointConflictException.class, () -> staleScope.release(config)));
		assertSameConflict(firstConflict, assertThrows(CheckpointConflictException.class, () -> staleScope.rewind(config)));

		assertEquals(readsAfterSetup, countingSaver.versionedReads.get());
		assertEquals(putsAfterSetup + 1, countingSaver.versionedPuts.get());
		assertEquals(releasesAfterSetup, countingSaver.versionedReleases.get());
	}

	@Test
	void scopeSnapshotsAndPutInputsAreDefensiveCopies() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("scope-ownership");
		Map<String, Object> seededState = new HashMap<>();
		seededState.put("result", new ArrayList<>(List.of("seed")));
		backend.putIfVersion(config,
				Checkpoint.builder().state(seededState).nodeId("seed").nextNodeId(END).build(), 0);

		Checkpoint secondRead = VersionedCheckpointScope.withScope(backend, config, scope -> {
			Checkpoint firstRead = scope.snapshot().checkpoint().orElseThrow();
			((List<String>) firstRead.getState().get("result")).add("changed-through-snapshot");
			Checkpoint readAfterMutation = scope.snapshot().checkpoint().orElseThrow();
			Map<String, Object> putState = new HashMap<>();
			putState.put("result", new ArrayList<>(List.of("owned-put")));
			Checkpoint incoming = Checkpoint.builder().state(putState).nodeId("put").nextNodeId(END).build();
			try {
				scope.put(config, incoming);
				((List<String>) putState.get("result")).add("changed-after-put");
				return reactor.core.publisher.Flux.just(readAfterMutation);
			}
			catch (Exception ex) {
				return reactor.core.publisher.Flux.error(ex);
			}
		}).single().block(Duration.ofSeconds(2));

		Checkpoint stored = backend.getVersioned(config).checkpoint().orElseThrow();

		assertEquals(List.of("seed"), secondRead.getState().get("result"));
		assertEquals(List.of("owned-put"), stored.getState().get("result"));
		assertNotSame(seededState, stored.getState());
	}

	@Test
	void staleVersionedExecutionCannotReplaceWinningCheckpoint() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		VersionedCheckpointSaver staleFacade = new FacadeVersionedCheckpointSaver(backend);
		VersionedCheckpointSaver winnerFacade = new FacadeVersionedCheckpointSaver(backend);
		ControlledGraph stale = new ControlledGraph(staleFacade);
		ControlledGraph winner = new ControlledGraph(winnerFacade);
		RunnableConfig config = config("stale-run");

		CompletableFuture<NodeOutput> staleUpdate = stale.submit("stale", config);
		Invocation staleInvocation = stale.awaitInvocation("stale");
		CompletableFuture<NodeOutput> winningUpdate = winner.submit("winner", config);
		Invocation winnerInvocation = winner.awaitInvocation("winner");

		winnerInvocation.complete("winner");
		assertEquals("winner", result(winningUpdate).state().value("result").orElseThrow());

		staleInvocation.complete("stale");
		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> staleUpdate.get(10, TimeUnit.SECONDS));
		assertInstanceOf(CheckpointConflictException.class, rootCause(failure));
		assertEquals("winner", backend.getVersioned(config).checkpoint().orElseThrow().getState().get("result"));
	}

	@Test
	void staleScopedManualUpdateCannotReplaceWinningCheckpointOrReroute() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		VersionedCheckpointSaver staleFacade = new FacadeVersionedCheckpointSaver(backend);
		VersionedCheckpointSaver winnerFacade = new FacadeVersionedCheckpointSaver(backend);
		AtomicInteger routeCalls = new AtomicInteger();
		CompiledGraph staleGraph = routedGraph(staleFacade, routeCalls);
		CompiledGraph winnerGraph = routedGraph(winnerFacade, routeCalls);
		RunnableConfig config = config("manual-update");

		CompletableFuture<VersionedCheckpointScope> staleScope = captureScope(staleFacade, config);
		RunnableConfig winnerConfig = winnerGraph.updateState(seed(winnerFacade, config, "seed"),
				Map.of("result", "winner"), "route");

		CheckpointConflictException conflict = assertThrows(CheckpointConflictException.class,
				() -> staleGraph.updateState(winnerConfig, Map.of("result", "stale"), "route", staleScope.get()));

		assertEquals(0, conflict.getExpectedRevision());
		assertEquals("winner", backend.getVersioned(config).checkpoint().orElseThrow().getState().get("result"));
		assertEquals(1, routeCalls.get());
	}

	@Test
	void staleScopedReleaseCannotRemoveWinningCheckpoint() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		VersionedCheckpointSaver staleFacade = new FacadeVersionedCheckpointSaver(backend);
		VersionedCheckpointSaver winnerFacade = new FacadeVersionedCheckpointSaver(backend);
		RunnableConfig config = config("manual-release");

		CompletableFuture<VersionedCheckpointScope> staleScope = captureScope(staleFacade, config);
		seed(winnerFacade, config, "winner");

		CheckpointConflictException conflict = assertThrows(CheckpointConflictException.class,
				() -> staleScope.get().release(config));

		assertEquals(0, conflict.getExpectedRevision());
		assertEquals("winner", backend.getVersioned(config).checkpoint().orElseThrow().getState().get("result"));
	}

	@Test
	void coldSubscriptionsGetFreshScopesAndNamespacesStayIndependent() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		RunnableConfig firstThread = config("first-thread");
		RunnableConfig secondThread = config("second-thread");

		VersionedCheckpointScope.withScope(backend, firstThread, scope -> {
			try {
				scope.put(firstThread, checkpoint("first"));
				return reactor.core.publisher.Flux.just(scope.snapshot().revision());
			}
			catch (Exception ex) {
				return reactor.core.publisher.Flux.error(ex);
			}
		}).blockLast(Duration.ofSeconds(2));

		Long freshRevision = VersionedCheckpointScope
			.withScope(backend, firstThread, scope -> reactor.core.publisher.Flux.just(scope.snapshot().revision()))
			.blockLast(Duration.ofSeconds(2));
		Long independentRevision = VersionedCheckpointScope
			.withScope(backend, secondThread, scope -> reactor.core.publisher.Flux.just(scope.snapshot().revision()))
			.blockLast(Duration.ofSeconds(2));

		assertEquals(1, freshRevision);
		assertEquals(0, independentRevision);
	}

	@Test
	void versionedGraphCompletionReleaseReturnsTagAndLeavesPositiveTombstone() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		CompiledGraph graph = singleWriteGraph(backend, true);
		RunnableConfig config = config("release-success");

		GraphResponse<NodeOutput> completion = graph.graphResponseStream(Map.of("request", "released"), config)
			.filter(GraphResponse::isDone)
			.single()
			.block(Duration.ofSeconds(5));
		CheckpointSnapshot snapshot = backend.getVersioned(config);

		assertTrue(snapshot.checkpoint().isEmpty());
		assertEquals(3, snapshot.revision());
		BaseCheckpointSaver.Tag tag = (BaseCheckpointSaver.Tag) completion.resultValue().orElseThrow();
		assertEquals(backend.checkpointThreadId(config), tag.threadId());
		assertTrue(tag.checkpoints().stream().map(cp -> cp.getState().get("result")).toList().contains("released"));
	}

	@Test
	void versionedGraphCompletionReleaseConflictCannotRemoveWinningCheckpoint() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		VersionedCheckpointSaver graphFacade = new FacadeVersionedCheckpointSaver(backend);
		VersionedCheckpointSaver winnerFacade = new FacadeVersionedCheckpointSaver(backend);
		CompiledGraph graph = singleWriteGraph(graphFacade, true);
		RunnableConfig config = config("release-conflict");

		GraphResponse<NodeOutput> finalResponse = graph.graphResponseStream(Map.of("request", "stale"), config)
			.concatMap(response -> {
				if (!response.isDone() && !response.isError()) {
					try {
						response.getOutput().get(5, TimeUnit.SECONDS);
						winnerFacade.putIfVersion(config, checkpoint("winner"), backend.getVersioned(config).revision());
					}
					catch (Exception ex) {
						return reactor.core.publisher.Flux.error(ex);
					}
				}
				return reactor.core.publisher.Flux.just(response);
			})
			.last()
			.block(Duration.ofSeconds(5));

		assertTrue(finalResponse.isError());
		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> finalResponse.getOutput().get(5, TimeUnit.SECONDS));
		assertInstanceOf(CheckpointConflictException.class, rootCause(failure));
		assertEquals("winner", backend.getVersioned(config).checkpoint().orElseThrow().getState().get("result"));
	}

	@Test
	void versionedGraphInitializationUsesOneSnapshotRead() throws Exception {
		CountingVersionedCheckpointSaver saver = new CountingVersionedCheckpointSaver(new VersionedMemoryCheckpointSaver());
		CompiledGraph graph = singleWriteGraph(saver, false);
		RunnableConfig config = config("single-read");

		assertEquals("once", graph.stream(Map.of("request", "once"), config)
			.last()
			.block(Duration.ofSeconds(5))
			.state()
			.value("result")
			.orElseThrow());

		assertEquals(1, saver.versionedReads.get());
	}

	@Test
	void pinnedHistoryIsPreservedInsideInheritedScope() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("pinned-history");
		RunnableConfig firstConfig = backend.putIfVersion(config, checkpoint("first"), 0);
		backend.putIfVersion(config, checkpoint("second"), 1);
		RunnableConfig pinnedFirst = RunnableConfig.builder(config)
			.checkPointId(firstConfig.checkPointId().orElseThrow())
			.build();

		CheckpointSnapshot selected = VersionedCheckpointScope
			.withScope(backend, config,
					scope -> VersionedCheckpointScope.withScope(backend, pinnedFirst, inheritedScope -> {
						try {
							return reactor.core.publisher.Flux.just(inheritedScope.snapshot(pinnedFirst));
						}
						catch (Exception ex) {
							return reactor.core.publisher.Flux.error(ex);
						}
					}))
			.single()
			.block(Duration.ofSeconds(2));

		assertEquals(2, selected.revision());
		assertEquals("first", selected.checkpoint().orElseThrow().getState().get("result"));
		assertEquals("second", backend.getVersioned(config).checkpoint().orElseThrow().getState().get("result"));
	}

	@Test
	void versionedStartAndResumeMergeInputsWithCheckpointState() throws Exception {
		VersionedMemoryCheckpointSaver backend = new VersionedMemoryCheckpointSaver();
		CompiledGraph graph = mergeInputGraph(backend);
		RunnableConfig startConfig = config("start-merge");
		backend.putIfVersion(startConfig,
				Checkpoint.builder().state(Map.of("persisted", "seed")).nodeId(START).nextNodeId("merge").build(), 0);

		NodeOutput startOutput = graph.stream(Map.of("request", "start"), startConfig)
			.last()
			.block(Duration.ofSeconds(5));

		assertEquals("seed:start", startOutput.state().value("result").orElseThrow());

		RunnableConfig resumeConfig = config("resume-merge");
		RunnableConfig checkpointConfig = backend.putIfVersion(resumeConfig,
				Checkpoint.builder().state(Map.of("persisted", "seed", "request", "old"))
					.nodeId(START)
					.nextNodeId("merge")
					.build(),
				0);

		NodeOutput resumeOutput = graph.stream(Map.of("request", "resume"),
				RunnableConfig.builder(resumeConfig).checkPointId(checkpointConfig.checkPointId().orElseThrow()).build())
			.last()
			.block(Duration.ofSeconds(5));

		assertEquals("seed:resume", resumeOutput.state().value("result").orElseThrow());
	}

	@Test
	@SuppressWarnings("unchecked")
	void versionedSubgraphResumeSharesAdvancedScopeWithChildInitialization() throws Exception {
		CountingVersionedCheckpointSaver saver = new CountingVersionedCheckpointSaver(new VersionedMemoryCheckpointSaver());
		RunnableConfig childConfig = config("shared_subgraph_nested");
		saver.putIfVersion(childConfig, Checkpoint.builder().nodeId(START).nextNodeId("append")
			.state(Map.of("messages", List.of("seed"))).build(), 0);
		CompiledGraph child = childGraph(saver);
		SubCompiledGraphNodeAction action = new SubCompiledGraphNodeAction("nested", compileConfig(saver), child);
		RunnableConfig parentConfig = RunnableConfig.builder(config("shared"))
			.addMetadata(resumeSubGraphId("nested"), true)
			.build();
		OverAllState parentState = new OverAllState(Map.of("messages", List.of("parent")));

		Flux<GraphResponse<NodeOutput>> childStream = (Flux<GraphResponse<NodeOutput>>) action.apply(parentState,
				parentConfig).get(5, TimeUnit.SECONDS).get(outputKeyToParent("nested"));
		childStream.collectList().block(Duration.ofSeconds(5));
		int readsBeforeVerification = saver.versionedReads.get();

		CheckpointSnapshot childSnapshot = saver.getVersioned(childConfig);

		assertEquals(1, readsBeforeVerification);
		assertEquals(3, childSnapshot.revision());
		assertEquals(List.of("child"),
				childSnapshot.checkpoint().orElseThrow().getState().get("messages"));
	}

	@Test
	void versionedRuntimeUsesConfiguredSerializerBeforeMutatingReducersCanAliasState() throws Exception {
		CountingStateSerializer serializer = new CountingStateSerializer();
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver(serializer);
		RunnableConfig config = config("custom-serializer-mutating-reducer");
		Map<String, Object> seed = new HashMap<>();
		seed.put("messages", new ArrayList<>(List.of("seed")));
		saver.putIfVersion(config, Checkpoint.builder().state(seed).nodeId(START).nextNodeId("append").build(), 0);
		CompiledGraph graph = new StateGraph(VersionedCheckpointScopeIntegrationTest::mutatingStrategies, serializer)
			.addNode("append", (state, runnableConfig) -> CompletableFuture
				.completedFuture(Map.of("messages", new ArrayList<>(List.of("node")))))
			.addEdge(START, "append")
			.addEdge("append", END)
			.compile(compileConfig(saver));

		graph.stream(Map.of("messages", new ArrayList<>(List.of("input"))), config)
			.last()
			.block(Duration.ofSeconds(5));
		((List<String>) seed.get("messages")).add("changed-after-run");

		assertTrue(serializer.writeCount > 0);
		assertTrue(serializer.readCount > 0);
		assertEquals(List.of("node"), saver.getVersioned(config).checkpoint().orElseThrow().getState().get("messages"));
	}

	@Test
	void legacySaverStillAllowsLastWriterToWin() throws Exception {
		MemorySaver backend = new MemorySaver();
		ControlledGraph stale = new ControlledGraph(new FacadeCheckpointSaver(backend));
		ControlledGraph winner = new ControlledGraph(new FacadeCheckpointSaver(backend));
		RunnableConfig config = config("legacy-run");

		CompletableFuture<NodeOutput> staleUpdate = stale.submit("stale", config);
		Invocation staleInvocation = stale.awaitInvocation("stale");
		CompletableFuture<NodeOutput> winningUpdate = winner.submit("winner", config);
		Invocation winnerInvocation = winner.awaitInvocation("winner");

		winnerInvocation.complete("winner");
		assertEquals("winner", result(winningUpdate).state().value("result").orElseThrow());
		staleInvocation.complete("stale");

		assertEquals("stale", result(staleUpdate).state().value("result").orElseThrow());
		assertEquals("stale", backend.get(config).orElseThrow().getState().get("result"));
	}

	private static CompletableFuture<VersionedCheckpointScope> captureScope(VersionedCheckpointSaver saver,
			RunnableConfig config) {
		return VersionedCheckpointScope
			.withScope(saver, config, scope -> reactor.core.publisher.Flux.just(scope))
			.single()
			.toFuture();
	}

	private static RunnableConfig seed(VersionedCheckpointSaver saver, RunnableConfig config, String result)
			throws Exception {
		return saver.putIfVersion(config, checkpoint(result), saver.getVersioned(config).revision());
	}

	private static Checkpoint checkpoint(String result) {
		return Checkpoint.builder().state(Map.of("result", result)).nodeId("route").nextNodeId(END).build();
	}

	private static CompiledGraph routedGraph(VersionedCheckpointSaver saver, AtomicInteger routeCalls) throws Exception {
		return new StateGraph(() -> Map.<String, KeyStrategy>of("result", new ReplaceStrategy()))
			.addNode("route", AsyncNodeActionWithConfig.node_async((state, config) -> Map.of()))
			.addConditionalEdges("route", edge_async(state -> {
				routeCalls.incrementAndGet();
				return END;
			}), Map.of(END, END))
			.addEdge(START, "route")
			.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
	}

	private static CompiledGraph mergeInputGraph(BaseCheckpointSaver saver) throws Exception {
		return new StateGraph(() -> Map.<String, KeyStrategy>of("persisted", new ReplaceStrategy(), "request",
				new ReplaceStrategy(), "result", new ReplaceStrategy()))
			.addNode("merge", (state, config) -> CompletableFuture.completedFuture(Map.of("result",
					"%s:%s".formatted(state.value("persisted").orElseThrow(), state.value("request").orElseThrow()))))
			.addEdge(START, "merge")
			.addEdge("merge", END)
			.compile(compileConfig(saver));
	}

	private static CompiledGraph childGraph(BaseCheckpointSaver saver) throws Exception {
		return new StateGraph(() -> Map.<String, KeyStrategy>of("messages", new AppendStrategy(false)))
			.addNode("append", (state, config) -> CompletableFuture.completedFuture(Map.of("messages", List.of("child"))))
			.addEdge(START, "append")
			.addEdge("append", END)
			.compile(compileConfig(saver));
	}

	private static CompiledGraph singleWriteGraph(BaseCheckpointSaver saver, boolean releaseThread) throws Exception {
		return new StateGraph(() -> Map.<String, KeyStrategy>of("result", new ReplaceStrategy()))
			.addNode("write", (state, config) -> CompletableFuture
				.completedFuture(Map.of("result", state.value("request", String.class).orElseThrow())))
			.addEdge(START, "write")
			.addEdge("write", END)
			.compile(CompileConfig.builder()
				.releaseThread(releaseThread)
				.saverConfig(SaverConfig.builder().register(saver).build())
				.build());
	}

	private static CompileConfig compileConfig(BaseCheckpointSaver saver) {
		return CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build();
	}

	private static Map<String, KeyStrategy> mutatingStrategies() {
		return Map.of("messages", (oldValue, newValue) -> {
			ArrayList<Object> result = oldValue instanceof List<?> oldList ? new ArrayList<>(oldList) : new ArrayList<>();
			if (newValue instanceof List<?> newList) {
				result.addAll(newList);
			}
			else {
				result.add(newValue);
			}
			return result;
		});
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	private static NodeOutput result(CompletableFuture<NodeOutput> future) throws Exception {
		return future.get(10, TimeUnit.SECONDS);
	}

	private static Throwable rootCause(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	private static void assertSameConflict(CheckpointConflictException expected,
			CheckpointConflictException actual) {
		assertEquals(expected.getNamespace(), actual.getNamespace());
		assertEquals(expected.getExpectedRevision(), actual.getExpectedRevision());
		assertEquals(expected.getActualRevision(), actual.getActualRevision());
	}

	private record Invocation(String request, CompletableFuture<Map<String, Object>> completion) {

		private void complete(String result) {
			assertTrue(completion.complete(Map.of("result", result)));
		}

	}

	private static final class ControlledGraph {

		private final CompiledGraph graph;

		private final BlockingQueue<Invocation> pending = new LinkedBlockingQueue<>();

		private ControlledGraph(BaseCheckpointSaver saver) throws Exception {
			this.graph = new StateGraph(() -> Map.<String, KeyStrategy>of("result", new ReplaceStrategy()))
				.addNode("write", (state, config) -> {
					String request = state.value("request", String.class).orElseThrow();
					CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>() {
						@Override
						public boolean cancel(boolean mayInterruptIfRunning) {
							return false;
						}
					};
					pending.add(new Invocation(request, completion));
					return completion;
				})
				.addEdge(START, "write")
				.addEdge("write", END)
				.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
		}

		private CompletableFuture<NodeOutput> submit(String request, RunnableConfig config) {
			return graph.stream(Map.of("request", request), config).last().toFuture();
		}

		private Invocation awaitInvocation(String request) throws InterruptedException {
			Invocation invocation = pending.poll(10, TimeUnit.SECONDS);
			assertNotNull(invocation);
			assertEquals(request, invocation.request());
			return invocation;
		}

	}

	private record FacadeVersionedCheckpointSaver(VersionedCheckpointSaver delegate) implements VersionedCheckpointSaver {

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			return delegate.get(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			return delegate.put(config, checkpoint);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return delegate.release(config);
		}

		@Override
		public CheckpointSnapshot getVersioned(RunnableConfig config) {
			return delegate.getVersioned(config);
		}

		@Override
		public RunnableConfig putIfVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision)
				throws Exception {
			return delegate.putIfVersion(config, checkpoint, expectedRevision);
		}

		@Override
		public BaseCheckpointSaver.Tag releaseIfVersion(RunnableConfig config, long expectedRevision) throws Exception {
			return delegate.releaseIfVersion(config, expectedRevision);
		}

	}

	private record FacadeCheckpointSaver(BaseCheckpointSaver delegate) implements BaseCheckpointSaver {

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			return delegate.get(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			return delegate.put(config, checkpoint);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return delegate.release(config);
		}

	}

	private static final class CountingVersionedCheckpointSaver implements VersionedCheckpointSaver {

		private final VersionedCheckpointSaver delegate;

		private final AtomicInteger versionedReads = new AtomicInteger();

		private final AtomicInteger versionedPuts = new AtomicInteger();

		private final AtomicInteger versionedReleases = new AtomicInteger();

		private CountingVersionedCheckpointSaver(VersionedCheckpointSaver delegate) {
			this.delegate = delegate;
		}

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			return delegate.get(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			return delegate.put(config, checkpoint);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return delegate.release(config);
		}

		@Override
		public CheckpointSnapshot getVersioned(RunnableConfig config) {
			versionedReads.incrementAndGet();
			return delegate.getVersioned(config);
		}

		@Override
		public RunnableConfig putIfVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision)
				throws Exception {
			versionedPuts.incrementAndGet();
			return delegate.putIfVersion(config, checkpoint, expectedRevision);
		}

		@Override
		public BaseCheckpointSaver.Tag releaseIfVersion(RunnableConfig config, long expectedRevision) throws Exception {
			versionedReleases.incrementAndGet();
			return delegate.releaseIfVersion(config, expectedRevision);
		}

	}

	private static final class CountingStateSerializer extends StateSerializer {

		private int writeCount;

		private int readCount;

		private CountingStateSerializer() {
			super(StateGraph.DEFAULT_JACKSON_SERIALIZER.stateFactory());
		}

		@Override
		public void writeData(Map<String, Object> data, java.io.ObjectOutput out) throws IOException {
			writeCount++;
			StateGraph.DEFAULT_JACKSON_SERIALIZER.writeData(data, out);
		}

		@Override
		public Map<String, Object> readData(java.io.ObjectInput in) throws IOException, ClassNotFoundException {
			readCount++;
			return StateGraph.DEFAULT_JACKSON_SERIALIZER.readData(in);
		}

	}

}
