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

import io.github.agentic.ai.runtime.execution.definition.DefinitionKind;
import io.github.agentic.ai.runtime.execution.definition.DefinitionReference;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.run.MemoryRunStore;
import io.github.agentic.ai.runtime.execution.run.RunNamespace;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunSpec;
import io.github.agentic.ai.runtime.execution.run.RunState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Freshness gating of the persisted state gauge (issue #331): success advances the
 * collection time, failure keeps the last known values and marks them stale, a fresh
 * process has no snapshot at all, and the multi-replica semantics of shared versus
 * sharded stores are observable.
 */
class FreshnessGatedRunStateAggregatorTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));

	private final MemoryRunStore store = new MemoryRunStore(clock);

	private final SwitchableReader reader = new SwitchableReader(store);

	@Test
	void aFreshAggregatorHasNoSnapshotUntilItsFirstCollection() {
		FreshnessGatedRunStateAggregator aggregator = aggregator("session-1", "key-1");

		assertThat(aggregator.current()).isEmpty();
	}

	@Test
	void successfulRefreshProducesAFreshSnapshotWithTheCollectionTime() throws Exception {
		TrustedExecutionContext trusted = context("run-1");
		RunSpec run = run(trusted, "session-1", "key-1");
		store.submit(run, trusted);
		FreshnessGatedRunStateAggregator aggregator = aggregator("session-1", "key-1");

		PersistedRunStateSnapshot snapshot = aggregator.refresh();

		assertThat(snapshot.fresh()).isTrue();
		assertThat(snapshot.staleSince()).isNull();
		assertThat(snapshot.collectedAt()).isEqualTo(clock.instant());
		assertThat(snapshot.countFor(RunState.PENDING)).isEqualTo(1);
		assertThat(aggregator.current()).contains(snapshot);
	}

	@Test
	void aFailedRefreshKeepsTheLastValuesAndMarksThemStale() throws Exception {
		TrustedExecutionContext trusted = context("run-1");
		RunSpec run = run(trusted, "session-1", "key-1");
		store.submit(run, trusted);
		FreshnessGatedRunStateAggregator aggregator = aggregator("session-1", "key-1");
		aggregator.refresh();
		PersistedRunStateSnapshot lastKnown = aggregator.current().orElseThrow();

		clock.advanceSeconds(30);
		reader.fail();
		PersistedRunStateSnapshot stale = aggregator.refresh();

		assertThat(stale.fresh()).isFalse();
		assertThat(stale.staleSince()).isEqualTo(clock.instant());
		assertThat(stale.collectedAt()).isEqualTo(lastKnown.collectedAt());
		// A collection outage must never disguise the backlog as an empty store.
		assertThat(stale.countFor(RunState.PENDING)).isEqualTo(1);
		assertThat(stale.total()).isEqualTo(lastKnown.total());
		// current() reflects the stale mark too.
		assertThat(aggregator.current().orElseThrow().fresh()).isFalse();
		// The failure instant is the first failure of the outage, not the latest one.
		Instant firstFailure = stale.staleSince();
		clock.advanceSeconds(30);
		assertThat(aggregator.refresh().staleSince()).isEqualTo(firstFailure);
	}

	@Test
	void recoveryRestoresFreshnessWithANewCollectionTime() throws Exception {
		TrustedExecutionContext trusted = context("run-1");
		RunSpec run = run(trusted, "session-1", "key-1");
		store.submit(run, trusted);
		FreshnessGatedRunStateAggregator aggregator = aggregator("session-1", "key-1");
		aggregator.refresh();
		reader.fail();
		aggregator.refresh();
		clock.advanceSeconds(60);
		reader.recover();

		PersistedRunStateSnapshot recovered = aggregator.refresh();

		assertThat(recovered.fresh()).isTrue();
		assertThat(recovered.staleSince()).isNull();
		assertThat(recovered.collectedAt()).isEqualTo(clock.instant());
	}

	@Test
	void aFirstCollectionFailurePropagatesInsteadOfFabricatingAnEmptySnapshot() {
		FreshnessGatedRunStateAggregator aggregator = aggregator("session-1", "key-1");
		reader.fail();

		assertThatThrownBy(() -> aggregator.refresh()).isInstanceOf(IllegalStateException.class);
		assertThat(aggregator.current()).isEmpty();
	}

	@Test
	void aRestartedProcessRebuildsItsSnapshotFromTheStoreNotFromMemory() throws Exception {
		TrustedExecutionContext trusted = context("run-1");
		RunSpec run = run(trusted, "session-1", "key-1");
		store.submit(run, trusted);
		// The "old" process had a snapshot; the "new" process starts empty.
		FreshnessGatedRunStateAggregator before = aggregator("session-1", "key-1");
		before.refresh();
		FreshnessGatedRunStateAggregator after = aggregator("session-1", "key-1");

		assertThat(after.current()).isEmpty();
		after.refresh();
		assertThat(after.current().orElseThrow().countFor(RunState.PENDING)).isEqualTo(1);
		// The stock came from the persistence, so both processes observe the same
		// values — process-local counters are irrelevant to this gauge.
		assertThat(after.current().orElseThrow().counts())
			.isEqualTo(before.current().orElseThrow().counts());
	}

	@Test
	void instancesSharingOneStoreObserveTheSameSnapshotAndMustNotBeSummed() throws Exception {
		TrustedExecutionContext trusted = context("run-1");
		RunSpec run = run(trusted, "session-1", "key-1");
		store.submit(run, trusted);
		FreshnessGatedRunStateAggregator first = aggregator("session-1", "key-1");
		FreshnessGatedRunStateAggregator second = aggregator("session-1", "key-1");

		first.refresh();
		second.refresh();

		// Shared store: the same stock seen twice. Summing the two snapshots would
		// report two runs where the store holds one.
		assertThat(first.current().orElseThrow().counts()).isEqualTo(second.current().orElseThrow().counts());
		assertThat(first.current().orElseThrow().total()).isEqualTo(1);
	}

	@Test
	void shardedNamespacesDeclareTheirScopeAndSumAcrossShards() throws Exception {
		TrustedExecutionContext first = context("run-1");
		TrustedExecutionContext second = context("run-2");
		RunSpec shardOne = run(first, "session-1", "key-1");
		RunSpec shardTwo = run(second, "session-2", "key-2");
		store.submit(shardOne, first);
		store.submit(shardTwo, second);
		FreshnessGatedRunStateAggregator one = aggregator("session-1", "key-1");
		FreshnessGatedRunStateAggregator two = aggregator("session-2", "key-2");

		one.refresh();
		two.refresh();

		// Sharded mode: each instance owns a namespace and the consumer declares the
		// cross-namespace sum as the global aggregate.
		long global = one.current().orElseThrow().total() + two.current().orElseThrow().total();
		assertThat(global).isEqualTo(2);
	}

	private RunSpec run(TrustedExecutionContext trusted, String sessionId, String submissionKey) {
		return RunSpec.from(trusted, "app", sessionId, submissionKey,
				DefinitionReference.of(DefinitionKind.AGENT, "shared", "1"), new RunPayload(1, "input", "{}"));
	}

	private FreshnessGatedRunStateAggregator aggregator(String sessionId, String submissionKey) {
		TrustedExecutionContext trusted = context("scope-" + sessionId);
		RunNamespace namespace = RunSpec.from(trusted, "app", sessionId, submissionKey,
				DefinitionReference.of(DefinitionKind.AGENT, "shared", "1"),
				new RunPayload(1, "input", "{}")).namespace();
		return new FreshnessGatedRunStateAggregator(reader,
				new PersistedRunStateQuery(namespace, EnumSet.allOf(RunState.class)), clock);
	}

	/** Delegates to the store until the test flips the switch to simulate an outage. */
	private static final class SwitchableReader implements PersistedRunStateReader {

		private final PersistedRunStateReader delegate;

		private final AtomicReference<PersistedRunStateReader> active = new AtomicReference<>();

		private SwitchableReader(PersistedRunStateReader delegate) {
			this.delegate = delegate;
			this.active.set(delegate);
		}

		private void fail() {
			active.set(query -> {
				throw new IllegalStateException("storage unavailable");
			});
		}

		private void recover() {
			active.set(delegate);
		}

		@Override
		public PersistedRunStateSnapshot readCounts(PersistedRunStateQuery query) {
			return active.get().readCounts(query);
		}

	}

	private TrustedExecutionContext context(String runId) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "user"))
			.runId(runId)
			.correlationId("trace")
			.budget(new ExecutionBudget(Map.of("tokens", 100L)))
			.delegationScope(new DelegationScope(Set.of("read")))
			.build();
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advanceSeconds(int seconds) {
			now = now.plusSeconds(seconds);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
