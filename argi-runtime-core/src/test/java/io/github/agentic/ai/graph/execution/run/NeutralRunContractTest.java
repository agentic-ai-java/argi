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

import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionReference;
import io.github.agentic.ai.graph.execution.definition.DefinitionRegistry;
import io.github.agentic.ai.graph.execution.definition.DefinitionResolutionException;
import io.github.agentic.ai.graph.execution.definition.ResolvedRunDefinitions;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionAdmission;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecordCodec;
import io.github.agentic.ai.graph.execution.definition.WorkerCapabilities;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.github.agentic.ai.graph.execution.run.RunStoreContractTest.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NeutralRunContractTest {

	private final RunSpecCodec codec = new RunSpecCodec();

	private final RunDefinitionRecordCodec definitionsCodec = new RunDefinitionRecordCodec();

	private final DefinitionReference tools = DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "tools", "1");

	private final DefinitionReference json = DefinitionReference.of(DefinitionKind.CODEC, "json", "1");

	private final DefinitionReference application = DefinitionReference.of(DefinitionKind.APPLICATION, "app", "1");

	@Test
	void rewritesActualPreExtractionFixturesByteForByte() throws Exception {
		String runJson = fixture("run-v1");
		RunSpec run = codec.decodeRun(runJson);
		assertThat(run.definition()).isEqualTo(DefinitionReference.of(DefinitionKind.GRAPH, "checkout", "1"));
		assertThat(run.inputHash()).isEqualTo("30e8b414b87ab4f292ce4d13a5b5ae73bfa400ccc8bb31e758d8d0a010db2573");
		assertThat(codec.encodeRun(run)).isEqualTo(runJson);
		String taskJson = fixture("task-v1");
		TaskSpec task = codec.decodeTask(taskJson);
		task.validateFor(run);
		assertThat(codec.encodeTask(task)).isEqualTo(taskJson);
		String recordJson = fixture("definitions-v1");
		assertThat(definitionsCodec.decode(recordJson).definition()).isEqualTo(run.definition());
		assertThat(definitionsCodec.encode(definitionsCodec.decode(recordJson))).isEqualTo(recordJson);
	}

	@ParameterizedTest
	@EnumSource(value = DefinitionKind.class, names = { "AGENT", "WORKFLOW", "GRAPH" })
	void executionKindsResolveAndCommitWithoutAnEngine(DefinitionKind kind) {
		TrustedExecutionContext trusted = context("run");
		RunSpec run = run(kind, trusted);
		assertThat(run.schemaVersion()).isEqualTo(2);
		String encoded = codec.encodeRun(run);
		assertThat(encoded).contains("\"definition\"").doesNotContain("graphId", "graphVersion");
		assertThat(codec.decodeRun(encoded)).isEqualTo(run);
		RunDefinitionRecord record = RunDefinitionRecord.forRun(run, tools, json, application);
		assertThat(definitionsCodec.decode(definitionsCodec.encode(record))).isEqualTo(record);
		DefinitionRegistry registry = DefinitionRegistry.builder()
			.register(run.definition(), "pinned-code")
			.register(tools, "tools")
			.register(json, "codec")
			.register(application, "application")
			.build();
		ResolvedRunDefinitions resolved = RunDefinitionAdmission.resolve(run, record, registry,
				new WorkerCapabilities(2,
						Set.of(kind, DefinitionKind.TOOL_CATALOG, DefinitionKind.CODEC, DefinitionKind.APPLICATION),
						Set.of(json)));
		assertThat(resolved.execution()).isEqualTo("pinned-code");
		MemoryRunStore store = new MemoryRunStore();
		store.submit(run, trusted);
		RunLease lease = store.acquire(run.namespace(), run.runId(), UUID.randomUUID(), Duration.ofSeconds(30));
		TaskSpec task = new TaskSpec(1, "task", "run", "local-action", "1", run.input(), trusted.toReference());
		store.commit(run.namespace(), run.runId(), lease, new RunCommit(0, RunState.RUNNING, null,
				List.of(new RunCommit.TaskUpdate(task, -1, RunState.PENDING)), List.of(), List.of()));
		store.commit(run.namespace(), run.runId(), lease, new RunCommit(1, RunState.RUNNING, null,
				List.of(new RunCommit.TaskUpdate(task, 0, RunState.RUNNING)), List.of(), List.of()));
		RunEntry event = new RunEntry(1, "completed", RunEntry.Kind.EVENT,
				new RunPayload(1, "result", "{\"ok\":true}"));
		RunSnapshot completed = store.commit(run.namespace(), run.runId(), lease,
				new RunCommit(2, RunState.SUCCEEDED, run.input(),
						List.of(new RunCommit.TaskUpdate(task, 1, RunState.SUCCEEDED)), List.of(event),
						List.of(event)));
		assertThat(completed.state()).isEqualTo(RunState.SUCCEEDED);
		assertThat(completed.outbox()).containsEntry("completed", event);
		assertThat(store.activeRun(run.namespace())).isEmpty();
		assertThat(store.submit(run, trusted)).isEqualTo(completed);
	}

	@Test
	void engineKindIsPartOfDedupAndDefinitionAdmission() {
		TrustedExecutionContext trusted = context("run");
		RunSpec agent = run(DefinitionKind.AGENT, trusted);
		RunSpec workflow = run(DefinitionKind.WORKFLOW, trusted);
		MemoryRunStore store = new MemoryRunStore();
		store.submit(agent, trusted);
		assertThatThrownBy(() -> store.submit(workflow, trusted)).isInstanceOf(RunStoreConflictException.class);
		RunDefinitionRecord record = RunDefinitionRecord.forRun(workflow, tools, json, application);
		assertThatThrownBy(() -> RunDefinitionAdmission.resolve(agent, record, DefinitionRegistry.empty(),
				new WorkerCapabilities(2, Set.of(), Set.of())))
			.isInstanceOf(DefinitionResolutionException.class);
		assertThatThrownBy(agent::graphId).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(agent::graphVersion).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(record::graph).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> record.reference(DefinitionKind.GRAPH)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsHybridAndUnsupportedSchemaData() throws Exception {
		String neutral = codec.encodeRun(run(DefinitionKind.AGENT, context("run")));
		List<String> invalid = List.of(neutral.replace("\"AGENT\"", "\"TOOL_CATALOG\""),
				neutral.replace("\"AGENT\"", "\"UNKNOWN\""),
				neutral.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
				neutral.replace("\"schemaVersion\":2", "\"schemaVersion\":4294967298"),
				neutral.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
				neutral.replace("\"definition\":", "\"graphId\":\"ambiguous\",\"definition\":"),
				fixture("run-v1").replace("\"graphId\":", "\"definition\":null,\"graphId\":"));
		for (String data : invalid) {
			assertThatThrownBy(() -> codec.decodeRun(data)).isInstanceOf(IllegalArgumentException.class);
		}
		RunDefinitionRecord record = RunDefinitionRecord.forRun(run(DefinitionKind.AGENT, context("run")), tools, json,
				application);
		String encoded = definitionsCodec.encode(record);
		assertThatThrownBy(() -> definitionsCodec.decode(encoded.replace("\"schemaVersion\":2", "\"schemaVersion\":1")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> definitionsCodec.decode(encoded.replace("\"schemaVersion\":2", "\"schemaVersion\":4294967297")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void graphDedupUsesDefinitionNotEnvelopeVersion() {
		TrustedExecutionContext trusted = context("run");
		RunSpec neutral = run(DefinitionKind.GRAPH, trusted);
		RunSpec legacy = RunSpec.from(trusted, "app", "session", "key", "shared", "1", neutral.input());
		MemoryRunStore store = new MemoryRunStore();
		RunSnapshot original = store.submit(legacy, trusted);
		assertThat(store.submit(neutral, trusted)).isEqualTo(original);
		assertThat(original.spec().schemaVersion()).isEqualTo(1);
	}

	private RunSpec run(DefinitionKind kind, TrustedExecutionContext trusted) {
		return RunSpec.from(trusted, "app", "session", "key", DefinitionReference.of(kind, "shared", "1"),
				new RunPayload(1, "input", "{}"));
	}

	private String fixture(String name) throws Exception {
		try (var stream = getClass().getResourceAsStream("/execution/" + name + ".json")) {
			return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8).strip();
		}
	}

}
