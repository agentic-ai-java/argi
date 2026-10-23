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
package io.github.agentic.ai.contract;

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
import io.github.agentic.ai.workflow.WorkflowAgent;
import io.github.agentic.ai.workflow.WorkflowDefinition;
import io.github.agentic.ai.workflow.WorkflowDefinitionParser;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Runs a parsed two-step workflow without Graph, Loop or framework artifacts. */
public final class WorkflowConsumer {

	private WorkflowConsumer() {
	}

	public static void main(String[] args) throws Exception {
		for (String unavailable : List.of("io.github.agentic.ai.graph.StateGraph",
				"io.github.agentic.ai.graph.CompiledGraph", "io.github.agentic.ai.graph.RunnableConfig",
				"reactor.core.publisher.Flux", "org.springframework.context.ApplicationContext")) {
			try {
				Class.forName(unavailable, false, WorkflowConsumer.class.getClassLoader());
				throw new IllegalStateException("Unexpected Graph or framework dependency: " + unavailable);
			}
			catch (ClassNotFoundException expected) {
			}
		}
		WorkflowDefinition definition = new WorkflowDefinitionParser().parse("""
				{"id":"consumer","version":"1.0.0","steps":[
				{"type":"agent","id":"answer","agentRef":"echo"},
				{"type":"tool","id":"record","toolName":"record","arguments":{"value":{"type":"state","path":"answer"}}}
				]}""");
		Agent echo = (request, context) -> subscriber -> {
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					if (demand > 0) {
						subscriber.onNext(new AgentEvent.Finished(AgentOutcome.completed(
								List.of(ChatMessage.assistant("echo")), Map.of("answer", "echo"), new Usage(0L, 1L, 1L))));
						subscriber.onComplete();
					}
				}

				public void cancel() {
				}
			});
		};
		Tool record = new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition("record", "record", "{}");
			}

			public CompletionStage<ToolResult> execute(io.github.agentic.ai.api.chat.ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("recorded"));
			}
		};
		WorkflowAgent workflow = WorkflowAgent.builder(definition).agents(Map.of("echo", echo))
				.tools(new ToolRegistry(List.of(record))).build();
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
				.tenant(new TenantReference("consumer", "tenant"))
				.principal(new PrincipalReference("consumer", "principal"))
				.runId("run").correlationId("correlation").budget(ExecutionBudget.unlimited())
				.delegationScope(DelegationScope.none()).build();
		try (AgentExecutionContext context = new AgentExecutionContext(trusted, Clock.systemUTC())) {
			AgentOutcome outcome = workflow.invoke(AgentRequest.messages(List.of(ChatMessage.user("hello"))), context)
					.get();
			if (!"recorded".equals(outcome.outputs().get("record"))) {
				throw new IllegalStateException("Independent workflow did not execute its tool step");
			}
		}
	}

}
