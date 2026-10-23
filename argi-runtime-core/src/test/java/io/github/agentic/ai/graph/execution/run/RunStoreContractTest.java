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

package io.github.agentic.ai.graph.execution.run;

import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable semantic contract for RunStore adapters. Backend suites should extend this
 * fixture and additionally verify restart persistence, real transactions and independent
 * JVM owner takeover. The clock must be the backend's authoritative test clock.
 */
public abstract class RunStoreContractTest {

	protected final MutableClock clock = new MutableClock();

	protected RunStore store;

	protected abstract RunStore newStore(Clock clock);

	@BeforeEach
	void setUp() {
		store = newStore(clock);
	}

	@Test
	void deduplicatesCanonicalInputEvenWithAnotherRunIdAndRetainsOriginalConstraints() {
		RunSpec first = spec("one", "key", "{\"b\":2,\"a\":1}");
		RunSnapshot admitted = submit(first);
		RunSpec retry = spec("two", "key", "{\"a\":1,\"b\":2}");
		assertThat(submit(retry)).isEqualTo(admitted);
		assertThat(store.find(first.namespace(), "two")).isEmpty();
		assertThat(store.activeRun(first.namespace())).contains("one");
		assertThatThrownBy(() -> submit(spec("three", "key", "{\"a\":2}")))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(store.find(first.namespace(), "three")).isEmpty();
	}

	@Test
	void rejectsChangedGraphPrincipalAndRunIdReuse() {
		RunSpec first = spec("one", "key", "{}");
		submit(first);
		RunSpec graph = new RunSpec(1, first.namespace(), "one", "key", first.inputHash(), "other", "1", first.input(),
				first.context());
		assertThatThrownBy(() -> submit(graph)).isInstanceOf(RunStoreConflictException.class);
		RunSpec version = new RunSpec(1, first.namespace(), "one", "key", first.inputHash(), first.graphId(), "2",
				first.input(), first.context());
		assertThatThrownBy(() -> submit(version)).isInstanceOf(RunStoreConflictException.class);
		TrustedExecutionContext other = context("one", "tenant", "other-user", null);
		RunSpec principal = RunSpec.from(other, "app", "session", "key", "graph", "1", first.input());
		assertThatThrownBy(() -> store.submit(principal, other)).isInstanceOf(RunStoreConflictException.class);
		assertThatThrownBy(() -> submit(spec("one", "another-key", "{}")))
			.isInstanceOf(RunStoreConflictException.class);
	}

	@Test
	void rejectsUnauthenticatedReferenceAndExpiredAdmissionWithoutResidue() {
		RunSpec first = spec("one", "key", "{}");
		assertThatThrownBy(() -> store.submit(first, context("one", "forged", "user", null)))
			.isInstanceOf(IllegalArgumentException.class);
		TrustedExecutionContext expired = context("one", "tenant", "user", clock.instant());
		RunSpec expiring = RunSpec.from(expired, "app", "session", "key", "graph", "1", first.input());
		assertThatThrownBy(() -> store.submit(expiring, expired)).isInstanceOf(IllegalArgumentException.class);
		assertThat(store.activeRun(first.namespace())).isEmpty();
		assertThat(store.find(first.namespace(), "one")).isEmpty();
		submit(first);
	}

	@Test
	void admissionUsesThePersistedMillisecondDeadline() {
		TrustedExecutionContext trusted = context("one", "tenant", "user", clock.instant().plusNanos(1));
		RunSpec run = RunSpec.from(trusted, "app", "session", "key", "graph", "1", payload("{}"));
		assertThat(trusted.isExpired(clock.instant())).isFalse();
		assertThatThrownBy(() -> store.submit(run, trusted)).isInstanceOf(IllegalArgumentException.class);
		assertThat(store.activeRun(run.namespace())).isEmpty();
	}

