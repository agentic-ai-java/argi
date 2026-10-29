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

/**
 * Narrow journal access supplied by a runtime that owns the run's submission, lease,
 * revision and execution position. Writes use {@link RunStore#commit}, rather than an
 * independent journal backend. Engines need not manage storage ownership themselves.
 *
 * <p>
 * Entry ids are stable within a run. Identical journal data may be submitted again;
 * changed data under the same id is a conflict. Every successful write may advance the
 * run revision, including a write containing only previously recorded entries.
 */
public interface RunJournalWriter {

	/** Reads an entry from the runtime's committed view of the run. */
	Optional<RunEntry> find(String runId, String entryId);

	/**
	 * Atomically appends journal entries through a run commit, retaining the current
	 * checkpoint. Used for request fingerprints and diagnostic progress.
	 * @throws RunStoreConflictException when the run commit or an entry conflicts
	 */
	void append(String runId, List<RunEntry> entries);

	/**
	 * Atomically commits the supplied checkpoint and every journal entry. The runtime
	 * calls this after preparing the corresponding execution position; returning means
	 * the storage commit has completed, not that the entries have merely been buffered.
	 * @param runId the run whose checkpoint and journal advance together
	 * @param checkpoint the non-null checkpoint corresponding to these entries
	 * @param entries the complete batch to commit
	 * @throws RunStoreConflictException when the run commit or an entry conflicts
	 */
	void commit(String runId, RunPayload checkpoint, List<RunEntry> entries);

}
