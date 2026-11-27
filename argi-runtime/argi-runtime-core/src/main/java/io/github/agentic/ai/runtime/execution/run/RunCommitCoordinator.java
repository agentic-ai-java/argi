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
package io.github.agentic.ai.runtime.execution.run;

import io.github.agentic.ai.runtime.execution.ExecutionGuard;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One invocation's serial commit boundary. The caller owns lease acquisition, renewal and
 * release; the supplier returns its current lease. This class never schedules work or
 * retries a write. A failed write invalidates the cached snapshot until reload.
 */
public final class RunCommitCoordinator {

	private final RunStore store;

	private final Supplier<RunLease> lease;

	private final ExecutionGuard guard;

	private RunSnapshot snapshot;

	private boolean reloadRequired;

	public RunCommitCoordinator(RunStore store, RunSnapshot snapshot, Supplier<RunLease> lease, ExecutionGuard guard) {
		this.store = Objects.requireNonNull(store, "store cannot be null");
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot cannot be null");
		this.lease = Objects.requireNonNull(lease, "lease cannot be null");
		this.guard = Objects.requireNonNull(guard, "guard cannot be null");
	}

	public ExecutionGuard guard() {
		return guard;
	}

	public RunStoreCapabilities capabilities() {
		return store.capabilities();
	}

	public synchronized RunSnapshot snapshot() {
		if (reloadRequired) {
			throw new IllegalStateException("Reload the run after a failed or uncertain commit");
		}
		return snapshot;
	}

	/**
	 * Reloading resolves storage state; the engine must then rebuild its execution
	 * cursor.
	 */
	public synchronized RunSnapshot reload() {
		RunSpec spec = snapshot.spec();
		snapshot = store.find(spec.namespace(), spec.runId()).orElseThrow();
		reloadRequired = false;
		return snapshot;
	}

	public synchronized RunSnapshot commit(RunState state, RunPayload checkpoint, List<RunEntry> journal) {
		guard.assertActive();
		RunSnapshot current = snapshot();
		try {
			snapshot = store.commit(current.spec().namespace(), current.spec().runId(), lease.get(),
					new RunCommit(current.revision(), state, checkpoint, List.of(), journal, List.of()));
			return snapshot;
		}
		catch (RuntimeException failure) {
			reloadRequired = true;
			throw failure;
		}
	}

}