	@Test
	void physicalExpiryAndReleaseDoNotReleaseWaitingSessionReservation() {
		RunSpec first = spec("one", "key", "{}");
		submit(first);
		RunLease original = acquire(first);
		commit(first, original, 0, RunState.RUNNING);
		commit(first, original, 1, RunState.WAITING);
		assertThat(store.release(original)).isTrue();
		assertThat(store.release(original)).isFalse();
		assertThatThrownBy(() -> submit(spec("two", "other", "{}"))).isInstanceOf(RunStoreConflictException.class);
		RunLease next = acquire(first);
		clock.advance(Duration.ofSeconds(30));
		assertThatThrownBy(() -> submit(spec("two", "other", "{}"))).isInstanceOf(RunStoreConflictException.class);
		assertThat(store.activeRun(first.namespace())).contains("one");
		RunLease takeover = acquire(first);
		assertThat(takeover.fencingToken()).isGreaterThan(next.fencingToken());
		assertThatThrownBy(() -> commit(first, next, 2, RunState.RUNNING))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(store.release(next)).isFalse();
		assertThatThrownBy(() -> store.renew(next, Duration.ofSeconds(30)))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(store.find(first.namespace(), "one").orElseThrow().revision()).isEqualTo(2);
		commit(first, takeover, 2, RunState.RUNNING);
	}

	@Test
	void terminalCommitReleasesSessionButRetainsDedupAndFencesAcrossDifferentRuns() {
		RunSpec first = spec("one", "key", "{}");
		submit(first);
		RunLease lease = acquire(first);
		commit(first, lease, 0, RunState.RUNNING);
		RunSnapshot finished = commit(first, lease, 1, RunState.SUCCEEDED);
		assertThat(store.activeRun(first.namespace())).isEmpty();
		assertThat(submit(spec("retry", "key", "{}"))).isEqualTo(finished);
		RunSpec second = spec("two", "other", "{}");
		submit(second);
		RunLease next = acquire(second);
		assertThat(next.fencingToken()).isGreaterThan(lease.fencingToken());
		assertThatThrownBy(() -> commit(second, lease, 0, RunState.RUNNING))
			.isInstanceOf(RunStoreConflictException.class);
		assertThatThrownBy(
				() -> store.acquire(first.namespace(), first.runId(), UUID.randomUUID(), Duration.ofSeconds(30)))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(store.release(lease)).isFalse();
	}

	@Test
	void leaseRenewalUsesStoredExpiryNotAnUntrustedExpiryClaim() {
		RunSpec first = spec("one", "key", "{}");
		submit(first);
		RunLease lease = acquire(first);
		assertThatThrownBy(() -> acquire(first)).isInstanceOf(RunStoreConflictException.class);
		clock.advance(Duration.ofSeconds(10));
		RunLease renewed = store.renew(lease, Duration.ofSeconds(30));
		assertThat(renewed.fencingToken()).isEqualTo(lease.fencingToken());
		assertThat(renewed.expiresAt()).isAfter(lease.expiresAt());
		clock.advance(Duration.ofSeconds(20));
		commit(first, lease, 0, RunState.RUNNING);
		RunLease wrongOwner = new RunLease(first.namespace(), UUID.randomUUID(), lease.fencingToken(),
				renewed.expiresAt());
		assertThatThrownBy(() -> commit(first, wrongOwner, 1, RunState.WAITING))
			.isInstanceOf(RunStoreConflictException.class);
		clock.advance(Duration.ofSeconds(10));
		RunLease fakeExpiry = new RunLease(first.namespace(), lease.ownerId(), lease.fencingToken(), Instant.MAX);
		assertThatThrownBy(() -> commit(first, fakeExpiry, 1, RunState.WAITING))
			.isInstanceOf(RunStoreConflictException.class);
		assertThatThrownBy(() -> store.renew(fakeExpiry, Duration.ofSeconds(30)))
			.isInstanceOf(RunStoreConflictException.class);
	}

	@Test
	void isolatesTenantApplicationAndSessionKeysIncludingDelimiterLikeIds() {
		RunSpec first = spec("same-run", "same-key", "{}");
		submit(first);
		RunLease lease = acquire(first);
		TrustedExecutionContext otherTenant = context("same-run", "other", "user", null);
		List<RunSpec> independent = List.of(
				RunSpec.from(otherTenant, "app", "session", "same-key", "graph", "1", first.input()),
				RunSpec.from(context("same-run"), "app:session", "x", "same-key", "graph", "1", first.input()),
				RunSpec.from(context("same-run"), "app", "session:x", "same-key", "graph", "1", first.input()));
		for (RunSpec spec : independent) {
			TrustedExecutionContext trusted = context(spec.runId(), spec.namespace().tenant().id(), "user", null);
			store.submit(spec, trusted);
			RunLease other = acquire(spec);
			assertThatThrownBy(() -> commit(spec, lease, 0, RunState.RUNNING))
				.isInstanceOf(RunStoreConflictException.class);
			commit(spec, other, 0, RunState.RUNNING);
		}
		assertThat(store.find(first.namespace(), first.runId()).orElseThrow().state()).isEqualTo(RunState.PENDING);
	}

