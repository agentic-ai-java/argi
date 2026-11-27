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
package io.github.agentic.ai.agent.orchestration;

import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.AgentResult;
import io.github.agentic.ai.agent.durable.ModelTurnAttemptPolicy;
import io.github.agentic.ai.agent.durable.ModelTurnBindings;
import io.github.agentic.ai.agent.durable.DurableSession;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.runtime.execution.definition.DefinitionKind;
import io.github.agentic.ai.runtime.execution.definition.DefinitionReference;
import io.github.agentic.ai.runtime.execution.run.MemoryRunStore;
import io.github.agentic.ai.runtime.execution.run.RunCommitCoordinator;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunSpec;
import io.github.agentic.ai.workflow.graph.RunnableConfig;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executable reference wiring. No credentials, database or provider SDK is required. */
class DurableAgentExampleTest {

	private static final String SCHEMA = """
			{"type":"object","properties":{"answer":{"type":"integer"}},"required":["answer"]}
			""";

	@ParameterizedTest
	@CsvSource({ "false,true", "true,true", "false,false", "true,false" })
	void invokesAndRebuildsBothEnginesWithPureOutputValidation(boolean graph, boolean valid) throws Exception {
		var trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("example", "tenant"))
			.principal(new PrincipalReference("example", "user"))
			.runId(UUID.randomUUID().toString())
			.correlationId("example")
			.build();
		var store = new MemoryRunStore();
		var spec = RunSpec.from(trusted, "example", "session", "submission",
				DefinitionReference.of(DefinitionKind.AGENT, graph ? "graph-agent" : "independent-agent", "1"),
				new RunPayload(1, "input", "{\"question\":\"answer?\"}"));
		store.submit(spec, trusted);
		var lease = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), Duration.ofMinutes(1));
		var calls = new AtomicInteger();

		// Each iteration creates new executable objects and restores from data in the
		// store.
		for (int invocation = 0; invocation < 2; invocation++) {
			ChatClient client = new ExampleModel(calls, valid ? "{\"answer\":42}" : "invalid");
			var catalog = new ToolRegistry(List.of());
			var bindings = new ModelTurnBindings("example-model", "1", "example-fixed-config", client, "example-tools",
					"1", catalog, "example-pipeline-1", Set.of());
			try (var scope = new AgentExecutionContext(trusted, Clock.systemUTC())) {
				var coordinator = new RunCommitCoordinator(store,
						store.find(spec.namespace(), spec.runId()).orElseThrow(), () -> lease, scope.control());
				// MemoryRunStore does not pass durable admission. Production uses
				// durable(...).
				var session = DurableSession.reference(coordinator, bindings, ModelTurnAttemptPolicy.DEFAULT);
				if (graph) {
					var agent = ReactAgent.builder()
						.name("example")
						.chatClient(client)
						.toolRegistry(catalog)
						.outputSchema(SCHEMA)
						.build();
					var config = RunnableConfig.builder()
						.threadId("session")
						.trustedExecutionContext(trusted)
						.executionGuard(scope.control())
						.addMetadata("_stream_", false)
						.build();
					AgentResult result = agent.callResult("answer?", config, session);
					assertThat(result.status())
						.isEqualTo(valid ? AgentResult.Status.COMPLETED : AgentResult.Status.VALIDATION_FAILED);
					if (valid) {
						assertThat(result.data())
							.isEqualTo(new tools.jackson.databind.json.JsonMapper().readTree("{\"answer\":42}"));
					}
				}
				else {
					var agent = io.github.agentic.ai.agent.react.ReActAgent.builder(client)
						.tools(catalog)
						.outputSchema(SCHEMA)
						.build();
					var result = agent.invoke(AgentRequest.messages(List.of(ChatMessage.user("answer?"))), scope,
							session);
					if (valid) {
						assertThat(result.join().outputs()).containsEntry("structuredOutput",
								new tools.jackson.databind.json.JsonMapper().readTree("{\"answer\":42}"));
					}
					else {
						assertThatThrownBy(result::join)
							.hasCauseInstanceOf(io.github.agentic.ai.agent.react.AgentExecutionException.class);
					}
				}
			}
		}
		assertThat(calls.get()).isEqualTo(1);
	}

	private record ExampleModel(AtomicInteger calls, String text) implements ChatClient {
		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			calls.incrementAndGet();
			return CompletableFuture
				.completedFuture(new ChatResponse("response", "example", List.of(ChatMessage.assistant(text)),
						List.of(), FinishReason.STOP, new Usage(1L, 1L, 2L), Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException("This example uses complete responses");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(false, false, false, false, false, false);
		}
	}

}
