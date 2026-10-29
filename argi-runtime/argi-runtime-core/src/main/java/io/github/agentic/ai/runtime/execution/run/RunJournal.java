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
 * Append-only journal for data that cannot be regenerated: model requests and the
 * responses and tool plans derived from them.
 *
 * <p>
 * This is deliberately narrower than {@link RunStore}. A journal write carries no lease,
 * no run state transition and no revision, because it is not a run commit: it records
 * what already happened so a recovery can reuse it instead of asking a model again. Use
 * {@link RunStore#commit} when checkpoint, task and journal data must advance together
 * under an owner fence.
 *
 * <p>
 * Ids are caller-assigned and stable across JVMs. Re-appending an id with identical data
 * is a no-op; re-appending it with different data is a conflict and nothing is written.
 *
 * @see RunEntry
 */
public interface RunJournal {

	/**
	 * Reads one entry of one stream.
	 * @param streamId caller-owned stream identity, for example a graph thread id
	 * @param entryId stable entry identity within that stream
	 * @return the recorded entry, or empty when it was never appended
	 */
	Optional<RunEntry> find(String streamId, String entryId);

	/** Every entry of {@code streamId} in append order; empty when the stream is unknown. */
	List<RunEntry> entries(String streamId);

	/**
	 * Atomically appends every entry or none of them.
	 *
	 * <p>
	 * All data is validated before anything is published, so a conflict or a rejected
	 * payload leaves the journal unchanged. A duplicate id whose data equals the recorded
	 * entry is accepted, which makes a retried append after an unknown outcome safe.
	 * @param streamId caller-owned stream identity
	 * @param entries entries to append in order
	 * @throws RunStoreConflictException when an id is already recorded with other data
	 * @throws IllegalArgumentException when an entry is invalid
	 */
	void append(String streamId, List<RunEntry> entries);

}
