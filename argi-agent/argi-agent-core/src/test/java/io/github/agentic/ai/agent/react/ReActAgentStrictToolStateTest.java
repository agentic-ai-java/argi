/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchSpec;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchViolationException;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict tool-state mode ({@code strictToolState(true)}) on the independent ReAct path:
 * tools see a read-only view of the invocation state instead of the live map, a failed
 * call's patch is always discarded, and patch validation keeps parity with the graph
 * path. Legacy pins (strict off) guard the default behavior. All scenarios run against
 * a scripted client, without a model.
 */
class ReActAgentStrictToolStateTest {

	@Test
	void strictModeRejectsSharedCollectionMutationThroughToolContext() throws Exception {
		var mutationFailure = new AtomicReference<Throwable>();
		Tool mutator = tool("mutate", (call, context) -> {
			@SuppressWarnings("unchecked")
			List<Object> shared = (List<Object>) context.attributes().get("shared");
			try {
				shared.add("hijack");
			}
			catch (UnsupportedOperationException rejection) {
				mutationFailure.set(rejection);
			}
			return CompletableFuture.completedFuture(ToolResult.text("done"));
		});
		var outcome = invoke(strictAgent(mutator, "mutate"), seededRequest());
		assertInstanceOf(UnsupportedOperationException.class, mutationFailure.get());
		assertEquals(List.of("original"), outcome.outputs().get("shared"));
	}

	@Test
	void legacyAllowsSharedCollectionMutationThroughToolContext() throws Exception {
		Tool mutator = tool("mutate", (call, context) -> {
			@SuppressWarnings("unchecked")
			List<Object> shared = (List<Object>) context.attributes().get("shared");
			shared.add("hijack");
			return CompletableFuture.completedFuture(ToolResult.text("done"));
		});
		var outcome = invoke(legacyAgent(mutator, "mutate"), seededRequest());
		assertEquals(List.of("original", "hijack"), outcome.outputs().get("shared"));
	}

	@Test
	void strictModeRejectsTopLevelStatePutThroughToolContext() throws Exception {
		var mutationFailure = new AtomicReference<Throwable>();
		Tool writer = tool("mutate", (call, context) -> {
			try {
				context.attributes().put("leak", "leaked");
			}
			catch (UnsupportedOperationException rejection) {
				mutationFailure.set(rejection);
			}
			return CompletableFuture.completedFuture(ToolResult.text("done"));
		});
		var outcome = invoke(strictAgent(writer, "mutate"), plainRequest());
		assertInstanceOf(UnsupportedOperationException.class, mutationFailure.get());
		assertTrue(!outcome.outputs().containsKey("leak"));
	}

	@Test
	void strictAdmitsConformingPatchThroughPolicy() throws Exception {
		Tool obedient = patchTool("obedient", ToolResult.text("done"), Map.of("count", 1));
		ReActAgent agent = ReActAgent.builder(scriptedClient("obedient"))
			.tools(new ToolRegistry(List.of(obedient)))
			.patchPolicy(ToolContextPatchPolicy.builder()
				.specFor("obedient", ToolContextPatchSpec.builder()
					.writableKey("count")
					.keyType("count", Integer.class)
					.build())
				.build())
			.strictToolState(true)
			.build();
		var outcome = invoke(agent, plainRequest());
		assertEquals(1, outcome.outputs().get("count"));
	}

	@Test
	void strictDiscardsFailedCallPatchEvenWhenPolicyWouldKeepIt() throws Exception {
		Tool failing = patchTool("failing", ToolResult.error("boom"), Map.of("leak", "leaked"));
		ReActAgent agent = ReActAgent.builder(scriptedClient("failing"))
			.tools(new ToolRegistry(List.of(failing)))
			.patchPolicy(ToolContextPatchPolicy.builder().discardPatchOnFailedResult(false).build())
			.strictToolState(true)
			.build();
		var outcome = invoke(agent, plainRequest());
		assertTrue(!outcome.outputs().containsKey("leak"));
		// The result message still reaches the conversation: the model must see failures.
		assertTrue(outcome.messages().stream().anyMatch(
				message -> message.role() == ChatRole.TOOL && "boom".equals(message.text())));
	}

