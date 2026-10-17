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
package io.github.agentic.ai.graph.execution.durable;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionReference;
import io.github.agentic.ai.graph.execution.definition.DefinitionRegistry;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentDeclaration;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentRole;
import io.github.agentic.ai.graph.execution.run.MemoryRunStore;
import io.github.agentic.ai.graph.execution.run.RunLease;
import io.github.agentic.ai.graph.execution.run.RunSnapshot;
import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunState;
import io.github.agentic.ai.graph.execution.run.RunStoreConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.graph.execution.durable.DurableTestSupport.MutableClock;

import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.APP;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.CODEC;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.GRAPH;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.OPTIONS;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.RECORD;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.TOOLS;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.WORKER;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.components;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.context;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.definition;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.graph;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.registry;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.runner;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.spec;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableRunRunnerTest {
	private final MutableClock clock = new MutableClock();
	private final MemoryRunStore store = new MemoryRunStore(clock);
	private final TrustedExecutionContext context = context();
	private final RunSpec spec = spec(context);

	@Test
	void completesGraphWithAtomicProgressEventsAndTerminalQueries() {
		List<RunExecutionEvent> events = new CopyOnWriteArrayList<>();
		DurableGraphDefinition definition = definition((definitions, saver) -> graph(saver,
				(state, config) -> completedFuture(Map.of("value", 2)),
				(state, config) -> completedFuture(Map.of("value", 3))));
		DurableRunRunner runner = new DurableRunRunner(store, registry(definition), WORKER, OPTIONS, events::add, clock);
		assertThat(runner.submit(spec, RECORD, context).state()).isEqualTo(RunState.PENDING);
		assertThat(runner.run(spec.namespace(), spec.runId(), context).block(Duration.ofSeconds(5)).state())
			.isEqualTo(RunState.SUCCEEDED);
		RunSnapshot result = runner.query(spec.namespace(), spec.runId()).orElseThrow();
		assertThat(new JsonDurableStateCodec().decodeCheckpoint(result.checkpoint()).getState()).containsEntry("value", 3);
		assertThat(result.outbox()).hasSize((int) result.revision());
		assertThat(store.activeRun(spec.namespace())).isEmpty();
		assertThat(events).extracting(RunExecutionEvent::kind).contains(RunExecutionEvent.Kind.STARTED,
				RunExecutionEvent.Kind.CHECKPOINT_COMMITTED, RunExecutionEvent.Kind.FINISHED);
		assertThat(runner.run(spec.namespace(), spec.runId(), context).block()).isEqualTo(result);
		assertThat(runner.cancel(spec.namespace(), spec.runId())).isFalse();
	}

	@Test
	void usesBorrowedWorkerOwnerWithoutSecondAcquire() {
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					assertThatThrownBy(() -> store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl()))
						.isInstanceOf(RunStoreConflictException.class);
					config.assertExecutionActive();
					return completedFuture(Map.of());
				}, (state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		RunLease owned = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl());
		assertThat(runner.runOwned(spec.namespace(), spec.runId(), owned, context).block().state())
			.isEqualTo(RunState.SUCCEEDED);
		assertThat(store.release(owned)).isFalse();
	}

	@Test
	void leaseLossCancelsInFlightWorkPreventsNextNodeAndPreservesNewOwner() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		AtomicInteger secondCalls = new AtomicInteger();
		CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					config.executionGuard().orElseThrow().onLoss(cancelled::countDown);
					entered.countDown();
					return pending;
				}, (state, config) -> {
					secondCalls.incrementAndGet();
					return completedFuture(Map.of());
				})));
		runner.submit(spec, RECORD, context);
		CompletableFuture<RunSnapshot> result = runner.run(spec.namespace(), spec.runId(), context).toFuture();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		RunSnapshot before = store.find(spec.namespace(), spec.runId()).orElseThrow();
		clock.advance(Duration.ofSeconds(3));
		RunLease replacement = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl());
		assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(LeaseLostException.class);
		pending.complete(Map.of("late", true));
		assertThat(secondCalls.get()).isZero();
		assertThat(store.find(spec.namespace(), spec.runId()).orElseThrow()).isEqualTo(before);
		assertThat(store.renew(replacement, OPTIONS.ttl()).ownerId()).isEqualTo(replacement.ownerId());
	}

	@Test
	void lateCommitIsFencedEvenBeforeHeartbeatDetectsLoss() {
		AtomicInteger secondCalls = new AtomicInteger();
		AtomicReference<RunLease> replacement = new AtomicReference<>();
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					clock.advance(Duration.ofSeconds(3));
					replacement.set(store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl()));
					return completedFuture(Map.of("late", true));
				}, (state, config) -> {
					secondCalls.incrementAndGet();
					return completedFuture(Map.of());
				})));
		runner.submit(spec, RECORD, context);
		assertThatThrownBy(() -> runner.run(spec.namespace(), spec.runId(), context).block())
			.isInstanceOf(LeaseLostException.class);
		assertThat(secondCalls.get()).isZero();
		RunSnapshot snapshot = store.find(spec.namespace(), spec.runId()).orElseThrow();
		assertThat(new JsonDurableStateCodec().decodeCheckpoint(snapshot.checkpoint()).getState()).doesNotContainKey("late");
		assertThat(store.renew(replacement.get(), OPTIONS.ttl())).isNotNull();
	}

	@Test
	void resumesOnlyUncommittedNodeAfterLeaseLoss() {
		AtomicInteger firstCalls = new AtomicInteger();
		AtomicInteger secondCalls = new AtomicInteger();
		DurableGraphDefinition definition = definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					firstCalls.incrementAndGet();
					return completedFuture(Map.of("value", 2));
				}, (state, config) -> {
					if (secondCalls.incrementAndGet() == 1) {
						clock.advance(Duration.ofSeconds(3));
					}
					return completedFuture(Map.of("value", 3));
				}));
		DurableRunRunner first = runner(store, clock, definition);
		first.submit(spec, RECORD, context);
		assertThatThrownBy(() -> first.run(spec.namespace(), spec.runId(), context).block())
			.isInstanceOf(LeaseLostException.class);
		DurableRunRunner recovered = runner(store, clock, definition);
		assertThat(recovered.run(spec.namespace(), spec.runId(), context).block().state()).isEqualTo(RunState.SUCCEEDED);
		assertThat(firstCalls.get()).isEqualTo(1);
		assertThat(secondCalls.get()).isEqualTo(2);
	}

	@Test
	void nodeFailureCommitsFailedInsteadOfTreatingResponseCompletionAsSuccess() {
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> CompletableFuture.failedFuture(new IllegalStateException("node failed")),
				(state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		assertThatThrownBy(() -> runner.run(spec.namespace(), spec.runId(), context).block())
			.isInstanceOf(IllegalStateException.class).hasMessage("node failed");
		assertThat(runner.query(spec.namespace(), spec.runId()).orElseThrow().state()).isEqualTo(RunState.FAILED);
		assertThat(store.activeRun(spec.namespace())).isEmpty();
	}

	@Test
	void localCancellationCommitsCancelledAndReleasesResources() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					entered.countDown();
					return pending;
				}, (state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		CompletableFuture<RunSnapshot> result = runner.run(spec.namespace(), spec.runId(), context).toFuture();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(runner.cancel(spec.namespace(), spec.runId())).isTrue();
		assertThat(result.get(5, TimeUnit.SECONDS).state()).isEqualTo(RunState.CANCELLED);
		assertThat(pending.isCancelled()).isTrue();
		assertThat(store.activeRun(spec.namespace())).isEmpty();
	}

	@Test
	void subscriberCancellationDoesNotRewindCommittedProgress() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		DurableGraphDefinition definition = definition((definitions, saver) -> graph(saver,
				(state, config) -> completedFuture(Map.of("value", 2)), (state, config) -> {
					entered.countDown();
					return new CompletableFuture<>();
				}));
		DurableRunRunner runner = new DurableRunRunner(store, registry(definition), WORKER, OPTIONS,
				event -> {
					if (event.kind() == RunExecutionEvent.Kind.FINISHED) {
						finished.countDown();
					}
				}, clock);
		runner.submit(spec, RECORD, context);
		var subscription = runner.run(spec.namespace(), spec.runId(), context).subscribe();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		subscription.dispose();
		assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
		RunSnapshot result = runner.query(spec.namespace(), spec.runId()).orElseThrow();
		assertThat(result.state()).isEqualTo(RunState.CANCELLED);
		assertThat(new JsonDurableStateCodec().decodeCheckpoint(result.checkpoint()).getNodeId()).isEqualTo("first");
	}

	@Test
	void expiredRunTimesOutWithoutStartingNodes() {
		TrustedExecutionContext expiring = context(clock.instant().plusSeconds(1));
		RunSpec expiringSpec = spec(expiring);
		AtomicInteger calls = new AtomicInteger();
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> {
			calls.incrementAndGet();
			return graph(saver, (state, config) -> completedFuture(Map.of()), (state, config) -> completedFuture(Map.of()));
		}));
		runner.submit(expiringSpec, RECORD, expiring);
		clock.advance(Duration.ofSeconds(2));
		assertThat(runner.run(expiringSpec.namespace(), expiringSpec.runId(), expiring).block().state())
			.isEqualTo(RunState.TIMED_OUT);
		assertThat(calls.get()).isZero();
	}

	@Test
	void waitingRunKeepsReservationAndResumesAtItsBoundary() {
		AtomicInteger calls = new AtomicInteger();
		DurableGraphDefinition definition = definition((definitions, saver) -> new StateGraph()
			.addNode("work", (state, config) -> {
				calls.incrementAndGet();
				return completedFuture(Map.of("value", 2));
			}).addEdge(StateGraph.START, "work").addEdge("work", StateGraph.END)
			.compile(CompileConfig.builder().saverConfig(new SaverConfig().register(saver)).interruptBefore("work").build()));
		DurableRunRunner runner = runner(store, clock, definition);
		runner.submit(spec, RECORD, context);
		assertThat(runner.run(spec.namespace(), spec.runId(), context).block().state()).isEqualTo(RunState.WAITING);
		assertThat(calls.get()).isZero();
		assertThat(store.activeRun(spec.namespace())).contains(spec.runId());
		assertThat(runner.run(spec.namespace(), spec.runId(), context).block().state()).isEqualTo(RunState.SUCCEEDED);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void rejectsUnsafeAndInstanceBoundDefinitionsBeforeSubmission() {
		DurableGraphDefinition.Factory factory = (definitions, saver) -> { throw new AssertionError("Must not compile"); };
		DurableRunRunner unsafe = runner(store, clock, new DurableGraphDefinition(factory, ReplaySafety.UNSAFE, components()));
		assertThatThrownBy(() -> unsafe.submit(spec, RECORD, context)).isInstanceOf(IllegalArgumentException.class);
		List<RuntimeComponentDeclaration> declarations = new ArrayList<>(components());
		declarations.set(0, RuntimeComponentDeclaration.instanceBound(RuntimeComponentRole.CUSTOM_NODE, "callback"));
		DurableRunRunner instanceBound = runner(store, clock,
				new DurableGraphDefinition(factory, ReplaySafety.READ_ONLY, declarations));
		assertThatThrownBy(() -> instanceBound.submit(spec, RECORD, context)).isInstanceOf(IllegalStateException.class);
		assertThat(store.find(spec.namespace(), spec.runId())).isEmpty();
	}

	@Test
	void productionEntryRejectsMemoryStoreAndUnknownOrMismatchedRuns() {
		DurableGraphDefinition definition = definition((definitions, saver) -> graph(saver,
				(state, config) -> completedFuture(Map.of()), (state, config) -> completedFuture(Map.of())));
		assertThatThrownBy(() -> new DurableRunRunner(store, registry(definition), WORKER, OPTIONS, event -> { }))
			.isInstanceOf(IllegalStateException.class);
		DurableRunRunner runner = runner(store, clock, definition);
		assertThatThrownBy(() -> runner.run(spec.namespace(), "absent", context).block())
			.isInstanceOf(IllegalArgumentException.class);
		runner.submit(spec, RECORD, context);
		assertThatThrownBy(() -> runner.run(spec.namespace(), spec.runId(), context(clock.instant().plusSeconds(1))).block())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void changedSubmissionVersionConflictsAndMissingVersionFailsBeforeFactory() {
		AtomicInteger factories = new AtomicInteger();
		DurableGraphDefinition definition = definition((definitions, saver) -> {
			factories.incrementAndGet();
			return graph(saver, (state, config) -> completedFuture(Map.of()), (state, config) -> completedFuture(Map.of()));
		});
		DurableRunRunner runner = runner(store, clock, definition);
		runner.submit(spec, RECORD, context);
		DefinitionReference tools2 = DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "tools", "2");
		DefinitionRegistry upgraded = DefinitionRegistry.builder().register(GRAPH, definition).register(TOOLS, "one")
			.register(tools2, "two").register(CODEC, new JsonDurableStateCodec()).register(APP, "app").build();
		DurableRunRunner other = new DurableRunRunner(store, upgraded, WORKER, OPTIONS, event -> { }, clock);
		assertThatThrownBy(() -> other.submit(spec, RunDefinitionRecord.of(GRAPH, tools2, CODEC, APP), context))
			.isInstanceOf(RunStoreConflictException.class);
		DurableRunRunner missing = new DurableRunRunner(store, DefinitionRegistry.builder().build(),
				WORKER, OPTIONS, event -> { }, clock);
		assertThatThrownBy(() -> missing.run(spec.namespace(), spec.runId(), context).block())
			.isInstanceOf(IllegalStateException.class);
		assertThat(factories.get()).isZero();
		assertThat(store.release(store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl()))).isTrue();
	}

	@Test
	void rejectsIndependentCheckpointSaverAndReleasesLeaseAfterFactoryFailure() {
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> new StateGraph()
			.addNode("work", (state, config) -> completedFuture(Map.of()))
			.addEdge(StateGraph.START, "work").addEdge("work", StateGraph.END).compile()));
		runner.submit(spec, RECORD, context);
		assertThatThrownBy(() -> runner.run(spec.namespace(), spec.runId(), context).block())
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supplied");
		assertThat(store.release(store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl()))).isTrue();
	}

	@Test
	void observerFailuresCannotChangeExecutionOrAtomicCommitOutcome() {
		DurableGraphDefinition definition = definition((definitions, saver) -> graph(saver,
				(state, config) -> completedFuture(Map.of()), (state, config) -> completedFuture(Map.of())));
		DurableRunRunner runner = new DurableRunRunner(store, registry(definition), WORKER, OPTIONS,
				event -> { throw new IllegalStateException("observer failed"); }, clock);
		runner.submit(spec, RECORD, context);
		assertThat(runner.run(spec.namespace(), spec.runId(), context).block().state()).isEqualTo(RunState.SUCCEEDED);
	}
}
