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
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatMessage;
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
import java.util.concurrent.Flow;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the subflow recursion boundary (issue #273): resolvable
 * references are cycle-checked at publish, the run keeps a call chain with a depth
 * limit so neither a cycle nor an overly deep acyclic chain can exhaust the JVM
 * stack, and legitimate reuse of the same subflow (sequential or parallel) stays
 * legal.
 */
class WorkflowSubflowBoundaryTest {

	private static final int DEFAULT_MAX_DEPTH = 16;

	@Test
	void publishRejectsSelfReference() {
		WorkflowRegistry registry = new WorkflowRegistry();
		WorkflowDefinition self = new WorkflowDefinition("cycle", "1",
				List.of(new WorkflowStep.Subflow("self", "cycle", "1")));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> registry.publish(self));
		assertTrue(failure.getMessage().contains("cycle@1 -> cycle@1"), failure.getMessage());
	}

	@Test
	void publishRejectsIndirectCycleAcrossDefinitions() {
		WorkflowRegistry registry = new WorkflowRegistry();
		// b@1 is published first and references a@1, which is not published yet: a
		// legal forward reference.
		registry.publish(new WorkflowDefinition("b", "1",
				List.of(new WorkflowStep.Tool("work", "record", Map.of())),
				null, null, Map.of()));
		registry.publish(new WorkflowDefinition("b", "2",
				List.of(new WorkflowStep.Subflow("back", "a", "1"))));
		// Publishing a@1 closes the cycle a@1 -> b@2 -> a@1.
		WorkflowDefinition a = new WorkflowDefinition("a", "1", List.of(new WorkflowStep.Subflow("call", "b", "2")));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> registry.publish(a));
		assertTrue(failure.getMessage().contains("a@1 -> b@2 -> a@1"), failure.getMessage());
	}

	@Test
	void forwardReferencePublishesAndReportsUnresolvedAtRun() {
		WorkflowRegistry registry = new WorkflowRegistry();
		WorkflowDefinition caller = new WorkflowDefinition("caller", "1",
				List.of(new WorkflowStep.Subflow("call", "missing", "1")));
		assertEquals(caller, registry.publish(caller));
		WorkflowAgent workflow = WorkflowAgent.builder(caller).registry(registry).build();
		try (AgentExecutionContext context = context()) {
			Exception failure = assertThrows(Exception.class, () -> workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
					.get(3, TimeUnit.SECONDS));
			assertTrue(String.valueOf(failure.getCause()).contains("missing@1"),
					String.valueOf(failure.getCause()));
		}
	}

	@Test
	void deepAcyclicChainFailsWithDomainErrorNotStackOverflow() {
		WorkflowRegistry registry = new WorkflowRegistry();
		int depth = 20;
		// Chain of definitions d0 -> d1 -> ... -> d19; d19 executes a tool.
		WorkflowDefinition leaf = new WorkflowDefinition("d" + (depth - 1), "1",
				List.of(new WorkflowStep.Tool("work", "record", Map.of())));
		registry.publish(leaf);
		for (int i = depth - 2; i >= 0; i--) {
			registry.publish(new WorkflowDefinition("d" + i, "1",
					List.of(new WorkflowStep.Subflow("next", "d" + (i + 1), "1"))));
		}
		WorkflowAgent workflow = WorkflowAgent.builder(registry.get("d0", "1")).registry(registry)
				.tools(new ToolRegistry(List.of(recordingTool()))).build();
		try (AgentExecutionContext context = context()) {
			Exception failure = assertThrows(Exception.class, () -> workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
					.get(5, TimeUnit.SECONDS));
			WorkflowSubflowRecursionException boundary = assertInstanceOf(WorkflowSubflowRecursionException.class,
					failure.getCause());
			assertTrue(boundary.getMessage().contains("depth"), boundary.getMessage());
			// The reported chain names the root definition, every entered subflow
			// (limit), and the refused one.
			assertEquals(DEFAULT_MAX_DEPTH + 2, boundary.chain().size());
		}
	}

	@Test
	void configuredDepthLimitTightensTheBoundary() {
		WorkflowRegistry registry = new WorkflowRegistry();
		WorkflowDefinition leaf = new WorkflowDefinition("leaf", "1",
				List.of(new WorkflowStep.Tool("work", "record", Map.of())));
		registry.publish(leaf);
		registry.publish(new WorkflowDefinition("inner", "1",
				List.of(new WorkflowStep.Subflow("next", "leaf", "1"))));
		registry.publish(new WorkflowDefinition("middle", "1",
				List.of(new WorkflowStep.Subflow("next", "inner", "1"))));
		WorkflowDefinition root = new WorkflowDefinition("root", "1",
				List.of(new WorkflowStep.Subflow("next", "middle", "1")));
		registry.publish(root);
		// Three nested subflow levels with a limit of 2: a legal chain that is simply
		// too deep, refused with a domain error.
		WorkflowAgent workflow = WorkflowAgent.builder(root).registry(registry).subflowMaxDepth(2)
				.tools(new ToolRegistry(List.of(recordingTool()))).build();
		try (AgentExecutionContext context = context()) {
			Exception failure = assertThrows(Exception.class, () -> workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
					.get(3, TimeUnit.SECONDS));
			WorkflowSubflowRecursionException boundary = assertInstanceOf(WorkflowSubflowRecursionException.class,
					failure.getCause());
			// The chain starts at the executing definition and names every level.
			assertEquals(4, boundary.chain().size());
			assertEquals("root@1", boundary.chain().get(0));
			assertEquals("leaf@1", boundary.chain().get(3));
		}
	}

	@Test
	void sequentialReuseOfTheSameSubflowIsLegal() throws Exception {
		WorkflowRegistry registry = new WorkflowRegistry();
		WorkflowDefinition shared = new WorkflowDefinition("shared", "1",
				List.of(new WorkflowStep.Tool("work", "record", Map.of())));
		registry.publish(shared);
		WorkflowDefinition caller = new WorkflowDefinition("caller", "1",
				List.of(new WorkflowStep.Subflow("first", "shared", "1"),
						new WorkflowStep.Subflow("second", "shared", "1")));
		registry.publish(caller);
		AtomicInteger executions = new AtomicInteger();
		WorkflowAgent workflow = WorkflowAgent.builder(caller).registry(registry)
				.tools(new ToolRegistry(List.of(countingTool(executions)))).build();
		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
					.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
			assertEquals(2, executions.get());
		}
	}

	@Test
	void parallelBranchesReusingTheSameSubflowAreLegal() throws Exception {
		WorkflowRegistry registry = new WorkflowRegistry();
		WorkflowDefinition shared = new WorkflowDefinition("shared", "1",
				List.of(new WorkflowStep.Tool("work", "record", Map.of())));
		registry.publish(shared);
		WorkflowDefinition caller = new WorkflowDefinition("caller", "1",
				List.of(new WorkflowStep.Parallel("both",
						List.of(new WorkflowStep.Subflow("left", "shared", "1"),
								new WorkflowStep.Subflow("right", "shared", "1")))));
		registry.publish(caller);
		AtomicInteger executions = new AtomicInteger();
		WorkflowAgent workflow = WorkflowAgent.builder(caller).registry(registry)
				.tools(new ToolRegistry(List.of(countingTool(executions)))).build();
		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
					.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
			assertEquals(2, executions.get());
		}
	}

	@Test
	void cancellationInsideASubflowTerminatesNormally() throws Exception {
		WorkflowRegistry registry = new WorkflowRegistry();
		WorkflowDefinition shared = new WorkflowDefinition("shared", "1",
				List.of(new WorkflowStep.Agent("stop", "ref")));
		registry.publish(shared);
		WorkflowDefinition caller = new WorkflowDefinition("caller", "1",
				List.of(new WorkflowStep.Subflow("call", "shared", "1")));
		registry.publish(caller);
		// The fake agent cancels its own run from inside the subflow: the failure must
		// propagate out of the nested executeSteps instead of being swallowed.
		Agent cancellingAgent = (request, agentContext) -> subscriber -> {
			agentContext.control().cancel();
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					if (demand > 0) {
						subscriber.onError(new IllegalStateException("cancelled inside the subflow"));
						subscriber.onComplete();
					}
				}

				public void cancel() {
				}
			});
		};
		WorkflowAgent workflow = WorkflowAgent.builder(caller).registry(registry)
				.agents(Map.of("ref", cancellingAgent)).build();
		try (AgentExecutionContext context = context()) {
			assertThrows(Exception.class, () -> workflow
					.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
					.get(3, TimeUnit.SECONDS));
		}
	}

	private static Tool recordingTool() {
		return countingTool(new AtomicInteger());
	}

	private static Tool countingTool(AtomicInteger executions) {
		return new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition("record", "record", "{}");
			}

			public CompletionStage<ToolResult> execute(io.github.agentic.ai.api.chat.ToolCall call,
					ToolContext context) {
				executions.incrementAndGet();
				return CompletableFuture.completedFuture(ToolResult.text("recorded"));
			}
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
