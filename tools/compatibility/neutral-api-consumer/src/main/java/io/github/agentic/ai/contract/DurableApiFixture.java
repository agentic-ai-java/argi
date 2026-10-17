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

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionRegistry;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentDeclaration;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentRole;
import io.github.agentic.ai.graph.execution.definition.WorkerCapabilities;
import io.github.agentic.ai.graph.execution.durable.DurableGraphDefinition;
import io.github.agentic.ai.graph.execution.durable.DurableRunRunner;
import io.github.agentic.ai.graph.execution.durable.JsonDurableStateCodec;
import io.github.agentic.ai.graph.execution.durable.ReplaySafety;
import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunStore;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Compile the durable opt-in API without provider SDKs or Extensions. */
final class DurableApiFixture {
	private DurableApiFixture() {
	}

	static DurableRunRunner compileOnly(RunStore backend, RunSpec spec, RunDefinitionRecord record,
			TrustedExecutionContext context) {
		DurableGraphDefinition graph = new DurableGraphDefinition((resolved, saver) -> new StateGraph()
			.addNode("read", (state, config) -> CompletableFuture.completedFuture(Map.of("answer", 42)))
			.addEdge(StateGraph.START, "read").addEdge("read", StateGraph.END)
			.compile(CompileConfig.builder().saverConfig(new SaverConfig().register(saver)).build()),
				ReplaySafety.READ_ONLY, Arrays.stream(RuntimeComponentRole.values())
					.map(role -> role == RuntimeComponentRole.CUSTOM_NODE
						? RuntimeComponentDeclaration.dataOnly(role, "read")
						: RuntimeComponentDeclaration.notApplicable(role)).toList());
		DefinitionRegistry definitions = DefinitionRegistry.builder().register(record.graph(), graph)
			.register(record.toolCatalog(), "no-tools").register(record.codec(), new JsonDurableStateCodec())
			.register(record.application(), "application").build();
		DurableRunRunner runner = new DurableRunRunner(backend, definitions,
				new WorkerCapabilities(1, Set.of(DefinitionKind.values()), Set.of(record.codec())),
				LeaseOptions.defaults(), event -> { });
		runner.submit(spec, record, context);
		runner.run(spec.namespace(), spec.runId(), context);
		runner.query(spec.namespace(), spec.runId());
		runner.cancel(spec.namespace(), spec.runId());
		return runner;
	}
}
