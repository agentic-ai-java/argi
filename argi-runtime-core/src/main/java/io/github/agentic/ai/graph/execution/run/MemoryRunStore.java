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

import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Single-process reference store. A shared monitor provides atomic visibility and
 * rollback-by-validation: all replacement data is built before any stored state changes.
 * It is NOT persistent, cannot support durable mode and performs no scheduling or I/O.
 * Sharing this instance is required for single-writer behavior within a JVM.
 */
public final class MemoryRunStore implements RunStore {

	private static final RunStoreCapabilities CAPABILITIES = new RunStoreCapabilities(false, true, true, true, true,
			true);

	private final Clock clock;

	private final Map<RunNamespace, NamespaceData> namespaces = new HashMap<>();

	public MemoryRunStore() {
		this(Clock.systemUTC());
	}

	public MemoryRunStore(Clock clock) {
		this.clock = requireNonNull(clock, "clock cannot be null");
	}

	@Override
	public RunStoreCapabilities capabilities() {
		return CAPABILITIES;
	}

	@Override
	public synchronized RunSnapshot submit(RunSpec spec, TrustedExecutionContext context) {
		requireNonNull(spec, "spec cannot be null");
		requireNonNull(context, "trusted context cannot be null");
		if (!spec.context().equals(context.toReference())) {
			throw new IllegalArgumentException("submission must match the authenticated context");
		}
		NamespaceData data = namespaces.get(spec.namespace());
		if (data != null) {
			String existingId = data.submissions.get(spec.submissionKey());
			if (existingId != null) {
				RunSnapshot existing = data.runs.get(existingId);
				RunSpec original = existing.spec();
				if (!original.inputHash().equals(spec.inputHash()) || !original.definition().equals(spec.definition())
						|| !original.context().principal().equals(spec.context().principal())) {
					throw conflict("Submission key already identifies different input, definition or principal");
				}
				return existing;
			}
			if (data.runs.containsKey(spec.runId())) {
				throw conflict("Run id already exists under a different submission key");
			}
			if (data.reservation != null) {
				throw conflict("Session is reserved by a nonterminal run");
			}
		}
		Instant now = clock.instant();
		Long deadline = spec.deadlineEpochMillis();
		if (deadline != null && !now.isBefore(Instant.ofEpochMilli(deadline))) {
			throw new IllegalArgumentException("Cannot admit an expired run");
		}
		RunSnapshot result = new RunSnapshot(spec, 0, RunState.PENDING, null, Map.of(), Map.of(), Map.of());
		if (data == null) {
			data = new NamespaceData();
			namespaces.put(spec.namespace(), data);
		}
		data.runs.put(spec.runId(), result);
		data.submissions.put(spec.submissionKey(), spec.runId());
		data.reservation = spec.runId();
		return result;
	}

	@Override
	public synchronized Optional<RunSnapshot> find(RunNamespace namespace, String runId) {
		requireNonNull(namespace, "namespace cannot be null");
		RunValidation.text(runId, "runId");
		NamespaceData data = namespaces.get(namespace);
		return data == null ? Optional.empty() : Optional.ofNullable(data.runs.get(runId));
	}

	@Override
	public synchronized Optional<String> activeRun(RunNamespace namespace) {
		requireNonNull(namespace, "namespace cannot be null");
		NamespaceData data = namespaces.get(namespace);
		return data == null ? Optional.empty() : Optional.ofNullable(data.reservation);
	}

	@Override
	public synchronized RunLease acquire(RunNamespace namespace, String runId, UUID ownerId, Duration ttl) {
		requireNonNull(ownerId, "ownerId cannot be null");
		long ttlMillis = RunValidation.ttlMillis(ttl);
		NamespaceData data = reserved(namespace, runId);
		Instant now = clock.instant();
		if (data.lease != null && now.isBefore(data.lease.expiresAt())) {
			throw conflict("Namespace already has a live owner");
		}
		long fence = Math.addExact(data.fence, 1);
		RunLease lease = new RunLease(namespace, ownerId, fence, now.plusMillis(ttlMillis));
		data.fence = fence;
		data.lease = lease;
		return lease;
	}

	@Override
	public synchronized RunLease renew(RunLease lease, Duration ttl) {
		requireNonNull(lease, "lease cannot be null");
		long ttlMillis = RunValidation.ttlMillis(ttl);
		NamespaceData data = namespaces.get(lease.namespace());
		Instant now = clock.instant();
		requireLease(data, lease.namespace(), lease, now);
		RunLease renewed = new RunLease(lease.namespace(), lease.ownerId(), lease.fencingToken(),
				now.plusMillis(ttlMillis));
		data.lease = renewed;
		return renewed;
	}

	@Override
	public synchronized boolean release(RunLease lease) {
		requireNonNull(lease, "lease cannot be null");
		NamespaceData data = namespaces.get(lease.namespace());
		if (data == null || !matches(data.lease, lease)) {
			return false;
		}
		data.lease = null;
		return true;
	}

