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

package io.github.agentic.ai.graph.execution.definition;

import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunStoreCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.APP_V5;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.CODEC_V2;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.GRAPH_V1;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.GRAPH_V2;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.TOOLS_V3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunDefinitionAdmissionTest {

	private static final RunStoreCapabilities DURABLE = new RunStoreCapabilities(true, true, true, true, true, true);

	@Test
	void upgradedDefinitionStillResolvesTheVersionTheRunWasAdmittedWith() {
		RunSpec run = DefinitionTestSupport.run("run-1", "checkout", "1");
		RunDefinitionRecord record = RunDefinitionRecord.forRun(run, TOOLS_V3, CODEC_V2, APP_V5);

		ResolvedRunDefinitions resolved = RunDefinitionAdmission.resolve(run, record,
				DefinitionTestSupport.registryWithAllVersions(), DefinitionTestSupport.fullWorker());

		assertThat(resolved.graph()).isEqualTo("graph-v1");
		assertThat(resolved.graph()).isNotEqualTo("graph-v2");
		assertThat(resolved.graphReference()).isEqualTo(GRAPH_V1);
		assertThat(resolved.definition(DefinitionKind.GRAPH, String.class)).isEqualTo("graph-v1");
		assertThat(resolved.toolCatalog()).isEqualTo("tools-v3");
		assertThat(resolved.codec()).isEqualTo("codec-v2");
		assertThat(resolved.application()).isEqualTo("app-v5");
		assertThat(resolved.codecReference()).isEqualTo(CODEC_V2);
	}

	@Test
	void aToolAddedLaterNeverChangesWhatAnOlderRunResolves() {
		DefinitionReference catalogV4 = DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "main", "4");
		DefinitionRegistry registry = DefinitionRegistry.builder()
			.register(GRAPH_V1, "graph-v1")
			.register(TOOLS_V3, List.of("search"))
			.register(catalogV4, List.of("search", "dynamic-weather"))
			.register(CODEC_V2, "codec-v2")
			.register(APP_V5, "app-v5")
			.build();

		assertThat(RunDefinitionAdmission
			.resolve(RunDefinitionRecord.of(GRAPH_V1, TOOLS_V3, CODEC_V2, APP_V5), registry,
					DefinitionTestSupport.fullWorker())
			.toolCatalog()).isEqualTo(List.of("search"));
		assertThat(RunDefinitionAdmission
			.resolve(RunDefinitionRecord.of(GRAPH_V1, catalogV4, CODEC_V2, APP_V5), registry,
					DefinitionTestSupport.fullWorker())
			.toolCatalog()).isEqualTo(List.of("search", "dynamic-weather"));
	}

	@Test
	void missingRecordedVersionFailsBeforeAnyToolDispatches() {
		RunSpec run = DefinitionTestSupport.run("run-1", "checkout", "1");
		RunDefinitionRecord record = RunDefinitionRecord.forRun(run, TOOLS_V3, CODEC_V2, APP_V5);
		DefinitionRegistry registry = DefinitionRegistry.builder().register(GRAPH_V2, "graph-v2").build();
		DispatchingWorker worker = new DispatchingWorker();

		assertThatThrownBy(
				() -> worker.dispatch(RunDefinitionAdmission.resolve(run, record, registry, DefinitionTestSupport.fullWorker())))
			.isInstanceOfSatisfying(DefinitionResolutionException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.NOT_REGISTERED);
				assertThat(ex.reference()).isEqualTo(GRAPH_V1);
			});
		assertThat(worker.dispatched).isEmpty();
	}

	@Test
	void recordMustDescribeTheAdmittedRun() {
		RunSpec run = DefinitionTestSupport.run("run-1", "checkout", "2");
		RunDefinitionRecord record = DefinitionTestSupport.record();

		assertThatThrownBy(() -> RunDefinitionAdmission.resolve(run, record, DefinitionTestSupport.registryWithAllVersions(),
				DefinitionTestSupport.fullWorker()))
			.isInstanceOfSatisfying(DefinitionResolutionException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.RECORD_MISMATCH);
				assertThat(ex.reference()).isEqualTo(GRAPH_V2);
				assertThat(ex.details()).containsExactly(GRAPH_V1.display());
			});
	}

	@Test
	void unsupportedWorkerKindFailsWithoutConsultingTheResolver() {
		RunSpec run = DefinitionTestSupport.run("run-1", "checkout", "1");
		RunDefinitionRecord record = RunDefinitionRecord.forRun(run, TOOLS_V3, CODEC_V2, APP_V5);
		List<DefinitionReference> requested = new ArrayList<>();
		DefinitionResolver resolver = (reference) -> {
			requested.add(reference);
			return Optional.of("value");
		};

		assertThatThrownBy(() -> RunDefinitionAdmission.resolve(record, resolver,
				DefinitionTestSupport.worker(DefinitionKind.GRAPH)))
			.isInstanceOfSatisfying(DefinitionResolutionException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.KIND_UNSUPPORTED);
				assertThat(ex.reference()).isEqualTo(TOOLS_V3);
				assertThat(ex.details()).containsExactly("graph");
			});
		assertThat(requested).containsExactly(GRAPH_V1);
	}

	@Test
	void unreadableEnvelopeVersionFailsStructurally() {
		RunDefinitionRecord record = new RunDefinitionRecord(2, GRAPH_V1, TOOLS_V3, CODEC_V2, APP_V5);

		assertThatThrownBy(() -> RunDefinitionAdmission.resolve(record, DefinitionTestSupport.registryWithAllVersions(),
				DefinitionTestSupport.fullWorker()))
			.isInstanceOfSatisfying(CodecIncompatibilityException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.ENVELOPE_VERSION_UNSUPPORTED);
				assertThat(ex.recorded()).isEqualTo("2");
			});
	}

	@Test
	void unsupportedDataCodecFailsStructurally() {
		RunDefinitionRecord record = DefinitionTestSupport.record();
		WorkerCapabilities worker = new WorkerCapabilities(1, Set.of(DefinitionKind.values()),
				Set.of(DefinitionReference.of(DefinitionKind.CODEC, "argi-json", "3")));

		assertThatThrownBy(() -> RunDefinitionAdmission.resolve(record, DefinitionTestSupport.registryWithAllVersions(), worker))
			.isInstanceOfSatisfying(CodecIncompatibilityException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.CODEC_VERSION_UNSUPPORTED);
				assertThat(ex.subject()).isEqualTo(CODEC_V2.display());
				assertThat(ex.recorded()).isEqualTo("2");
				assertThat(ex.supported()).containsExactly("codec[argi-json@3]");
			});
	}

	@Test
	void workerCapabilitiesRejectUnknownAndIllTypedDeclarations() {
		assertThatThrownBy(() -> new WorkerCapabilities(0, Set.of(DefinitionKind.GRAPH), Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkerCapabilities(1, Set.of(DefinitionKind.GRAPH), Set.of(GRAPH_V1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be a codec reference");
		assertThatThrownBy(() -> new WorkerCapabilities(1, Set.of(DefinitionKind.GRAPH), Set.of(CODEC_V2))
			.requireCodec(TOOLS_V3)).isInstanceOf(IllegalArgumentException.class);
		assertThat(DefinitionTestSupport.fullWorker().supportsKind(DefinitionKind.CODEC)).isTrue();
		assertThat(DefinitionTestSupport.fullWorker().supportsCodec(CODEC_V2)).isTrue();
		assertThat(DefinitionTestSupport.fullWorker().supportsCodec(GRAPH_V1)).isFalse();
	}

	@Test
	void resolvedDefinitionsRejectATypeMismatch() {
		ResolvedRunDefinitions resolved = RunDefinitionAdmission.resolve(DefinitionTestSupport.record(),
				DefinitionTestSupport.registryWithAllVersions(), DefinitionTestSupport.fullWorker());

		assertThatThrownBy(() -> resolved.definition(DefinitionKind.TOOL_CATALOG, Integer.class))
			.isInstanceOfSatisfying(DefinitionResolutionException.class,
					(ex) -> assertThat(ex.code()).isEqualTo(DefinitionFailureCode.TYPE_MISMATCH));
	}

	@Test
	void durableModeRequiresADurableStoreAndRecoverableComponents() {
		assertThatThrownBy(() -> RunDefinitionAdmission.requireDurableMode(new RunStoreCapabilities(false, true, true, true,
				true, true), DefinitionTestSupport.recoverableComponents()))
			.isInstanceOf(IllegalStateException.class);

		RunDefinitionAdmission.requireDurableMode(DURABLE, DefinitionTestSupport.recoverableComponents());
		RunDefinitionAdmission.requireRecoverableComponents(DefinitionTestSupport.recoverableComponents());
	}

	@Test
	void instanceBoundComponentsRejectDurableMode() {
		List<RuntimeComponentDeclaration> components = new ArrayList<>(DefinitionTestSupport.recoverableComponents());
		components.set(3, RuntimeComponentDeclaration.instanceBound(RuntimeComponentRole.BACKGROUND_TOOL, "in-jvm-tasks"));

		assertThatThrownBy(() -> RunDefinitionAdmission.requireDurableMode(DURABLE, components))
			.isInstanceOfSatisfying(RecoveryCapabilityException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.COMPONENT_NOT_RECOVERABLE);
				assertThat(ex.offending()).extracting(RuntimeComponentDeclaration::display)
					.containsExactly("background_tool[in-jvm-tasks]=INSTANCE_BOUND");
				assertThat(ex.missingRoles()).isEmpty();
			});
	}

	@Test
	void undeclaredRolesAreMissingCapabilities() {
		List<RuntimeComponentDeclaration> components = List.of(
				RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.CUSTOM_NODE),
				RuntimeComponentDeclaration.dataOnly(RuntimeComponentRole.STORE, "jdbc-store"));

		assertThatThrownBy(() -> RunDefinitionAdmission.requireDurableMode(DURABLE, components))
			.isInstanceOfSatisfying(RecoveryCapabilityException.class, (ex) -> {
				assertThat(ex.missingRoles()).containsExactly(RuntimeComponentRole.CHAT_MEMORY,
						RuntimeComponentRole.BACKGROUND_TOOL);
				assertThat(ex.offending()).isEmpty();
			});
	}

	@Test
	void declarationsMustBeUniqueAndNonNull() {
		List<RuntimeComponentDeclaration> duplicated = new ArrayList<>(DefinitionTestSupport.recoverableComponents());
		duplicated.add(RuntimeComponentDeclaration.dataOnly(RuntimeComponentRole.STORE, "other-store"));

		assertThatThrownBy(() -> RunDefinitionAdmission.requireRecoverableComponents(duplicated))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("declared more than once");
		assertThatThrownBy(() -> RunDefinitionAdmission.requireRecoverableComponents(List.of((RuntimeComponentDeclaration) null)))
			.isInstanceOf(NullPointerException.class);
		assertThat(RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.STORE).recoverable()).isTrue();
		assertThat(RuntimeComponentDeclaration.instanceBound(RuntimeComponentRole.STORE, "memory").recoverable()).isFalse();
	}

	/** A worker only dispatches once it holds fully resolved definitions. */
	private static final class DispatchingWorker {

		private final List<String> dispatched = new ArrayList<>();

		void dispatch(ResolvedRunDefinitions definitions) {
			this.dispatched.add("tool:" + definitions.toolCatalog());
		}

	}

}
