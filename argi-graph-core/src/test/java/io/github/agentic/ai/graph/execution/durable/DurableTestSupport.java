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
package io.github.agentic.ai.graph.execution.durable;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionReference;
import io.github.agentic.ai.graph.execution.definition.DefinitionRegistry;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentDeclaration;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentRole;
import io.github.agentic.ai.graph.execution.definition.WorkerCapabilities;
import io.github.agentic.ai.graph.execution.run.MemoryRunStore;
import io.github.agentic.ai.graph.execution.run.RunPayload;
import io.github.agentic.ai.graph.execution.run.RunSpec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

final class DurableTestSupport {
	static final DefinitionReference GRAPH = DefinitionReference.of(DefinitionKind.GRAPH, "graph", "1");
	static final DefinitionReference TOOLS = DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "tools", "1");
	static final DefinitionReference CODEC = DefinitionReference.of(DefinitionKind.CODEC, "json", "1");
	static final DefinitionReference APP = DefinitionReference.of(DefinitionKind.APPLICATION, "app", "1");
	static final RunDefinitionRecord RECORD = RunDefinitionRecord.of(GRAPH, TOOLS, CODEC, APP);
	static final WorkerCapabilities WORKER = new WorkerCapabilities(1, Set.of(DefinitionKind.values()), Set.of(CODEC));
	static final LeaseOptions OPTIONS = new LeaseOptions(Duration.ofSeconds(2), Duration.ofMillis(50));

	private DurableTestSupport() {
	}

	static TrustedExecutionContext context() {
		return context(null);
	}

	static TrustedExecutionContext context(Instant deadline) {
		return TrustedExecutionContext.builder().tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "user")).runId("run").correlationId("trace")
			.deadline(deadline).build();
	}

	static RunSpec spec(TrustedExecutionContext context) {
		return RunSpec.from(context, "app", "session", "submission", "graph", "1",
				new RunPayload(1, "test.input", "{\"value\":1}"));
	}

	static List<RuntimeComponentDeclaration> components() {
		return Arrays.stream(RuntimeComponentRole.values())
			.map(role -> role == RuntimeComponentRole.CUSTOM_NODE
				? RuntimeComponentDeclaration.dataOnly(role, "graph")
				: RuntimeComponentDeclaration.notApplicable(role)).toList();
	}

	static DefinitionRegistry registry(DurableGraphDefinition definition) {
		return DefinitionRegistry.builder().register(GRAPH, definition).register(TOOLS, "no-tools")
			.register(CODEC, new JsonDurableStateCodec()).register(APP, "test-app").build();
	}

	static DurableGraphDefinition definition(DurableGraphDefinition.Factory factory) {
		return new DurableGraphDefinition(factory, ReplaySafety.REPLAYABLE, components());
	}

	static CompiledGraph graph(LeasedCheckpointSaver saver, AsyncNodeActionWithConfig first,
			AsyncNodeActionWithConfig second) throws Exception {
		return new StateGraph().addNode("first", first).addNode("second", second)
			.addEdge(StateGraph.START, "first").addEdge("first", "second").addEdge("second", StateGraph.END)
			.compile(CompileConfig.builder().saverConfig(new SaverConfig().register(saver)).build());
	}

	static DurableRunRunner runner(MemoryRunStore store, MutableClock clock, DurableGraphDefinition definition) {
		return new DurableRunRunner(store, registry(definition), WORKER, OPTIONS, event -> { }, clock);
	}

	static final class MutableClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

		void advance(Duration duration) {
			now.updateAndGet(value -> value.plus(duration));
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}
}