	@Test
	void deadlineRejectsProgressButAllowsAtomicTimeout() {
		TrustedExecutionContext trusted = context("one", "tenant", "user", clock.instant().plusSeconds(1));
		RunSpec first = RunSpec.from(trusted, "app", "session", "key", "graph", "1", payload("{}"));
		store.submit(first, trusted);
		RunLease lease = acquire(first);
		clock.advance(Duration.ofSeconds(1));
		assertThatThrownBy(() -> commit(first, lease, 0, RunState.RUNNING))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(commit(first, lease, 0, RunState.TIMED_OUT).state()).isEqualTo(RunState.TIMED_OUT);
		assertThat(store.activeRun(first.namespace())).isEmpty();
	}

	@Test
	void expiredDeadlineAlsoAllowsCancellationAndExistingDedupRead() {
		TrustedExecutionContext trusted = context("one", "tenant", "user", clock.instant().plusSeconds(1));
		RunSpec first = RunSpec.from(trusted, "app", "session", "key", "graph", "1", payload("{}"));
		store.submit(first, trusted);
		RunLease lease = acquire(first);
		clock.advance(Duration.ofSeconds(1));
		assertThat(store.submit(first, trusted).state()).isEqualTo(RunState.PENDING);
		assertThat(commit(first, lease, 0, RunState.CANCELLED).state()).isEqualTo(RunState.CANCELLED);
	}

	@Test
	void narrowerTaskDeadlineCannotBeBypassedByALiveParentRun() {
		for (RunState terminal : List.of(RunState.TIMED_OUT, RunState.CANCELLED)) {
			RunSpec first = spec("one-" + terminal, "key-" + terminal, "{}");
			submit(first);
			RunLease lease = acquire(first);
			TaskSpec child = new TaskSpec(1, "child", first.runId(), "task", "1", payload("{}"),
					context(first.runId()).delegate("child", Set.of("read"), clock.instant().plusSeconds(1), null)
						.toReference());
			RunSnapshot original = store.commit(first.namespace(), first.runId(), lease,
					new RunCommit(0, RunState.RUNNING, null,
							List.of(new RunCommit.TaskUpdate(child, -1, RunState.PENDING)), List.of(), List.of()));
			clock.advance(Duration.ofSeconds(1));
			assertThatThrownBy(() -> store.commit(first.namespace(), first.runId(), lease,
					new RunCommit(1, RunState.RUNNING, payload("{\"changed\":true}"),
							List.of(new RunCommit.TaskUpdate(child, 0, RunState.RUNNING)), List.of(), List.of())))
				.isInstanceOf(RunStoreConflictException.class);
			assertThat(store.find(first.namespace(), first.runId())).contains(original);
			RunSnapshot ended = store.commit(first.namespace(), first.runId(), lease,
					new RunCommit(1, RunState.SUCCEEDED, null, List.of(new RunCommit.TaskUpdate(child, 0, terminal)),
							List.of(), List.of()));
			assertThat(ended.tasks().get("child").state()).isEqualTo(terminal);
		}
	}

