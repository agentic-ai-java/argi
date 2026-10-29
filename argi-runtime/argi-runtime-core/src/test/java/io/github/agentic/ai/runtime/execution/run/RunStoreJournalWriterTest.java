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

import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.runtime.execution.definition.DefinitionKind;
import io.github.agentic.ai.runtime.execution.definition.DefinitionReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adapter behavior; backend atomicity and fencing remain in RunStoreContractTest. */
@Timeout(10)
class RunStoreJournalWriterTest {

	private static final String RUN_ID = "run-1";

	private final RunStore store = new MemoryRunStore();

	private RunSpec run;

	private RunLease lease;

	private RunStoreJournalWriter writer;

	@BeforeEach
	void setUp() {
		run = spec(RUN_ID, DefinitionKind.AGENT);
		store.submit(run, context(RUN_ID));
		lease = store.acquire(run.namespace(), RUN_ID, UUID.randomUUID(), Duration.ofMinutes(1));
		RunSnapshot running = store.commit(run.namespace(), RUN_ID, lease,
				new RunCommit(0, RunState.RUNNING, checkpoint("MODEL_READY"), List.of(), List.of(),
						List.of(entry("started", RunEntry.Kind.EVENT, "{}"))));
		writer = new RunStoreJournalWriter(store, lease, running);
	}

	@Test
	void appendRetainsCheckpointStateAndOtherRunData() {
		RunSnapshot before = writer.snapshot();
		RunEntry request = entry("turn#req", RunEntry.Kind.MODEL_CALL, "{\"hash\":\"request\"}");
		RunEntry delta = entry("turn#delta.0", RunEntry.Kind.EVENT, "{\"text\":\"hello\"}");

		writer.append(RUN_ID, List.of(request));
		writer.append(RUN_ID, List.of(delta));

		RunSnapshot after = writer.snapshot();
		assertThat(after.revision()).isEqualTo(before.revision() + 2);
		assertThat(after.checkpoint()).isEqualTo(before.checkpoint());
		assertThat(after.state()).isEqualTo(before.state());
		assertThat(after.tasks()).isEqualTo(before.tasks());
		assertThat(after.outbox()).isEqualTo(before.outbox());
		assertThat(after.journal()).containsOnlyKeys(request.id(), delta.id());
		assertThat(writer.find(RUN_ID, request.id())).contains(request);
		assertThat(writer.find(RUN_ID, "missing")).isEmpty();
		assertThat(store.find(run.namespace(), RUN_ID)).contains(after);
	}

	@Test
	void commitsCheckpointAndWholePlanInOneRevision() {
		RunSnapshot before = writer.snapshot();
		RunPayload checkpoint = checkpoint("TOOLS_PENDING");
		List<RunEntry> entries = List.of(entry("turn#resp", RunEntry.Kind.MODEL_CALL, "{}"),
				entry("turn#task.0", RunEntry.Kind.TASK_CALL, "{\"ordinal\":0}"),
				entry("turn#task.1", RunEntry.Kind.TASK_CALL, "{\"ordinal\":1}"));

		writer.commit(RUN_ID, checkpoint, entries);

		RunSnapshot after = store.find(run.namespace(), RUN_ID).orElseThrow();
		assertThat(after).isEqualTo(writer.snapshot());
		assertThat(after.revision()).isEqualTo(before.revision() + 1);
		assertThat(after.checkpoint()).isEqualTo(checkpoint);
		assertThat(after.journal().values()).containsExactlyInAnyOrderElementsOf(entries);
		assertThat(after.state()).isEqualTo(before.state());
		assertThat(after.tasks()).isEqualTo(before.tasks());
		assertThat(after.outbox()).isEqualTo(before.outbox());
	}

	@Test
	void conflictingBatchLeavesCheckpointAndCommittedViewUnchanged() {
		writer.append(RUN_ID, List.of(entry("turn#task.0", RunEntry.Kind.TASK_CALL, "{\"input\":1}")));
		RunSnapshot before = writer.snapshot();

		assertThatThrownBy(() -> writer.commit(RUN_ID, checkpoint("TOOLS_PENDING"),
				List.of(entry("turn#resp", RunEntry.Kind.MODEL_CALL, "{}"),
						entry("turn#task.0", RunEntry.Kind.TASK_CALL, "{\"input\":2}"))))
			.isInstanceOf(RunStoreConflictException.class);

		assertThat(writer.snapshot()).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
		assertThat(writer.find(RUN_ID, "turn#resp")).isEmpty();
	}

