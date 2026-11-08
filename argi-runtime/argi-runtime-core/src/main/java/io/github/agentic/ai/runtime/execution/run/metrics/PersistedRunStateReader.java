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

/**
 * The authorized aggregate read of persisted run state stock (issue #331).
 *
 * <p>
 * The contract is intentionally aggregate-only: an implementation answers one
 * {@link PersistedRunStateQuery} with per-state counts computed by its own storage
 * (a {@code GROUP BY} for a SQL adapter, counters for a Redis adapter) and never
 * hands out row lists for the caller to count. Storage technology and authorization
 * both live on the adapter side; the core never scans a table nor opens a database
 * connection. Reads are admission-gated by whatever live authorization the adapter
 * requires, in the same spirit as {@code RunStore}: a persisted identity reference
 * alone cannot admit a read.
 *
 * <p>
 * Implementations must be safe for concurrent callers; the freshness gating on top
 * of this contract is provided by
 * {@link FreshnessGatedRunStateAggregator}.
 */
public interface PersistedRunStateReader {

	/**
	 * Reads the persisted stock for the query scope. The returned snapshot is fresh:
	 * staleness handling belongs to the aggregator consuming this contract.
	 * Implementations must translate checked storage failures into unchecked ones:
	 * the freshness gate treats any thrown {@link RuntimeException} as a failed
	 * collection and keeps its last known values instead of treating the failure as
	 * an empty store.
	 * @param query the scope and states of interest, never null
	 * @return the collected snapshot, never null
	 */
	PersistedRunStateSnapshot readCounts(PersistedRunStateQuery query);

}
