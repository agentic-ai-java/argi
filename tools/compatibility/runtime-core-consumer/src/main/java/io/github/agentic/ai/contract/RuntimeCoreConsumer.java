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

import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionReference;
import io.github.agentic.ai.graph.execution.definition.DefinitionRegistry;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionAdmission;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecordCodec;
import io.github.agentic.ai.graph.execution.definition.WorkerCapabilities;
import io.github.agentic.ai.graph.execution.run.MemoryRunStore;
import io.github.agentic.ai.graph.execution.run.RunCommit;
import io.github.agentic.ai.graph.execution.run.RunEntry;
import io.github.agentic.ai.graph.execution.run.RunPayload;
import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunSpecCodec;
import io.github.agentic.ai.graph.execution.run.RunState;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A standalone runtime contract consumer, not an engine implementation. */
public final class RuntimeCoreConsumer {

	private RuntimeCoreConsumer() {
	}

	@FunctionalInterface
	private interface LocalAction {

		RunPayload execute(RunPayload input);

	}

	public static void main(String[] args) throws Exception {
		DefinitionKind kind = DefinitionKind.fromWireName(args[0]);
		for (String absent : Set.of("io.github.agentic.ai.graph.StateGraph", "io.github.agentic.ai.graph.agent.Agent",
				"reactor.core.publisher.Flux", "org.springframework.context.ApplicationContext",
				"io.micrometer.observation.ObservationRegistry")) {
			try {
				Class.forName(absent, false, RuntimeCoreConsumer.class.getClassLoader());
				throw new IllegalStateException("Unexpected engine dependency: " + absent);
			}
			catch (ClassNotFoundException expected) {
				// Engines and their integrations must not be available.
			}
		}
		var context = TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "user"))
			.runId("run")
			.correlationId("trace")
			.build();
		var target = DefinitionReference.of(kind, "local", "1");
		var tools = DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "tools", "1");
		var codec = DefinitionReference.of(DefinitionKind.CODEC, "json", "1");
		var application = DefinitionReference.of(DefinitionKind.APPLICATION, "app", "1");
		var original = RunSpec.from(context, "app", "session", "key", target, new RunPayload(1, "input", "{}"));
		var run = new RunSpecCodec().decodeRun(new RunSpecCodec().encodeRun(original));
		var record = RunDefinitionRecord.forRun(run, tools, codec, application);
		var restored = new RunDefinitionRecordCodec().decode(new RunDefinitionRecordCodec().encode(record));
		LocalAction action = input -> new RunPayload(1, "result", "{}");
		var registry = DefinitionRegistry.builder()
			.register(target, action)
			.register(tools, "tools")
			.register(codec, "json")
			.register(application, "app")
			.build();
		var definitions = RunDefinitionAdmission.resolve(run, restored, registry,
				new WorkerCapabilities(2,
						Set.of(kind, DefinitionKind.TOOL_CATALOG, DefinitionKind.CODEC, DefinitionKind.APPLICATION),
						Set.of(codec)));
		var store = new MemoryRunStore();
		store.submit(run, context);
		var lease = store.acquire(run.namespace(), run.runId(), UUID.randomUUID(), Duration.ofSeconds(30));
		store.commit(run.namespace(), run.runId(), lease,
				new RunCommit(0, RunState.RUNNING, null, List.of(), List.of(), List.of()));
		try (var control = new ExecutionControl(context, Clock.systemUTC())) {
			control.assertActive();
			RunPayload result = ((LocalAction) definitions.execution()).execute(run.input());
			control.assertActive();
			var event = new RunEntry(1, "completed", RunEntry.Kind.EVENT, result);
			var finished = store.commit(run.namespace(), run.runId(), lease,
					new RunCommit(1, RunState.SUCCEEDED, null, List.of(), List.of(event), List.of(event)));
			if (finished.state() != RunState.SUCCEEDED) {
				throw new IllegalStateException();
			}
		}
	}

}
