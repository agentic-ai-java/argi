/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.workflow;

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.AgentResult;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the input/output schema boundary of a workflow definition
 * (issue #274): declared schemas must be validated at publish time, inputs must
 * be validated before any step or tool side effect, and a final output that does
 * not match the declared schema must fail the completion instead of delivering
 * COMPLETED.
 */
class WorkflowSchemaContractTest {

	private static final String MALFORMED_SCHEMA = "{\"type\":\"object\"";

	private static final String INPUT_SCHEMA =
			"{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"}},\"required\":[\"question\"]}";

	// The step outputKey stores the agent outputs map, so the output schema describes
	// result.value.
	private static final String OUTPUT_SCHEMA =
			"{\"type\":\"object\",\"properties\":{\"result\":{\"type\":\"object\","
					+ "\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}},"
					+ "\"required\":[\"result\"]}";

	@Test
	void publishRejectsMalformedSchemas() {
		WorkflowRegistry registry = new WorkflowRegistry();
		IllegalArgumentException input = assertThrows(IllegalArgumentException.class,
				() -> registry.publish(definition(MALFORMED_SCHEMA, null)));
		assertTrue(input.getMessage().contains("inputSchema"));
		IllegalArgumentException output = assertThrows(IllegalArgumentException.class,
				() -> registry.publish(definition(null, MALFORMED_SCHEMA)));
		assertTrue(output.getMessage().contains("outputSchema"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"required\":\"question\"}", "[]", "123", "\"schema\"", "null" })
	void publishRejectsDocumentsThatAreNotJsonSchemas(String schema) {
		WorkflowRegistry registry = new WorkflowRegistry();
		IllegalArgumentException input = assertThrows(IllegalArgumentException.class,
				() -> registry.publish(definition(schema, null)));
		assertTrue(input.getMessage().contains("inputSchema"));
		IllegalArgumentException output = assertThrows(IllegalArgumentException.class,
				() -> registry.publish(definition(null, schema)));
		assertTrue(output.getMessage().contains("outputSchema"));
	}

	@Test
	void publishAcceptsObjectAndBooleanSchemas() {
		new WorkflowRegistry().publish(definition("{\"type\":\"object\"}", "true"));
		new WorkflowRegistry().publish(definition("false", "{}"));
	}

	@Test
	void blankSchemasStayRejected() {
		WorkflowRegistry registry = new WorkflowRegistry();
		assertThrows(IllegalArgumentException.class, () -> registry.publish(definition(" ", null)));
		assertThrows(IllegalArgumentException.class, () -> registry.publish(definition(null, " ")));
	}

	@Test
	void undeclaredSchemasKeepExistingBehavior() throws Exception {
		AtomicInteger executions = new AtomicInteger();
		WorkflowAgent workflow = WorkflowAgent.builder(definition(null, null))
				.tools(new ToolRegistry(List.of(countingTool("record", executions)))).build();
		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
					.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
			assertEquals(1, executions.get());
		}
	}

	@Test
	void missingRequiredInputIsRejectedBeforeAnySideEffect() {
		AtomicInteger executions = new AtomicInteger();
		WorkflowAgent workflow = WorkflowAgent.builder(definition(INPUT_SCHEMA, null))
				.tools(new ToolRegistry(List.of(countingTool("record", executions)))).build();
		try (AgentExecutionContext context = context()) {
			WorkflowValidationException failure = validationFailure(() -> workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context));
			assertEquals(AgentResult.ERROR_CODE_SCHEMA_VIOLATION, failure.issues().get(0).errorCode());
			assertEquals(0, executions.get());
		}
	}

	@Test
	void typeMismatchReportsTheFieldPath() {
		AtomicInteger executions = new AtomicInteger();
		WorkflowAgent workflow = WorkflowAgent.builder(definition(INPUT_SCHEMA, null))
				.tools(new ToolRegistry(List.of(countingTool("record", executions)))).build();
		try (AgentExecutionContext context = context()) {
			WorkflowValidationException failure = validationFailure(() -> workflow.invoke(
					new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("question", 123), null), context));
			assertTrue(failure.issues().get(0).fieldPath().contains("question"));
			assertEquals(0, executions.get());
		}
	}

	@Test
	void validInputAndOutputComplete() throws Exception {
		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", "analysis complete"), new Usage(1L, 1L, 2L)));
		WorkflowStep step = new WorkflowStep.Agent("answer", "ref", Map.of(), "result", null);
		WorkflowDefinition definition = new WorkflowDefinition("schema", "1", List.of(step), INPUT_SCHEMA,
				OUTPUT_SCHEMA, Map.of());
		WorkflowAgent workflow = WorkflowAgent.builder(definition).agents(Map.of("ref", agent)).build();
		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("question", "why"), null),
							context)
					.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
			assertEquals(Map.of("value", "analysis complete"), outcome.outputs().get("result"));
		}
	}

	@Test
	void outputViolationFailsInsteadOfCompleting() {
		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", 42), new Usage(1L, 1L, 2L)));
		WorkflowStep step = new WorkflowStep.Agent("answer", "ref", Map.of(), "result", null);
		WorkflowDefinition definition = new WorkflowDefinition("schema", "1", List.of(step), INPUT_SCHEMA,
				OUTPUT_SCHEMA, Map.of());
		WorkflowAgent workflow = WorkflowAgent.builder(definition).agents(Map.of("ref", agent)).build();
		try (AgentExecutionContext context = context()) {
			WorkflowValidationException failure = validationFailure(() -> workflow.invoke(
					new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("question", "why"), null), context));
			assertTrue(failure.issues().stream().anyMatch(issue -> issue.fieldPath().contains("result")));
		}
	}

	@Test
	void resumeSkipsInputRevalidationButStillAcceptsValidOutputs() throws Exception {
		AtomicInteger resumedInvocations = new AtomicInteger();
		WorkflowAgent workflow = suspendingWorkflow("done", resumedInvocations);
		try (AgentExecutionContext first = context()) {
			AgentOutcome suspended = workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("question", "why"), null),
							first)
					.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.SUSPENDED, suspended.status());
			String token = suspended.suspension().resumeToken();
			try (AgentExecutionContext resumed = context()) {
				AgentOutcome outcome = workflow
						.invoke(new AgentRequest(List.of(), Map.of(), token), resumed)
						.get(3, TimeUnit.SECONDS);
				// The resume request carries no inputs at all: the schema was enforced
				// on the fresh run, the continuation owns the state afterwards.
				assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
				// The continuation must resume the agent exactly once: a second
				// invocation with the same token would be rejected by a real agent.
				assertEquals(1, resumedInvocations.get());
			}
		}
	}

	@Test
	void resumeOutputViolationStillFails() throws Exception {
		WorkflowAgent workflow = suspendingWorkflow(42, new AtomicInteger());
		try (AgentExecutionContext first = context()) {
			AgentOutcome suspended = workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("question", "why"), null),
							first)
					.get(3, TimeUnit.SECONDS);
			String token = suspended.suspension().resumeToken();
			try (AgentExecutionContext resumed = context()) {
				WorkflowValidationException failure = validationFailure(
						() -> workflow.invoke(new AgentRequest(List.of(), Map.of(), token), resumed));
				assertTrue(failure.issues().stream().anyMatch(issue -> issue.fieldPath().contains("result")));
			}
		}
	}

	@Test
	void subflowSchemasAreValidatedAtPublishAndDoNotGateParentInputs() throws Exception {
		WorkflowRegistry registry = new WorkflowRegistry();
		WorkflowDefinition broken = new WorkflowDefinition("nested", "1", List.of(new WorkflowStep.Tool("step",
				"record", Map.of())), MALFORMED_SCHEMA, null, Map.of());
		assertThrows(IllegalArgumentException.class, () -> registry.publish(broken));

		WorkflowDefinition valid = new WorkflowDefinition("nested", "1", List.of(new WorkflowStep.Tool("step",
				"record", Map.of())), INPUT_SCHEMA, null, Map.of());
		registry.publish(valid);
		WorkflowDefinition parent = new WorkflowDefinition("parent", "1",
				List.of(new WorkflowStep.Subflow("call", "nested", "1")), null, null, Map.of());
		registry.publish(parent);
		AtomicInteger executions = new AtomicInteger();
		WorkflowAgent workflow = WorkflowAgent.builder(parent).registry(registry)
				.tools(new ToolRegistry(List.of(countingTool("record", executions)))).build();
		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("question", "why"), null),
							context)
					.get(3, TimeUnit.SECONDS);
			// The subflow shares the parent inputs; the nested inputSchema is a publish-
			// time contract for its own callers, not an extra gate inside the parent run.
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
			assertEquals(1, executions.get());
		}
	}

	private WorkflowAgent suspendingWorkflow(Object resumedValue, AtomicInteger resumedInvocations) {
		AtomicReference<Object> secondCallValue = new AtomicReference<>(resumedValue);
		Agent agent = agent(request -> {
			if (request.resumeToken() != null) {
				resumedInvocations.incrementAndGet();
			}
			if (request.resumeToken() == null) {
				return new AgentOutcome(AgentOutcome.Status.SUSPENDED, List.of(ChatMessage.assistant("need input")),
						Map.of(), new Usage(1L, 1L, 2L), new AgentOutcome.Suspension("agent-token",
								Map.of("prompt", "provide the missing confirmation")));
			}
			return AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
					Map.of("value", secondCallValue.get()), new Usage(1L, 1L, 2L));
		});
		WorkflowStep step = new WorkflowStep.Agent("answer", "ref", Map.of(), "result", null);
		WorkflowDefinition definition = new WorkflowDefinition("schema", "1", List.of(step), INPUT_SCHEMA,
				OUTPUT_SCHEMA, Map.of());
		return WorkflowAgent.builder(definition).agents(Map.of("ref", agent)).build();
	}

	private WorkflowDefinition definition(String inputSchema, String outputSchema) {
		return new WorkflowDefinition("schema", "1",
				List.of(new WorkflowStep.Tool("record", "record", Map.of(), "result", null)), inputSchema,
				outputSchema, Map.of());
	}

	private static Tool countingTool(String name, AtomicInteger executions) {
		return new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition(name, name, "{}");
			}

			public CompletionStage<ToolResult> execute(io.github.agentic.ai.api.chat.ToolCall call,
					ToolContext context) {
				executions.incrementAndGet();
				return CompletableFuture.completedFuture(ToolResult.text("recorded"));
			}
		};
	}

	private static WorkflowValidationException validationFailure(
			java.util.function.Supplier<CompletableFuture<AgentOutcome>> invocation) {
		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> invocation.get().get(3, TimeUnit.SECONDS));
		return assertInstanceOf(WorkflowValidationException.class, failure.getCause());
	}

	private static Agent agent(java.util.function.Function<AgentRequest, AgentOutcome> function) {
		return (request, context) -> subscriber -> {
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					if (demand > 0) {
						subscriber.onNext(new AgentEvent.Finished(function.apply(request)));
						subscriber.onComplete();
					}
				}

				public void cancel() {
				}
			});
		};
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
				.tenant(new TenantReference("test", "tenant"))
				.principal(new PrincipalReference("test", "principal"))
				.runId("run").correlationId("correlation").budget(ExecutionBudget.unlimited())
				.delegationScope(DelegationScope.none()).build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

}