	@Test
	void publishesRunCheckpointTasksJournalAndOutboxAsOneImmutableSnapshot() {
		RunSpec first = spec("one", "key", "{}");
		RunSnapshot pending = submit(first);
		RunLease lease = acquire(first);
		TaskSpec task = task(first, "child");
		List<RunCommit.TaskUpdate> tasks = new ArrayList<>(
				List.of(new RunCommit.TaskUpdate(task, -1, RunState.PENDING)));
		RunEntry event = entry("event", RunEntry.Kind.EVENT, "{}");
		List<RunEntry> journal = new ArrayList<>();
		for (RunEntry.Kind kind : RunEntry.Kind.values()) {
			journal.add(entry(kind.name(), kind, "{}"));
		}
		RunCommit command = new RunCommit(0, RunState.RUNNING, payload("{\"node\":\"start\"}"), tasks, journal,
				List.of(event));
		tasks.clear();
		journal.clear();
		RunSnapshot next = store.commit(first.namespace(), first.runId(), lease, command);
		assertThat(next.revision()).isEqualTo(1);
		assertThat(next.tasks().get("child").revision()).isZero();
		assertThat(next.journal()).hasSize(4);
		assertThat(next.outbox()).containsEntry("event", event);
		assertThat(pending.tasks()).isEmpty();
		assertThatThrownBy(() -> next.tasks().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> next.journal().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> next.outbox().clear()).isInstanceOf(UnsupportedOperationException.class);
		RunSnapshot progress = commit(first, lease, 1, RunState.RUNNING);
		assertThat(progress.checkpoint()).isEqualTo(next.checkpoint());
		assertThat(progress.outbox()).isEqualTo(next.outbox());
	}

	@Test
	void rollsBackEveryComponentOnLateOutboxFailureAndStaleRevision() {
		RunSpec first = spec("one", "key", "{}");
		RunSnapshot original = submit(first);
		RunLease lease = acquire(first);
		RunCommit invalid = new RunCommit(0, RunState.RUNNING, payload("{\"checkpoint\":1}"),
				List.of(new RunCommit.TaskUpdate(task(first, "task"), -1, RunState.PENDING)),
				List.of(entry("model", RunEntry.Kind.MODEL_CALL, "{}")),
				List.of(entry("invalid", RunEntry.Kind.APPROVAL, "{}")));
		assertThatThrownBy(() -> store.commit(first.namespace(), first.runId(), lease, invalid))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(store.find(first.namespace(), first.runId())).contains(original);
		commit(first, lease, 0, RunState.RUNNING);
		assertThatThrownBy(() -> store.commit(first.namespace(), first.runId(), lease, invalid))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(store.find(first.namespace(), first.runId()).orElseThrow().revision()).isEqualTo(1);
	}

	@Test
	void appendOnlyIdsAreIdempotentButChangedDataRollsBackTerminalCommit() {
		RunSpec first = spec("one", "key", "{}");
		submit(first);
		RunLease lease = acquire(first);
		RunEntry event = entry("id", RunEntry.Kind.EVENT, "{}");
		RunCommit start = new RunCommit(0, RunState.RUNNING, null, List.of(), List.of(event), List.of(event));
		store.commit(first.namespace(), first.runId(), lease, start);
		RunSnapshot repeated = store.commit(first.namespace(), first.runId(), lease,
				new RunCommit(1, RunState.RUNNING, null, List.of(), List.of(event, event), List.of(event)));
		RunEntry changed = entry("id", RunEntry.Kind.EVENT, "{\"changed\":true}");
		for (boolean journal : List.of(true, false)) {
			RunCommit invalid = new RunCommit(2, RunState.SUCCEEDED, payload("{\"new\":1}"), List.of(),
					journal ? List.of(changed) : List.of(), journal ? List.of() : List.of(changed));
			assertThatThrownBy(() -> store.commit(first.namespace(), first.runId(), lease, invalid))
				.isInstanceOf(RunStoreConflictException.class);
			assertThat(store.find(first.namespace(), first.runId())).contains(repeated);
			assertThat(store.activeRun(first.namespace())).contains(first.runId());
		}
	}

