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

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The freshness gate between a {@link PersistedRunStateReader} and metric consumers
 * (issue #331). Every {@link #refresh()} attempts one collection: success advances
 * the snapshot and its {@code collectedAt}; failure keeps the last known values and
 * marks them stale, so a collection outage can never turn a real backlog into an
 * apparently empty store. A freshly built aggregator has no snapshot at all —
 * persisted stock exists only in the persistence, and process restarts must not
 * present local counters or zeros as persisted state.
 *
 * <p>
 * Two instances sharing one store observe the same snapshot; summing them would
 * double every run. A sharded deployment lets each instance own its namespace and
 * declares the cross-namespace sum as its aggregate.
 */
public final class FreshnessGatedRunStateAggregator {

	private final PersistedRunStateReader reader;

	private final PersistedRunStateQuery query;

	private final Clock clock;

	private PersistedRunStateSnapshot snapshot;

	private Instant staleSince;

	public FreshnessGatedRunStateAggregator(PersistedRunStateReader reader, PersistedRunStateQuery query, Clock clock) {
		this.reader = Objects.requireNonNull(reader, "reader must not be null");
		this.query = Objects.requireNonNull(query, "query must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	/**
	 * The last collected values, empty until the first successful collection. A stale
	 * snapshot still carries the last known values — consumers read
	 * {@link PersistedRunStateSnapshot#fresh()} and
	 * {@link PersistedRunStateSnapshot#staleSince()} to decide how much to trust them.
	 * @return the current snapshot, empty before the first successful collection
	 */
	public synchronized Optional<PersistedRunStateSnapshot> current() {
		return Optional.ofNullable(snapshot);
	}

	/**
	 * Attempts one collection. On success the snapshot and its collection time
	 * advance and freshness is restored. On failure the last known values are kept
	 * and marked stale since the first failure of the current outage; with no last
	 * known values there is nothing legitimate to report, so the failure propagates
	 * instead of fabricating an empty snapshot.
	 * @return the snapshot after the attempt: fresh on success, stale after a failure
	 * that had a predecessor
	 */
	public synchronized PersistedRunStateSnapshot refresh() {
		Instant now = clock.instant();
		try {
			PersistedRunStateSnapshot collected = reader.readCounts(query);
			snapshot = PersistedRunStateSnapshot.fresh(collected.counts(), now);
			staleSince = null;
			return snapshot;
		}
		catch (RuntimeException failure) {
			if (snapshot == null) {
				throw failure;
			}
			if (staleSince == null) {
				staleSince = now;
			}
			snapshot = PersistedRunStateSnapshot.stale(snapshot.counts(), snapshot.collectedAt(), staleSince);
			return snapshot;
		}
	}

}
