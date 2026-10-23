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

package io.github.agentic.ai.graph.execution.run;

import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.TrustedExecutionContextReference;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.agentic.ai.graph.execution.run.RunStoreContractTest.context;
import static io.github.agentic.ai.graph.execution.run.RunStoreContractTest.payload;
import static io.github.agentic.ai.graph.execution.run.RunStoreContractTest.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunDataContractTest {

	@Test
	void codecsRoundTripDataWithoutSerializingLiveClientsOrClosures() {
		RunSpec run = spec("run", "key", "{\"nested\":[{\"b\":1,\"a\":null}],\"flag\":true}");
		RunSpecCodec codec = new RunSpecCodec();
		String encoded = codec.encodeRun(run);
		assertThat(codec.decodeRun(encoded)).isEqualTo(run);
		assertThat(encoded).doesNotContain("@class", "Supplier", "Future", "password", "accessToken");
		assertThat(run.deadlineEpochMillis()).isNull();
		assertThat(run.budget()).isEqualTo(new ExecutionBudget(Map.of("tokens", 100L)));
		TrustedExecutionContext bounded = context("run", "tenant", "user", Instant.parse("2026-10-15T00:00:00Z"));
		RunSpec timed = RunSpec.from(bounded, "app", "session", "key", "graph", "1", run.input());
		assertThat(codec.decodeRun(codec.encodeRun(timed))).isEqualTo(timed);
		TaskSpec task = new TaskSpec(1, "task", run.runId(), "type", "version", run.input(),
				bounded.delegate("child", Set.of("read"), null, new ExecutionBudget(Map.of("tokens", 50L)))
					.toReference());
		assertThat(codec.decodeTask(codec.encodeTask(task))).isEqualTo(task);
		assertThat(task.inputHash()).isEqualTo(run.inputHash());
		assertThat(task.deadlineEpochMillis()).isEqualTo(bounded.toReference().deadlineEpochMillis());
		assertThat(task.budget().limits()).containsEntry("tokens", 50L);
		assertThat(codec.encodeRun(run)).isEqualTo(encoded);
	}

	@Test
	void rejectsMalformedUnknownMissingOrPolymorphicSpecFields() {
		RunSpecCodec codec = new RunSpecCodec();
		String json = codec.encodeRun(spec("run", "key", "{}"));
		List<String> invalid = List.of("", "null", "[]", "{", json + " {}",
				json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
				json.replace("\"schemaVersion\":1", "\"schemaVersion\":null"),
				json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
				json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
				json.replace("\"runId\":\"run\",", ""),
				json.substring(0, json.length() - 1) + ",\"supplier\":{\"@class\":\"java.lang.Runtime\"}}",
				json.replace("\"inputHash\":\"", "\"inputHash\":\"wrong"));
		for (String encoded : invalid) {
			assertThatThrownBy(() -> codec.decodeRun(encoded)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> codec.decodeRun(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.encodeRun(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.encodeTask(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.decodeTask("null")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.decodeTask("{}")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void canonicalHashIncludesSchemaTypeAndAllNestedInputs() {
		RunPayload first = payload("{\"b\":[{\"z\":null,\"a\":true}],\"a\":1}");
		RunPayload second = payload("{ \"a\" : 1, \"b\" : [ { \"a\" : true, \"z\" : null } ] }");
		assertThat(first).isEqualTo(second);
		assertThat(first.inputHash()).isEqualTo(second.inputHash()).hasSize(64);
		assertThat(new RunPayload(2, first.type(), first.json()).inputHash()).isNotEqualTo(first.inputHash());
		assertThat(new RunPayload(1, "different", first.json()).inputHash()).isNotEqualTo(first.inputHash());
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "null", "[]", "true", "1", "{", "{} {}", "{\"x\":1,\"x\":2}" })
	void rejectsNonObjectAmbiguousOrMalformedPayload(String json) {
		assertThatThrownBy(() -> payload(json)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validatesVersionIdentityHashAndRecordBoundaries() {
		RunSpec run = spec("run", "key", "{}");
		assertThatThrownBy(() -> new RunSpec(2, run.namespace(), run.runId(), "key", run.inputHash(), "g", "1",
				run.input(), run.context()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunSpec(1, run.namespace(), "other", "key", run.inputHash(), "g", "1", run.input(),
				run.context()))
			.isInstanceOf(IllegalArgumentException.class);
		RunNamespace otherTenant = new RunNamespace(new TenantReference("iam", "other"), "app", "session");
		assertThatThrownBy(() -> new RunSpec(1, otherTenant, run.runId(), "key", run.inputHash(), "g", "1", run.input(),
				run.context()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunPayload(0, "type", "{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunPayload(1, " ", "{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunPayload(1, null, "{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> payload(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunNamespace(run.namespace().tenant(), "", "session"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunNamespace(null, "app", "session")).isInstanceOf(NullPointerException.class);
		TrustedExecutionContextReference future = new TrustedExecutionContextReference(2, run.context().tenant(),
				run.context().principal(), "run", "trace", null, Map.of(), Set.of());
		assertThatThrownBy(() -> new TaskSpec(1, "task", "run", "type", "1", run.input(), future))
			.isInstanceOf(IllegalArgumentException.class);
		TaskSpec task = new TaskSpec(1, "task", "run", "type", "1", run.input(), run.context());
		assertThatThrownBy(() -> new TaskRecord(task, -1, RunState.PENDING))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunSnapshot(run, -1, RunState.PENDING, null, Map.of(), Map.of(), Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunCommit(-1, RunState.PENDING, null, List.of(), List.of(), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunCommit.TaskUpdate(task, -2, RunState.PENDING))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunLease(run.namespace(), UUID.randomUUID(), 0, Instant.now()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunEntry(2, "id", RunEntry.Kind.EVENT, run.input()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void taskCannotWidenIdentityDeadlinePermissionsOrBudgets() {
		TrustedExecutionContext trusted = context("run", "tenant", "user", Instant.parse("2026-10-15T00:00:00Z"));
		RunSpec run = RunSpec.from(trusted, "app", "session", "key", "g", "1", payload("{}"));
		TrustedExecutionContextReference parent = trusted.toReference();
		List<TrustedExecutionContextReference> invalid = List.of(
				ref(parent, new TenantReference("iam", "other"), parent.principal(), "trace",
						parent.deadlineEpochMillis(), Map.of("tokens", 50L), Set.of()),
				ref(parent, parent.tenant(), new PrincipalReference("iam", "other"), "trace",
						parent.deadlineEpochMillis(), Map.of("tokens", 50L), Set.of()),
				ref(parent, parent.tenant(), parent.principal(), "other", parent.deadlineEpochMillis(),
						Map.of("tokens", 50L), Set.of()),
				ref(parent, parent.tenant(), parent.principal(), "trace", null, Map.of("tokens", 50L), Set.of()),
				ref(parent, parent.tenant(), parent.principal(), "trace", parent.deadlineEpochMillis() + 1,
						Map.of("tokens", 50L), Set.of()),
				ref(parent, parent.tenant(), parent.principal(), "trace", parent.deadlineEpochMillis(),
						Map.of("tokens", 101L), Set.of()),
				ref(parent, parent.tenant(), parent.principal(), "trace", parent.deadlineEpochMillis(), Map.of(),
						Set.of()),
				ref(parent, parent.tenant(), parent.principal(), "trace", parent.deadlineEpochMillis(),
						Map.of("tokens", 50L), Set.of("write")));
		for (TrustedExecutionContextReference context : invalid) {
			TaskSpec task = new TaskSpec(1, "task", "run", "type", "1", run.input(), context);
			assertThatThrownBy(() -> task.validateFor(run)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(
				() -> new TaskSpec(1, "task", "other-run", "type", "1", run.input(), parent).validateFor(run))
			.isInstanceOf(IllegalArgumentException.class);
		new TaskSpec(1, "task", "run", "type", "1", run.input(),
				trusted
					.delegate("child", Set.of(), trusted.deadline().orElseThrow().minusSeconds(1),
							new ExecutionBudget(Map.of("tokens", 50L)))
					.toReference())
			.validateFor(run);
	}

	@ParameterizedTest
	@MethodSource("statePairs")
	void fullTransitionMatrix(RunState previous, RunState next) {
		Set<RunState> allowed = switch (previous) {
			case PENDING -> Set.of(RunState.PENDING, RunState.RUNNING, RunState.CANCELLED, RunState.TIMED_OUT);
			case RUNNING -> Set.of(RunState.RUNNING, RunState.WAITING, RunState.SUCCEEDED, RunState.FAILED,
					RunState.CANCELLED, RunState.TIMED_OUT);
			case WAITING ->
				Set.of(RunState.WAITING, RunState.RUNNING, RunState.FAILED, RunState.CANCELLED, RunState.TIMED_OUT);
			default -> Set.of();
		};
		if (allowed.contains(next)) {
			previous.requireTransitionTo(next);
		}
		else {
			assertThatThrownBy(() -> previous.requireTransitionTo(next)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(previous.terminal()).isEqualTo(allowed.isEmpty());
	}

	static Stream<Arguments> statePairs() {
		return Stream.of(RunState.values())
			.flatMap(previous -> Stream.of(RunState.values()).map(next -> Arguments.of(previous, next)));
	}

	@Test
	void everyDurableGuaranteeIsRequired() {
		for (int mask = 0; mask < 64; mask++) {
			RunStoreCapabilities capabilities = new RunStoreCapabilities((mask & 1) != 0, (mask & 2) != 0,
					(mask & 4) != 0, (mask & 8) != 0, (mask & 16) != 0, (mask & 32) != 0);
			assertThat(capabilities.supportsDurableMode()).isEqualTo(mask == 63);
			if (mask == 63) {
				capabilities.requireDurableMode();
			}
			else {
				assertThatThrownBy(capabilities::requireDurableMode).isInstanceOf(IllegalStateException.class);
			}
		}
	}

	@Test
	void validatesLeaseTtlBeforeChangingOwnership() {
		MemoryRunStore store = new MemoryRunStore();
		RunSpec run = spec("run", "key", "{}");
		store.submit(run, context("run"));
		List<Duration> invalid = List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofDays(2));
		for (Duration ttl : invalid) {
			assertThatThrownBy(() -> store.acquire(run.namespace(), run.runId(), UUID.randomUUID(), ttl))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> store.acquire(run.namespace(), "run", UUID.randomUUID(), null))
			.isInstanceOf(NullPointerException.class);
		RunLease lease = store.acquire(run.namespace(), "run", UUID.randomUUID(), Duration.ofMillis(1));
		assertThat(lease.fencingToken()).isEqualTo(1);
	}

	private static TrustedExecutionContextReference ref(TrustedExecutionContextReference parent, TenantReference tenant,
			PrincipalReference principal, String correlation, Long deadline, Map<String, Long> budget,
			Set<String> permissions) {
		return new TrustedExecutionContextReference(1, tenant, principal, "child", correlation, deadline, budget,
				permissions);
	}

}
