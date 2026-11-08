/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.runtime.execution.run.metrics;

import io.github.agentic.ai.runtime.execution.run.RunState;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One collected view of persisted run state stock. The counts are the stock per
 * {@link RunState}; {@code collectedAt} is when the collection succeeded, and it is
 * only advanced by a successful collection — a failed collection keeps the previous
 * values and marks the snapshot stale instead, so a collection outage can never
 * disguise a backlog as an empty store (issue #331).
 *
 * @param counts the persisted stock per state; states outside the query scope are
 * absent, states inside it are present even when zero
 * @param collectedAt when the successful collection behind these values happened,
 * never null
 * @param fresh whether these values come from a collection newer than the last
 * failure; a stale snapshot carries the last known values, never zeros
 * @param staleSince when the first collection after these values failed, or null
 * while fresh
 */
public record PersistedRunStateSnapshot(Map<RunState, Long> counts, Instant collectedAt, boolean fresh,
		Instant staleSince) {

	public PersistedRunStateSnapshot {
		Objects.requireNonNull(counts, "counts must not be null");
		Objects.requireNonNull(collectedAt, "collectedAt must not be null");
		counts = Map.copyOf(counts);
		counts.values().forEach(value -> Objects.requireNonNull(value, "counts must not contain null values"));
		if (fresh == (staleSince != null)) {
			throw new IllegalArgumentException("A snapshot is either fresh or stale since an instant");
		}
	}

	/**
	 * The persisted stock of one state, or zero when the state is inside the query
	 * scope but the store holds no runs of it.
	 * @param state the state to read, never null
	 * @return the persisted count, zero when absent
	 */
	public long countFor(RunState state) {
		return counts.getOrDefault(state, 0L);
	}

	/** The stock over every state present in the snapshot. */
	public long total() {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * Creates a fresh snapshot from a successful collection.
	 * @param counts the persisted stock per state, never null
	 * @param collectedAt when the collection succeeded, never null
	 * @return the fresh snapshot
	 */
	public static PersistedRunStateSnapshot fresh(Map<RunState, Long> counts, Instant collectedAt) {
		return new PersistedRunStateSnapshot(counts, collectedAt, true, null);
	}

	/**
	 * Creates a stale snapshot that carries the last known values of a previous
	 * collection.
	 * @param counts the last known stock per state, never null and never fabricated
	 * zeros
	 * @param collectedAt when that last successful collection happened, never null
	 * @param staleSince when the first collection after it failed, never null
	 * @return the stale snapshot
	 */
	public static PersistedRunStateSnapshot stale(Map<RunState, Long> counts, Instant collectedAt,
			Instant staleSince) {
		return new PersistedRunStateSnapshot(counts, collectedAt, false, staleSince);
	}

}
