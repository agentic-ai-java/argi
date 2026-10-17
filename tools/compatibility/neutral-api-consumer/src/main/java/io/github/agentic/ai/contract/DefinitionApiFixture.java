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
package io.github.agentic.ai.contract;

import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionReference;
import io.github.agentic.ai.graph.execution.definition.DefinitionRegistry;
import io.github.agentic.ai.graph.execution.definition.ResolvedRunDefinitions;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionAdmission;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecordCodec;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentDeclaration;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentRole;
import io.github.agentic.ai.graph.execution.definition.WorkerCapabilities;
import io.github.agentic.ai.graph.execution.run.MemoryRunStore;
import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunStore;

import java.util.List;
import java.util.Set;

/** Compile the opt-in definition contract as a consumer without a model SDK or Extensions. */
final class DefinitionApiFixture {

	private DefinitionApiFixture() {
	}

	static ResolvedRunDefinitions compileOnly(RunSpec admitted) {
		RunStore store = new MemoryRunStore();
		RunDefinitionRecordCodec codec = new RunDefinitionRecordCodec();
		RunDefinitionRecord record = RunDefinitionRecord.forRun(admitted,
				DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "tools", "3"),
				DefinitionReference.of(DefinitionKind.CODEC, "argi-json", "2"),
				DefinitionReference.of(DefinitionKind.APPLICATION, "application", "1"));
		DefinitionRegistry registry = DefinitionRegistry.builder()
			.register(record.graph(), "graph")
			.register(record.toolCatalog(), "tools")
			.register(record.codec(), "codec")
			.register(record.application(), "application")
			.build();
		WorkerCapabilities worker = new WorkerCapabilities(1, Set.of(DefinitionKind.values()), Set.of(record.codec()));
		RunDefinitionAdmission.requireDurableMode(store.capabilities(),
				List.of(RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.CUSTOM_NODE),
						RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.CHAT_MEMORY),
						RuntimeComponentDeclaration.dataOnly(RuntimeComponentRole.STORE, "store"),
						RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.BACKGROUND_TOOL)));
		return RunDefinitionAdmission.resolve(admitted, codec.decode(codec.encode(record)), registry, worker);
	}

}