	@Override
	public synchronized RunSnapshot commit(RunNamespace namespace, String runId, RunLease lease, RunCommit commit) {
		requireNonNull(commit, "commit cannot be null");
		NamespaceData data = reserved(namespace, runId);
		Instant now = clock.instant();
		requireLease(data, namespace, lease, now);
		RunSnapshot current = data.runs.get(runId);
		if (current.revision() != commit.expectedRevision()) {
			throw conflict("Run revision does not match");
		}
		current.state().requireTransitionTo(commit.state());
		Long deadline = current.spec().deadlineEpochMillis();
		if (deadline != null && !now.isBefore(Instant.ofEpochMilli(deadline)) && commit.state() != RunState.TIMED_OUT
				&& commit.state() != RunState.CANCELLED) {
			throw conflict("Expired run can only time out or be cancelled");
		}
		Map<String, TaskRecord> tasks = new HashMap<>(current.tasks());
		Set<String> updatedTasks = new HashSet<>();
		for (RunCommit.TaskUpdate update : commit.tasks()) {
			TaskSpec task = update.spec();
			task.validateFor(current.spec());
			Long taskDeadline = task.deadlineEpochMillis();
			if (taskDeadline != null && !now.isBefore(Instant.ofEpochMilli(taskDeadline))
					&& update.state() != RunState.TIMED_OUT && update.state() != RunState.CANCELLED) {
				throw conflict("Expired task can only time out or be cancelled");
			}
			if (!updatedTasks.add(task.taskId())) {
				throw new IllegalArgumentException("Duplicate task update in one commit");
			}
			TaskRecord previous = tasks.get(task.taskId());
			long nextRevision;
			if (previous == null) {
				if (update.expectedRevision() != -1 || update.state() != RunState.PENDING) {
					throw conflict("New task must start PENDING with expectedRevision=-1");
				}
				nextRevision = 0;
			}
			else {
				if (previous.revision() != update.expectedRevision() || !previous.spec().equals(task)) {
					throw conflict("Task revision or immutable spec does not match");
				}
				previous.state().requireTransitionTo(update.state());
				nextRevision = Math.addExact(previous.revision(), 1);
			}
			tasks.put(task.taskId(), new TaskRecord(task, nextRevision, update.state()));
		}
		if (commit.state().terminal() && tasks.values().stream().anyMatch(task -> !task.state().terminal())) {
			throw conflict("Terminal run cannot retain active tasks");
		}
		Map<String, RunEntry> journal = append(current.journal(), commit.journal(), false);
		Map<String, RunEntry> outbox = append(current.outbox(), commit.outbox(), true);
		RunSnapshot result = new RunSnapshot(current.spec(), Math.addExact(current.revision(), 1), commit.state(),
				commit.checkpoint() != null ? commit.checkpoint() : current.checkpoint(), tasks, journal, outbox);
		// Publish only after every guard and data validation succeeded.
		data.runs.put(runId, result);
		if (commit.state().terminal()) {
			data.reservation = null;
			data.lease = null;
		}
		return result;
	}

	private NamespaceData reserved(RunNamespace namespace, String runId) {
		requireNonNull(namespace, "namespace cannot be null");
		RunValidation.text(runId, "runId");
		NamespaceData data = namespaces.get(namespace);
		if (data == null || !runId.equals(data.reservation)) {
			throw conflict("Run does not own the session reservation");
		}
		return data;
	}

	private static boolean matches(RunLease stored, RunLease supplied) {
		return stored != null && stored.namespace().equals(supplied.namespace())
				&& stored.ownerId().equals(supplied.ownerId()) && stored.fencingToken() == supplied.fencingToken();
	}

	private static void requireLease(NamespaceData data, RunNamespace namespace, RunLease lease, Instant now) {
		requireNonNull(lease, "lease cannot be null");
		if (data == null || !namespace.equals(lease.namespace()) || !matches(data.lease, lease)
				|| !now.isBefore(data.lease.expiresAt())) {
			throw conflict("Namespace lease is absent, expired or owned by another fence");
		}
	}

	private static Map<String, RunEntry> append(Map<String, RunEntry> previous, List<RunEntry> entries,
			boolean outbox) {
		Map<String, RunEntry> result = new HashMap<>(previous);
		for (RunEntry entry : entries) {
			if (outbox && entry.kind() != RunEntry.Kind.EVENT) {
				throw new IllegalArgumentException("Outbox only accepts EVENT entries");
			}
			RunEntry existing = result.putIfAbsent(entry.id(), entry);
			if (existing != null && !existing.equals(entry)) {
				throw conflict("Append-only entry id identifies different data");
			}
		}
		return result;
	}

	private static RunStoreConflictException conflict(String message) {
		return new RunStoreConflictException(message);
	}

	private static final class NamespaceData {

		private final Map<String, RunSnapshot> runs = new HashMap<>();

		private final Map<String, String> submissions = new HashMap<>();

		private String reservation;

		private long fence;

		private RunLease lease;

	}

}
