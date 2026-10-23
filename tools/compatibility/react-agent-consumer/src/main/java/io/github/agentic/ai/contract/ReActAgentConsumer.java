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

import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.react.ReActAgent;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Executes a real two-turn ReAct invocation with only Agent Core and its neutral dependencies. */
public final class ReActAgentConsumer {

	private ReActAgentConsumer() {
	}

	public static void main(String[] args) throws Exception {
		for (String unavailable : List.of("io.github.agentic.ai.graph.StateGraph",
				"io.github.agentic.ai.graph.CompiledGraph", "io.github.agentic.ai.graph.RunnableConfig",
				"reactor.core.publisher.Flux", "org.springframework.context.ApplicationContext")) {
			try {
				Class.forName(unavailable, false, ReActAgentConsumer.class.getClassLoader());
				throw new IllegalStateException("Unexpected Graph or framework dependency: " + unavailable);
			}
			catch (ClassNotFoundException expected) {
				// The independent ReAct consumer must work without these artifacts.
			}
		}
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		Queue<ChatResponse> responses = new ArrayDeque<>(List.of(
				response(ChatMessage.assistant("tool", List.of(call)), List.of(call)),
				response(ChatMessage.assistant("answer"), List.of())));
		ChatClient client = new ChatClient() {
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(responses.remove());
			}

			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				throw new UnsupportedOperationException();
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
		Tool tool = new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", "lookup", "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall ignored, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("tool result"));
			}
		};
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(tool))).build();
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
				.tenant(new TenantReference("consumer", "tenant"))
				.principal(new PrincipalReference("consumer", "principal"))
				.runId("run").correlationId("correlation").budget(ExecutionBudget.unlimited())
				.delegationScope(DelegationScope.none()).build();
		try (AgentExecutionContext context = new AgentExecutionContext(trusted, Clock.systemUTC())) {
			AgentOutcome outcome = agent.invoke(AgentRequest.messages(List.of(ChatMessage.user("hello"))), context)
					.get();
			if (!"answer".equals(outcome.messages().get(outcome.messages().size() - 1).text())
					|| outcome.messages().stream().noneMatch(message -> "tool result".equals(message.text()))) {
				throw new IllegalStateException("ReAct loop did not execute the independent tool turn");
			}
		}
	}

	private static ChatResponse response(ChatMessage message, List<ToolCall> calls) {
		return new ChatResponse(null, "consumer", List.of(message), calls, FinishReason.STOP,
				new Usage(1L, 1L, 1L), Map.of());
	}

}