	@Test
	void legacyKeepsFailedCallPatchWhenPolicyAllows() throws Exception {
		Tool failing = patchTool("failing", ToolResult.error("boom"), Map.of("leak", "leaked"));
		ReActAgent agent = ReActAgent.builder(scriptedClient("failing"))
			.tools(new ToolRegistry(List.of(failing)))
			.patchPolicy(ToolContextPatchPolicy.builder().discardPatchOnFailedResult(false).build())
			.build();
		var outcome = invoke(agent, plainRequest());
		assertEquals("leaked", outcome.outputs().get("leak"));
	}

	@Test
	void strictDiscardsReservedKeyPatchPerPolicy() throws Exception {
		Tool violating = patchTool("violating", ToolResult.text("done"),
				Map.of("messages", List.of("hijacked"), "business", "value"));
		ReActAgent agent = ReActAgent.builder(scriptedClient("violating"))
			.tools(new ToolRegistry(List.of(violating)))
			.patchPolicy(ToolContextPatchPolicy.builder().build())
			.strictToolState(true)
			.build();
		var outcome = invoke(agent, plainRequest());
		assertTrue(!outcome.outputs().containsKey("business"));
		assertTrue(outcome.messages().stream().anyMatch(message -> message.role() == ChatRole.TOOL));
	}

	@Test
	void strictAbortsOnReservedKeyPatchWhenPolicyAborts() {
		Tool violating = patchTool("violating", ToolResult.text("done"), Map.of("messages", List.of("hijacked")));
		ReActAgent agent = ReActAgent.builder(scriptedClient("violating"))
			.tools(new ToolRegistry(List.of(violating)))
			.patchPolicy(ToolContextPatchPolicy.builder()
				.onViolation(ToolContextPatchPolicy.ViolationPolicy.ABORT_RUN)
				.build())
			.strictToolState(true)
			.build();
		var failure = assertThrows(Exception.class, () -> invoke(agent, plainRequest()));
		var violation = findCause(failure, ToolContextPatchViolationException.class);
		assertEquals("RESERVED_KEY", violation.failure().errorCode());
	}

	// === harness ===

	private static AgentOutcome invoke(ReActAgent agent, AgentRequest request) throws Exception {
		return agent.invoke(request, context()).get(3, TimeUnit.SECONDS);
	}

	private static ReActAgent strictAgent(Tool tool, String toolName) {
		return ReActAgent.builder(scriptedClient(toolName)).tools(new ToolRegistry(List.of(tool)))
			.strictToolState(true)
			.build();
	}

	private static ReActAgent legacyAgent(Tool tool, String toolName) {
		return ReActAgent.builder(scriptedClient(toolName)).tools(new ToolRegistry(List.of(tool))).build();
	}

	private static AgentRequest plainRequest() {
		return AgentRequest.messages(List.of(ChatMessage.user("hello")));
	}

	private static AgentRequest seededRequest() {
		return new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("shared", new ArrayList<>(List.of("original"))),
				null);
	}

	private static ChatClient scriptedClient(String toolName) {
		ToolCall call = new ToolCall("call-1", toolName, "{}");
		return new ScriptedClient(List.of(
				response(ChatMessage.assistant("acting", List.of(call)), List.of(call), 1),
				response(ChatMessage.assistant("done"), List.of(), 1)));
	}

	private static Tool tool(String name, ToolExecutor executor) {
		return new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return executor.execute(call, context);
			}
		};
	}

	private static Tool patchTool(String name, ToolResult result, Map<String, Object> patch) {
		class PatchTool implements Tool, ToolContextPatchProvider {
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(result);
			}

			public Map<String, Object> contextPatch(ToolCall call, ToolResult toolResult) {
				return patch;
			}
		}
		return new PatchTool();
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run")
			.correlationId("correlation")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	private static ChatResponse response(ChatMessage message, List<ToolCall> calls, long total) {
		return new ChatResponse(null, "test", List.of(message), calls, FinishReason.STOP, new Usage(1L, 1L, total),
				Map.of());
	}

	private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
		Throwable current = failure;
		while (current != null) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause();
		}
		throw new AssertionError("expected " + type.getSimpleName() + " in cause chain, got " + failure);
	}

	@FunctionalInterface
	private interface ToolExecutor {

		CompletionStage<ToolResult> execute(ToolCall call, ToolContext context);

	}

	private static class ScriptedClient implements ChatClient {

		private final Queue<ChatResponse> responses;

		private ScriptedClient(List<ChatResponse> responses) {
			this.responses = new ArrayDeque<>(responses);
		}

		public synchronized CompletionStage<ChatResponse> call(ChatRequest request) {
			return CompletableFuture.completedFuture(responses.remove());
		}

		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException();
		}

		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
