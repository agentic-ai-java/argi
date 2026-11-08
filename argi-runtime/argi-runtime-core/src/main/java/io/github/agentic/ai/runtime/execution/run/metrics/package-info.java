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
/**
 * Persisted run state gauges with collection freshness (issue #331).
 *
 * <p>
 * Three kinds of measurement must not be confused. A <em>process transition
 * counter</em> counts transitions this JVM performed and dies with the process
 * (issue #317's scope). An <em>audit history</em> is the persisted journal of every
 * transition. The <em>persisted state gauge</em> is what this package delivers: the
 * current stock of runs per {@link io.github.agentic.ai.runtime.execution.run.RunState}
 * as read from an authorized persistence, with the time it was collected and an
 * explicit staleness marker. A restart must never present process counters as
 * persisted stock — a freshly created aggregator has no snapshot at all until its
 * first successful collection.
 *
 * <p>
 * The reading contract {@link io.github.agentic.ai.runtime.execution.run.metrics.PersistedRunStateReader}
 * returns aggregates, never row lists: implementations are expected to answer with a
 * bounded {@code GROUP BY}-style result from their own storage (SQL, Redis, ...),
 * which lives on the adapter side, never inside the core. The {@code namespace} of a
 * query is the aggregation scope; run and tenant identifiers are scope parameters,
 * never metric dimensions (bounded-cardinality labels per issue #318).
 *
 * <p>
 * <b>Multi-replica semantics.</b> Two instances sharing one store observe the same
 * snapshot: adding their counts together would double every run, so a shared-store
 * global view must not be summed. A sharded deployment instead lets every instance
 * own its {@code namespace} and declares the sum across namespaces as its aggregate —
 * that declaration belongs to the consumer, not to this package.
 */
package io.github.agentic.ai.runtime.execution.run.metrics;
