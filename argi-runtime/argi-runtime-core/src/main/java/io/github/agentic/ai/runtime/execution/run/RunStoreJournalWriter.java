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

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Reference journal writer bound to a runtime-supplied lease and committed run snapshot.
 * The store remains the only persistence backend and supplies all atomicity, fencing and
 * revision checks. This adapter neither admits runs nor manages leases or transitions.
 *
 * <p>
 * All writes are serialized and use the last successful snapshot's revision. A failed
 * commit leaves that view unchanged and is propagated without retry. If the runtime
 * advances the run through another path, it must create a new writer from the updated
 * snapshot. Reads use the bound view rather than silently loading a newer revision.
 */
public final class RunStoreJournalWriter implements RunJournalWriter {

	private final RunStore store;

	private final RunLease lease;

	private RunSnapshot snapshot;

	public RunStoreJournalWriter(RunStore store, RunLease lease, RunSnapshot snapshot) {
		this.store = requireNonNull(store, "store cannot be null");
		this.lease = requireNonNull(lease, "lease cannot be null");
		this.snapshot = requireNonNull(snapshot, "snapshot cannot be null");
		if (!lease.namespace().equals(snapshot.spec().namespace())) {
			throw new IllegalArgumentException("lease namespace does not match the run");
		}
	}

	@Override
	public synchronized Optional<RunEntry> find(String runId, String entryId) {
		requireRun(runId);
		RunValidation.text(entryId, "entryId");
		return Optional.ofNullable(snapshot.journal().get(entryId));
	}

	@Override
	public synchronized void append(String runId, List<RunEntry> entries) {
		write(runId, null, entries);
	}

	@Override
	public synchronized void commit(String runId, RunPayload checkpoint, List<RunEntry> entries) {
		write(runId, requireNonNull(checkpoint, "checkpoint cannot be null"), entries);
	}

	/** The last successful committed view, for the runtime coordinating this writer. */
	public synchronized RunSnapshot snapshot() {
		return snapshot;
	}

	private void write(String runId, RunPayload checkpoint, List<RunEntry> entries) {
		requireRun(runId);
		RunCommit commit = new RunCommit(snapshot.revision(), snapshot.state(), checkpoint, List.of(), entries,
				List.of());
		snapshot = store.commit(snapshot.spec().namespace(), runId, lease, commit);
	}

	private void requireRun(String runId) {
		if (!snapshot.spec().runId().equals(runId)) {
			throw new IllegalArgumentException("runId does not match the bound run");
		}
	}

}
