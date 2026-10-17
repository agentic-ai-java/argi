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

import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.run.RunPayload;
import io.github.agentic.ai.graph.execution.run.RunSpec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model-agnostic fixtures shared by the version-pinned definition tests. */
final class DefinitionTestSupport {

	static final DefinitionReference GRAPH_V1 = DefinitionReference.of(DefinitionKind.GRAPH, "checkout", "1");

	static final DefinitionReference GRAPH_V2 = DefinitionReference.of(DefinitionKind.GRAPH, "checkout", "2");

	static final DefinitionReference TOOLS_V3 = DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "main", "3");

	static final DefinitionReference CODEC_V2 = DefinitionReference.of(DefinitionKind.CODEC, "argi-json", "2");

	static final DefinitionReference APP_V5 = DefinitionReference.of(DefinitionKind.APPLICATION, "shop", "5");

	private DefinitionTestSupport() {
	}

	static TrustedExecutionContext context(String runId) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "user"))
			.runId(runId)
			.correlationId("trace")
			.budget(new ExecutionBudget(Map.of("tokens", 100L)))
			.delegationScope(new DelegationScope(Set.of("read")))
			.build();
	}

	static RunPayload payload(String json) {
		return new RunPayload(1, "test.input", json);
	}

	static RunSpec run(String runId, String graphId, String graphVersion) {
		return RunSpec.from(context(runId), "app", "session", "key", graphId, graphVersion, payload("{}"));
	}

	static RunDefinitionRecord record() {
		return RunDefinitionRecord.of(GRAPH_V1, TOOLS_V3, CODEC_V2, APP_V5);
	}

	static WorkerCapabilities worker(DefinitionKind... kinds) {
		return new WorkerCapabilities(1, Set.copyOf(Arrays.asList(kinds)), Set.of(CODEC_V2));
	}

	static WorkerCapabilities fullWorker() {
		return worker(DefinitionKind.values());
	}

	static List<RuntimeComponentDeclaration> recoverableComponents() {
		return List.of(RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.CUSTOM_NODE),
				RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.CHAT_MEMORY),
				RuntimeComponentDeclaration.dataOnly(RuntimeComponentRole.STORE, "jdbc-store"),
				RuntimeComponentDeclaration.notApplicable(RuntimeComponentRole.BACKGROUND_TOOL));
	}

	static DefinitionRegistry registryWithAllVersions() {
		return DefinitionRegistry.builder()
			.register(GRAPH_V1, "graph-v1")
			.register(GRAPH_V2, "graph-v2")
			.register(TOOLS_V3, "tools-v3")
			.register(CODEC_V2, "codec-v2")
			.register(APP_V5, "app-v5")
			.build();
	}

}