	@Test
	void enforcesImmutableTaskSpecsTaskCasTransitionsAndTerminalConsistency() {
		RunSpec first = spec("one", "key", "{}");
		submit(first);
		RunLease lease = acquire(first);
		TaskSpec task = task(first, "task");
		RunCommit.TaskUpdate create = new RunCommit.TaskUpdate(task, -1, RunState.PENDING);
		store.commit(first.namespace(), first.runId(), lease,
				new RunCommit(0, RunState.RUNNING, null, List.of(create), List.of(), List.of()));
		RunSnapshot original = store.find(first.namespace(), first.runId()).orElseThrow();
		TaskSpec changed = new TaskSpec(1, "task", first.runId(), "different", "1", task.input(), task.context());
		List<List<RunCommit.TaskUpdate>> invalid = List.of(List.of(create),
				List.of(new RunCommit.TaskUpdate(task, 7, RunState.RUNNING)),
				List.of(new RunCommit.TaskUpdate(task, 0, RunState.SUCCEEDED)),
				List.of(new RunCommit.TaskUpdate(changed, 0, RunState.RUNNING)),
				List.of(new RunCommit.TaskUpdate(task(first, "missing"), 0, RunState.PENDING)),
				List.of(new RunCommit.TaskUpdate(task(first, "missing"), -1, RunState.RUNNING)),
				List.of(new RunCommit.TaskUpdate(task, 0, RunState.RUNNING),
						new RunCommit.TaskUpdate(task, 1, RunState.WAITING)));
		for (List<RunCommit.TaskUpdate> updates : invalid) {
			assertThatThrownBy(() -> store.commit(first.namespace(), first.runId(), lease,
					new RunCommit(1, RunState.RUNNING, payload("{\"new\":1}"), updates, List.of(), List.of())))
				.isInstanceOfAny(IllegalArgumentException.class, RunStoreConflictException.class);
			assertThat(store.find(first.namespace(), first.runId())).contains(original);
		}
		assertThatThrownBy(() -> commit(first, lease, 1, RunState.SUCCEEDED))
			.isInstanceOf(RunStoreConflictException.class);
		store.commit(first.namespace(), first.runId(), lease, new RunCommit(1, RunState.RUNNING, null,
				List.of(new RunCommit.TaskUpdate(task, 0, RunState.RUNNING)), List.of(), List.of()));
		RunSnapshot terminal = store.commit(first.namespace(), first.runId(), lease,
				new RunCommit(2, RunState.SUCCEEDED, null,
						List.of(new RunCommit.TaskUpdate(task, 1, RunState.SUCCEEDED)), List.of(), List.of()));
		assertThat(terminal.tasks().get("task").revision()).isEqualTo(2);
		assertThat(store.activeRun(first.namespace())).isEmpty();
	}

