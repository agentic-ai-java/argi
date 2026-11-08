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
import io.github.agentic.ai.runtime.execution.run.RunCommit;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunSpec;
import io.github.agentic.ai.runtime.execution.run.RunState;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the bounded aggregate read of persisted run state stock
 * (issue #331). Backend adapters inherit this class with their own
 * {@link PersistedRunStateReader}; the reference {@code MemoryRunStore} runs it
 * directly. The lifecycle uses {@code PENDING} as the created state — the neutral
 * {@code RunState} vocabulary has no {@code CREATED}.
 */
public abstract class PersistedRunStateReaderContractTest {

	protected abstract PersistedRunStateReader reader();

	protected abstract MemoryRunStore store();

	@Test
	void stockFollowsTheRunLifecycle() {
		TrustedExecutionContext trusted = context("run-1");
		MemoryRunStore store = store();
		RunSpec run = run(trusted, "session-1", "key-1");
		store.submit(run, trusted);
		PersistedRunStateQuery query = PersistedRunStateQuery.allStates(run.namespace());

		PersistedRunStateSnapshot pending = reader().readCounts(query);
		assertThat(pending.countFor(RunState.PENDING)).isEqualTo(1);
		assertThat(pending.countFor(RunState.RUNNING)).isZero();
		assertThat(pending.countFor(RunState.SUCCEEDED)).isZero();

		var lease = store.acquire(run.namespace(), run.runId(), UUID.randomUUID(), Duration.ofSeconds(30));
		store.commit(run.namespace(), run.runId(), lease, new RunCommit(0, RunState.RUNNING, null, List.of(),
				List.of(), List.of()));
		PersistedRunStateSnapshot running = reader().readCounts(query);
		assertThat(running.countFor(RunState.RUNNING)).isEqualTo(1);
		assertThat(running.countFor(RunState.PENDING)).isZero();

		store.commit(run.namespace(), run.runId(), lease, new RunCommit(1, RunState.SUCCEEDED, run.input(),
				List.of(), List.of(), List.of()));
		PersistedRunStateSnapshot finished = reader().readCounts(query);
		assertThat(finished.countFor(RunState.SUCCEEDED)).isEqualTo(1);
		assertThat(finished.countFor(RunState.RUNNING)).isZero();
		assertThat(finished.total()).isEqualTo(1);
	}

	@Test
	void queryRestrictsTheReportedStates() {
		TrustedExecutionContext trusted = context("run-2");
		MemoryRunStore store = store();
		RunSpec run = run(trusted, "session-2", "key-2");
		store.submit(run, trusted);

		PersistedRunStateSnapshot restricted = reader().readCounts(new PersistedRunStateQuery(run.namespace(),
				EnumSet.of(RunState.RUNNING, RunState.SUCCEEDED)));
		assertThat(restricted.counts()).doesNotContainKey(RunState.PENDING);
		assertThat(restricted.countFor(RunState.RUNNING)).isZero();
		assertThat(restricted.countFor(RunState.SUCCEEDED)).isZero();
	}

	@Test
	void anEmptyStoreYieldsZerosForEveryRequestedState() {
		TrustedExecutionContext trusted = context("run-3");
		MemoryRunStore store = store();
		RunSpec run = run(trusted, "session-3", "key-3");

		PersistedRunStateSnapshot empty = reader().readCounts(PersistedRunStateQuery.allStates(run.namespace()));
		for (RunState state : RunState.values()) {
			assertThat(empty.countFor(state)).isZero();
		}
		assertThat(empty.total()).isZero();
		assertThat(empty.counts()).hasSize(RunState.values().length);
		assertThat(empty.fresh()).isTrue();
	}

	@Test
	void theNamespaceIsTheAggregationScope() {
		TrustedExecutionContext first = context("run-4");
		TrustedExecutionContext second = context("run-5");
		MemoryRunStore store = store();
		RunSpec one = run(first, "session-4", "key-4");
		RunSpec two = run(second, "session-5", "key-5");
		store.submit(one, first);
		store.submit(two, second);

		PersistedRunStateSnapshot scoped = reader().readCounts(PersistedRunStateQuery.allStates(one.namespace()));
		assertThat(scoped.total()).isEqualTo(1);
		PersistedRunStateSnapshot other = reader().readCounts(PersistedRunStateQuery.allStates(two.namespace()));
		assertThat(other.total()).isEqualTo(1);
	}

	protected RunSpec run(TrustedExecutionContext trusted, String sessionId, String submissionKey) {
		return RunSpec.from(trusted, "app", sessionId, submissionKey,
				DefinitionReference.of(DefinitionKind.AGENT, "shared", "1"), new RunPayload(1, "input", "{}"));
	}

	protected TrustedExecutionContext context(String runId) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "user"))
			.runId(runId)
			.correlationId("trace")
			.budget(new ExecutionBudget(Map.of("tokens", 100L)))
			.delegationScope(new DelegationScope(Set.of("read")))
			.build();
	}

}
