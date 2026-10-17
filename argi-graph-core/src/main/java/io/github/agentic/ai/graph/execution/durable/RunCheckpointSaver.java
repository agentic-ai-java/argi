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

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionLease;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.execution.run.RunCommit;
import io.github.agentic.ai.graph.execution.run.RunEntry;
import io.github.agentic.ai.graph.execution.run.RunLease;
import io.github.agentic.ai.graph.execution.run.RunPayload;
import io.github.agentic.ai.graph.execution.run.RunSnapshot;
import io.github.agentic.ai.graph.execution.run.RunState;
import io.github.agentic.ai.graph.execution.run.RunStore;
import io.github.agentic.ai.graph.execution.run.RunStoreConflictException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** One worker's adapter; every checkpoint and event uses the same fenced atomic commit. */
final class RunCheckpointSaver implements LeasedCheckpointSaver {
	private final RunStore store;
	private final DurableStateCodec codec;
	private final LeaseOptions options;
	private final Consumer<RunSnapshot> onCheckpoint;
	private final BooleanSupplier deadlineReached;
	private RunSnapshot snapshot;
	private RunLease lease;
	private boolean accepting = true;
	private boolean lost;

	RunCheckpointSaver(RunStore store, RunSnapshot snapshot, RunLease lease, DurableStateCodec codec,
			LeaseOptions options, Consumer<RunSnapshot> onCheckpoint, BooleanSupplier deadlineReached) {
		this.store = store;
		this.snapshot = snapshot;
		this.lease = lease;
		this.codec = codec;
		this.options = options;
		this.onCheckpoint = onCheckpoint;
		this.deadlineReached = deadlineReached;
	}

	synchronized RunSnapshot snapshot() {
		return snapshot;
	}

	synchronized ExecutionLease executionLease() {
		return new ExecutionLease(snapshot.spec().runId(), lease.ownerId(), lease.fencingToken(),
				lease.expiresAt().toEpochMilli());
	}

	@Override
	public String checkpointThreadId(RunnableConfig config) {
		if (!config.threadId().filter(snapshot.spec().runId()::equals).isPresent()) {
			throw new IllegalArgumentException("Durable checkpoint belongs to a different run");
		}
		return snapshot.spec().runId();
	}

	@Override
	public LeaseOptions leaseOptions() {
		return options;
	}

	@Override
	public ExecutionLease acquireLease(RunnableConfig config, UUID ownerId) {
		throw new IllegalStateException("The worker lease must be bound before graph execution");
	}

	@Override
	public synchronized ExecutionLease renewLease(RunnableConfig config, ExecutionLease supplied) {
		check(config, supplied);
		try {
			lease = store.renew(lease, options.ttl());
			return executionLease();
		}
		catch (RunStoreConflictException ex) {
			throw loss(ex);
		}
	}

	@Override
	public boolean releaseLease(RunnableConfig config, ExecutionLease supplied) {
		throw new IllegalStateException("Only the outer worker may release its run lease");
	}

	@Override
	public synchronized CheckpointSnapshot getVersioned(RunnableConfig config) {
		checkpointThreadId(config);
		Optional<Checkpoint> checkpoint = checkpoint();
		if (config.checkPointId().isPresent()
				&& checkpoint.filter(cp -> cp.getId().equals(config.checkPointId().get())).isEmpty()) {
			throw new IllegalArgumentException("Only the committed checkpoint may be resumed");
		}
		return new CheckpointSnapshot(checkpoint, snapshot.revision());
	}

	private Optional<Checkpoint> checkpoint() {
		return Optional.ofNullable(snapshot.checkpoint()).map(codec::decodeCheckpoint);
	}

	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		return get(config).map(List::of).orElseGet(List::of);
	}

	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		return getVersioned(config).checkpoint();
	}

	@Override
	public synchronized RunnableConfig putIfLeasedVersion(RunnableConfig config, Checkpoint checkpoint,
			long expectedRevision, ExecutionLease supplied) {
		check(config, supplied);
		if (deadlineReached.getAsBoolean()) {
			throw new DeadlineExceededException();
		}
		if (snapshot.revision() != expectedRevision) {
			throw loss(new RunStoreConflictException("Checkpoint revision does not match the run"));
		}
		commit(RunState.RUNNING, codec.encodeCheckpoint(checkpoint));
		onCheckpoint.accept(snapshot);
		return config.withCheckPointId(checkpoint.getId());
	}

	@Override
	public Tag releaseIfLeasedVersion(RunnableConfig config, long revision, ExecutionLease supplied) {
		throw new IllegalStateException("Durable execution retains its committed checkpoint");
	}

	synchronized void start(Checkpoint initial) {
		commit(RunState.RUNNING, snapshot.checkpoint() == null ? codec.encodeCheckpoint(initial) : null);
	}

	synchronized RunSnapshot finish(RunState state) {
		accepting = false;
		if (!snapshot.state().terminal()) {
			commit(state, null);
		}
		return snapshot;
	}

	synchronized void release() {
		accepting = false;
		store.release(lease);
	}

	synchronized boolean lost() {
		return lost;
	}

	private void check(RunnableConfig config, ExecutionLease supplied) {
		checkpointThreadId(config);
		if (!accepting || lost || !executionLease().namespace().equals(supplied.namespace())
				|| !lease.ownerId().equals(supplied.ownerId()) || lease.fencingToken() != supplied.fencingToken()) {
			throw loss(new IllegalStateException("Run owner is no longer active"));
		}
	}

	private void commit(RunState state, RunPayload checkpoint) {
		if (lost) {
			throw loss(new IllegalStateException("Run ownership was lost"));
		}
		long revision = Math.addExact(snapshot.revision(), 1);
		RunEntry event = new RunEntry(1, "argi.durable." + revision, RunEntry.Kind.EVENT,
				new RunPayload(1, "argi.durable.progress",
						"{\"revision\":" + revision + ",\"state\":\"" + state.name() + "\"}"));
		try {
			snapshot = store.commit(snapshot.spec().namespace(), snapshot.spec().runId(), lease,
					new RunCommit(snapshot.revision(), state, checkpoint, List.of(), List.of(), List.of(event)));
		}
		catch (RunStoreConflictException ex) {
			throw loss(ex);
		}
	}

	private LeaseLostException loss(Throwable cause) {
		lost = true;
		return new LeaseLostException(snapshot.spec().runId(), lease.ownerId(), lease.fencingToken(),
				"Run lease or revision is no longer valid", cause);
	}

	/** Stops progress without discarding the still-valid fence needed to commit timeout. */
	static final class DeadlineExceededException extends IllegalStateException {
		DeadlineExceededException() {
			super("Run deadline passed before checkpoint publication");
		}
	}
}
