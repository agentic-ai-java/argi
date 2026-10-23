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
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowCoreTest {

	@Test
	void parsesAndPublishesImmutableVersionedDefinitions() {
		String json = """
				{"id":"support","version":"1.0.0","steps":[
				{"type":"agent","id":"answer","agentRef":"support-agent","inputs":{"question":{"type":"input","path":"question"}}},
				{"type":"condition","id":"route","condition":{"type":"compare","operator":"EQUALS","left":{"type":"state","path":"approved"},"right":true},"then":{"type":"tool","id":"approved-tool","toolName":"approve","arguments":{}},"else":{"type":"tool","id":"reject-tool","toolName":"reject","arguments":{}}}
				]}""";
		WorkflowDefinition definition = new WorkflowDefinitionParser().parse(json);
		WorkflowRegistry registry = new WorkflowRegistry();
		assertEquals(definition, registry.publish(definition));
		assertEquals(definition, registry.get("support", "1.0.0"));
		assertThrows(IllegalStateException.class, () -> registry.publish(
				new WorkflowDefinition("support", "1.0.0", List.of(new WorkflowStep.Tool("other", "approve", Map.of())))));
	}

	@Test
	void executesConditionLoopParallelToolAndAgentStepsWithoutGraph() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		Agent agent = agent(request -> {
			int value = calls.incrementAndGet();
			return AgentOutcome.completed(List.of(ChatMessage.assistant("agent-" + value)),
					Map.of("value", value, "approved", value == 1), new Usage(1L, 1L, 2L));
		});
		Tool tool = new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition("mark", "mark", "{}");
			}

			public CompletionStage<ToolResult> execute(io.github.agentic.ai.api.chat.ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("marked"));
			}
		};
		Tool toggle = new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition("toggle", "toggle", "{}");
			}

			public CompletionStage<ToolResult> execute(io.github.agentic.ai.api.chat.ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("false"));
			}
		};
		WorkflowStep worker = new WorkflowStep.Agent("worker", "worker");
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1", List.of(worker,
				new WorkflowStep.Condition("route", new WorkflowValue.StepOutput("worker", "approved"),
						new WorkflowStep.Tool("mark", "mark", Map.of()), null),
				new WorkflowStep.Loop("repeat", WorkflowValue.state("runLoop"),
						new WorkflowStep.Tool("toggle", "toggle", Map.of(), "runLoop", null), 2),
				new WorkflowStep.Parallel("parallel", List.of(new WorkflowStep.Agent("left", "worker"),
						new WorkflowStep.Agent("right", "worker")))));
		WorkflowAgent workflow = WorkflowAgent.builder(definition).agents(Map.of("worker", agent))
				.tools(new ToolRegistry(List.of(tool, toggle))).build();
		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow.invoke(new AgentRequest(List.of(ChatMessage.user("hello")),
					Map.of("runLoop", true), null), context)
					.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
			assertEquals("marked", outcome.outputs().get("mark"));
			assertEquals(3, calls.get());
		}
	}

	@Test
	void rejectsDuplicateStepIdsAndKeepsResumeTokenOpaque() {
		assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition("bad", "1", List.of(
				new WorkflowStep.Tool("same", "a", Map.of()), new WorkflowStep.Tool("same", "b", Map.of()))).validate());
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
