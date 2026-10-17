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
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.run.MemoryRunStore;
import io.github.agentic.ai.graph.execution.run.RunCommit;
import io.github.agentic.ai.graph.execution.run.RunLease;
import io.github.agentic.ai.graph.execution.run.RunNamespace;
import io.github.agentic.ai.graph.execution.run.RunSnapshot;
import io.github.agentic.ai.graph.execution.run.RunState;
import io.github.agentic.ai.graph.execution.run.RunStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.OPTIONS;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.RECORD;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.context;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.definition;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.graph;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.runner;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.spec;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.registry;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.WORKER;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class DurableRunLifecycleTest {
	private final DurableTestSupport.MutableClock clock = new DurableTestSupport.MutableClock();
	private final MemoryRunStore store = new MemoryRunStore(clock);

	@Test
	void cancellationDuringAcquisitionCleansDiscardedResourceAndLocalRegistration() throws Exception {
		var context = context();
		var spec = spec(context);
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch cleaned = new CountDownLatch(1);
		CompletableFuture<Void> acknowledge = new CompletableFuture<>();
		RunStore backend = mock(RunStore.class, delegatesTo(store));
		doAnswer(invocation -> {
			RunLease lease = store.acquire(invocation.getArgument(0), invocation.getArgument(1),
					invocation.getArgument(2), invocation.getArgument(3));
			acquired.countDown();
			acknowledge.join();
			return lease;
		}).when(backend).acquire(any(), anyString(), any(), any());
		DurableGraphDefinition definition = definition((definitions, saver) -> {
			throw new AssertionError("Cancelled acquisition must never start graph work");
		});
		DurableRunRunner runner = new DurableRunRunner(backend, registry(definition), WORKER, OPTIONS,
				event -> {
					if (event.kind() == RunExecutionEvent.Kind.FINISHED) {
						cleaned.countDown();
					}
				}, clock);
		runner.submit(spec, RECORD, context);
		var subscription = runner.run(spec.namespace(), spec.runId(), context).subscribe();
		try {
			assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
			subscription.dispose();
		}
		finally {
			acknowledge.complete(null);
		}
		assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(runner.query(spec.namespace(), spec.runId()).orElseThrow().state()).isEqualTo(RunState.CANCELLED);
		assertThat(store.activeRun(spec.namespace())).isEmpty();
	}

	@Test
	void deadlineCancelsNeverCompletingNodeAndPublishesTimeout() {
		TrustedExecutionContext context = context(clock.instant().plusMillis(200));
		var spec = spec(context);
		CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> pending, (state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		RunSnapshot result = runner.run(spec.namespace(), spec.runId(), context).block(Duration.ofSeconds(5));
		assertThat(result.state()).isEqualTo(RunState.TIMED_OUT);
		assertThat(pending.isCancelled()).isTrue();
	}

	@Test
	void deadlineBeforeCheckpointDoesNotBecomeLeaseLossOrPublishLateState() {
		TrustedExecutionContext context = context(clock.instant().plusMillis(500));
		var spec = spec(context);
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					clock.advance(Duration.ofSeconds(1));
					return completedFuture(Map.of("late", true));
				}, (state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		RunSnapshot result = runner.run(spec.namespace(), spec.runId(), context).block(Duration.ofSeconds(5));
		assertThat(result.state()).isEqualTo(RunState.TIMED_OUT);
		assertThat(new JsonDurableStateCodec().decodeCheckpoint(result.checkpoint()).getState()).doesNotContainKey("late");
	}

	@Test
	void nodeFailureAfterDeadlineCommitsTimeoutWithOriginalFailure() {
		TrustedExecutionContext context = context(clock.instant().plusMillis(500));
		var spec = spec(context);
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					clock.advance(Duration.ofSeconds(1));
					return CompletableFuture.failedFuture(new IllegalStateException("late failure"));
				}, (state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		assertThatThrownBy(() -> runner.run(spec.namespace(), spec.runId(), context).block())
			.isInstanceOf(IllegalStateException.class).hasMessage("late failure");
		assertThat(runner.query(spec.namespace(), spec.runId()).orElseThrow().state()).isEqualTo(RunState.TIMED_OUT);
	}

	@Test
	void recoveryAtEndFinalizesWithoutReplayingCompletedNodes() {
		var context = context();
		var spec = spec(context);
		AtomicInteger calls = new AtomicInteger();
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					calls.incrementAndGet();
					return completedFuture(Map.of());
				}, (state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		RunLease lease = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl());
		Checkpoint checkpoint = Checkpoint.builder().nodeId("second").nextNodeId(StateGraph.END)
			.state(Map.of("value", 99)).build();
		store.commit(spec.namespace(), spec.runId(), lease, new RunCommit(0, RunState.RUNNING,
				new JsonDurableStateCodec().encodeCheckpoint(checkpoint), List.of(), List.of(), List.of()));
		store.release(lease);
		RunSnapshot result = runner.run(spec.namespace(), spec.runId(), context).block();
		assertThat(result.state()).isEqualTo(RunState.SUCCEEDED);
		assertThat(calls.get()).isZero();
		assertThat(new JsonDurableStateCodec().decodeCheckpoint(result.checkpoint()).getState()).containsEntry("value", 99);
	}

	@Test
	void duplicateSubscriptionCannotReleaseActiveOwner() throws Exception {
		var context = context();
		var spec = spec(context);
		CountDownLatch entered = new CountDownLatch(1);
		CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> graph(saver,
				(state, config) -> {
					entered.countDown();
					return pending;
				}, (state, config) -> completedFuture(Map.of()))));
		runner.submit(spec, RECORD, context);
		RunLease lease = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl());
		CompletableFuture<RunSnapshot> running = runner.runOwned(spec.namespace(), spec.runId(), lease, context).toFuture();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		assertThatThrownBy(() -> runner.runOwned(spec.namespace(), spec.runId(), lease, context).block())
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("already executing");
		pending.complete(Map.of());
		assertThat(running.get(5, TimeUnit.SECONDS).state()).isEqualTo(RunState.SUCCEEDED);
	}

	@Test
	void rejectsWrongNamespaceAndAutomaticCheckpointRelease() {
		var context = context();
		var spec = spec(context);
		DurableRunRunner runner = runner(store, clock, definition((definitions, saver) -> new StateGraph()
			.addNode("work", (state, config) -> completedFuture(Map.of()))
			.addEdge(StateGraph.START, "work").addEdge("work", StateGraph.END)
			.compile(CompileConfig.builder().saverConfig(new SaverConfig().register(saver)).releaseThread(true).build())));
		runner.submit(spec, RECORD, context);
		RunLease wrong = new RunLease(new RunNamespace(context.tenant(), "other", "session"),
				UUID.randomUUID(), 1, clock.instant().plusSeconds(2));
		assertThatThrownBy(() -> runner.runOwned(spec.namespace(), spec.runId(), wrong, context))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> runner.run(spec.namespace(), spec.runId(), context).block())
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retain");
		assertThat(store.release(store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl()))).isTrue();
	}
}