	@Test
	void concurrentDifferentRunsAndOwnersHaveExactlyOneWinner() throws Exception {
		List<Callable<Boolean>> submissions = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			RunSpec spec = spec("run-" + i, "key-" + i, "{}");
			submissions.add(() -> wins(() -> submit(spec)));
		}
		assertThat(race(submissions)).filteredOn(Boolean::booleanValue).hasSize(1);
		String runId = store.activeRun(spec("x", "x", "{}").namespace()).orElseThrow();
		RunSpec winner = spec(runId, "unused", "{}");
		List<Callable<Boolean>> owners = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			owners.add(() -> wins(() -> acquire(winner)));
		}
		assertThat(race(owners)).filteredOn(Boolean::booleanValue).hasSize(1);
	}

	@Test
	void concurrentDuplicateSubmissionsAndRevisionCasAreLinearizable() throws Exception {
		List<Callable<RunSnapshot>> submissions = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			RunSpec spec = spec("run-" + i, "key", "{}");
			submissions.add(() -> submit(spec));
		}
		List<RunSnapshot> snapshots = race(submissions);
		assertThat(snapshots).allMatch(snapshot -> snapshot.equals(snapshots.get(0)));
		RunSpec first = snapshots.get(0).spec();
		RunLease lease = acquire(first);
		List<Callable<Boolean>> writes = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			writes.add(() -> wins(() -> commit(first, lease, 0, RunState.RUNNING)));
		}
		assertThat(race(writes)).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(store.find(first.namespace(), first.runId()).orElseThrow().revision()).isEqualTo(1);
	}

	@Test
	void readersNeverObserveTornSnapshotsDuringRepeatedAtomicWrites() throws Exception {
		RunSpec first = spec("one", "key", "{}");
		submit(first);
		RunLease lease = acquire(first);
		AtomicBoolean done = new AtomicBoolean();
		Callable<Boolean> writer = () -> {
			try {
				for (int revision = 1; revision <= 200; revision++) {
					RunPayload data = payload("{\"revision\":" + revision + "}");
					RunEntry event = new RunEntry(1, "event-" + revision, RunEntry.Kind.EVENT, data);
					store.commit(first.namespace(), first.runId(), lease, new RunCommit(revision - 1, RunState.RUNNING,
							data, List.of(), List.of(event), List.of(event)));
				}
				return true;
			}
			finally {
				done.set(true);
			}
		};
		Callable<Boolean> reader = () -> {
			do {
				RunSnapshot snapshot = store.find(first.namespace(), first.runId()).orElseThrow();
				assertThat(snapshot.journal()).hasSize((int) snapshot.revision());
				assertThat(snapshot.outbox()).hasSize((int) snapshot.revision());
				if (snapshot.revision() > 0) {
					assertThat(snapshot.checkpoint()).isEqualTo(payload("{\"revision\":" + snapshot.revision() + "}"));
				}
			}
			while (!done.get());
			return true;
		};
		assertThat(race(List.of(writer, reader))).containsOnly(true);
		assertThat(store.find(first.namespace(), first.runId()).orElseThrow().revision()).isEqualTo(200);
	}

	@Test
	void unknownNamespacesAndIllegalTransitionsNeverCreateRecords() {
		RunSpec first = spec("one", "key", "{}");
		assertThat(store.find(first.namespace(), first.runId())).isEmpty();
		assertThat(store.activeRun(first.namespace())).isEmpty();
		assertThatThrownBy(() -> acquire(first)).isInstanceOf(RunStoreConflictException.class);
		RunLease nonexistent = new RunLease(first.namespace(), UUID.randomUUID(), 1, clock.instant().plusSeconds(30));
		assertThat(store.release(nonexistent)).isFalse();
		assertThatThrownBy(() -> store.renew(nonexistent, Duration.ofSeconds(30)))
			.isInstanceOf(RunStoreConflictException.class);
		submit(first);
		RunLease lease = acquire(first);
		assertThatThrownBy(() -> commit(first, lease, 0, RunState.SUCCEEDED))
			.isInstanceOf(IllegalArgumentException.class);
		RunSnapshot finished = commit(first, lease, 0, RunState.CANCELLED);
		assertThatThrownBy(() -> commit(first, lease, 1, RunState.RUNNING))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(store.find(first.namespace(), first.runId())).contains(finished);
	}

	protected static RunPayload payload(String json) {
		return new RunPayload(1, "test.input", json);
	}

	protected static RunEntry entry(String id, RunEntry.Kind kind, String json) {
		return new RunEntry(1, id, kind, payload(json));
	}

	protected static TrustedExecutionContext context(String runId) {
		return context(runId, "tenant", "user", null);
	}

	protected static TrustedExecutionContext context(String runId, String tenant, String user, Instant deadline) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", tenant))
			.principal(new PrincipalReference("iam", user))
			.runId(runId)
			.correlationId("trace")
			.deadline(deadline)
			.budget(new ExecutionBudget(Map.of("tokens", 100L)))
			.delegationScope(new DelegationScope(Set.of("read")))
			.build();
	}

	protected static RunSpec spec(String runId, String key, String json) {
		return RunSpec.from(context(runId), "app", "session", key, "graph", "1", payload(json));
	}

	protected static TaskSpec task(RunSpec run, String taskId) {
		return new TaskSpec(1, taskId, run.runId(), "task", "1", payload("{}"),
				context(run.runId()).delegate(taskId, Set.of("read"), null, null).toReference());
	}

	protected RunSnapshot submit(RunSpec spec) {
		return store.submit(spec, context(spec.runId()));
	}

	protected RunLease acquire(RunSpec spec) {
		return store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), Duration.ofSeconds(30));
	}

	protected RunSnapshot commit(RunSpec spec, RunLease lease, long revision, RunState state) {
		return store.commit(spec.namespace(), spec.runId(), lease,
				new RunCommit(revision, state, null, List.of(), List.of(), List.of()));
	}

	private static boolean wins(Runnable action) {
		try {
			action.run();
			return true;
		}
		catch (RunStoreConflictException ex) {
			return false;
		}
	}

	private static <T> List<T> race(List<Callable<T>> actions) throws Exception {
		var executor = Executors.newFixedThreadPool(actions.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> results = new ArrayList<>();
			for (Callable<T> action : actions) {
				results.add(executor.submit(() -> {
					start.await();
					return action.call();
				}));
			}
			start.countDown();
			List<T> values = new ArrayList<>();
			for (Future<T> result : results) {
				values.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
			}
			return values;
		}
		finally {
			executor.shutdownNow();
		}
	}

	/** Deterministic expiry tests without sleeps. */
	protected static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-10-14T00:00:00Z");

		public void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