	@Test
	void externalProgressRequiresExplicitRebindingInsteadOfRetryingWithANewerRevision() {
		RunSnapshot before = writer.snapshot();
		RunEntry external = entry("external", RunEntry.Kind.EVENT, "{}");
		RunSnapshot progressed = store.commit(run.namespace(), RUN_ID, lease,
				new RunCommit(before.revision(), RunState.RUNNING, checkpoint("TOOLS_COMPLETED"), List.of(),
						List.of(external), List.of()));

		assertThatThrownBy(() -> writer.commit(RUN_ID, checkpoint("TOOLS_PENDING"),
				List.of(entry("turn#resp", RunEntry.Kind.MODEL_CALL, "{}"))))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(writer.snapshot()).isSameAs(before);
		assertThat(writer.find(RUN_ID, external.id())).isEmpty();
		assertThat(store.find(run.namespace(), RUN_ID)).contains(progressed);

		RunStoreJournalWriter rebound = new RunStoreJournalWriter(store, lease, progressed);
		assertThat(rebound.find(RUN_ID, external.id())).contains(external);
		rebound.append(RUN_ID, List.of(entry("next#req", RunEntry.Kind.MODEL_CALL, "{}")));
		assertThat(rebound.snapshot().revision()).isEqualTo(progressed.revision() + 1);
		assertThat(rebound.snapshot().checkpoint()).isEqualTo(progressed.checkpoint());
	}

	@Test
	void serializesConcurrentAppendsUsingEachSuccessfulRevision() throws Exception {
		long revision = writer.snapshot().revision();
		var executor = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Void>> writes = new ArrayList<>();
			for (int i = 0; i < 12; i++) {
				String id = "delta-" + i;
				writes.add(() -> {
					writer.append(RUN_ID, List.of(entry(id, RunEntry.Kind.EVENT, "{}")));
					return null;
				});
			}
			for (var result : executor.invokeAll(writes)) {
				result.get(5, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(writer.snapshot().revision()).isEqualTo(revision + 12);
		assertThat(writer.snapshot().journal()).hasSize(12);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(writer.snapshot());
	}

	@Test
	void acceptsBothGraphAndIndependentAgentRunDefinitions() {
		for (DefinitionKind kind : List.of(DefinitionKind.GRAPH, DefinitionKind.AGENT)) {
			String runId = kind.wireName() + "-run";
			RunSpec spec = spec(runId, kind);
			RunSnapshot admitted = store.submit(spec, context(runId));
			RunLease owned = store.acquire(spec.namespace(), runId, UUID.randomUUID(), Duration.ofMinutes(1));
			RunStoreJournalWriter bound = new RunStoreJournalWriter(store, owned, admitted);
			RunEntry entry = entry("turn#req", RunEntry.Kind.MODEL_CALL, "{}");

			bound.append(runId, List.of(entry));

			assertThat(bound.find(runId, entry.id())).contains(entry);
			assertThat(bound.snapshot().spec().definition().kind()).isEqualTo(kind);
			assertThat(store.find(spec.namespace(), runId)).contains(bound.snapshot());
		}
	}

	private static RunPayload checkpoint(String phase) {
		return new RunPayload(1, "test.checkpoint", "{\"phase\":\"" + phase + "\"}");
	}

	private static RunEntry entry(String id, RunEntry.Kind kind, String json) {
		return new RunEntry(1, id, kind, new RunPayload(1, "test.journal", json));
	}

	private static RunSpec spec(String runId, DefinitionKind kind) {
		return RunSpec.from(context(runId), "app", runId, "submission-" + runId,
				DefinitionReference.of(kind, "definition", "1"), new RunPayload(1, "test.input", "{}"));
	}

	private static TrustedExecutionContext context(String runId) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "user"))
			.runId(runId)
			.correlationId("trace")
			.build();
	}

}
