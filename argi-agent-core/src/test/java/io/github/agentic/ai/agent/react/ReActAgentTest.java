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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
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
import io.github.agentic.ai.graph.agent.interceptor.authorization.ToolAuthorizationPolicy;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentTest {

	@Test
	void runsMultipleTurnsAndMapsToolResultsIntoMessages() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("use tool", List.of(call)),
				List.of(call), 1), response(ChatMessage.assistant("final"), List.of(), 2)));
		AtomicInteger executions = new AtomicInteger();
		Tool tool = tool("lookup", "{\"type\":\"object\"}", (ignored, context) -> {
			executions.incrementAndGet();
			assertTrue(context.attributes().isEmpty());
			return CompletableFuture.completedFuture(ToolResult.text("tool answer"));
		});
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(tool))).build();
		AgentOutcome outcome = agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
		assertEquals("final", outcome.messages().get(outcome.messages().size() - 1).text());
		assertEquals("tool answer", outcome.messages().get(2).text());
		assertEquals(1, executions.get());
		assertEquals(3L, outcome.usage().totalTokens());
		assertEquals(2, client.requests.size());
	}

	@Test
	void rejectsInvalidArgumentsWithoutExecutingTheTool() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{\"query\":1}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("use", List.of(call)),
				List.of(call), 1), response(ChatMessage.assistant("recovered"), List.of(), 1)));
		AtomicInteger executions = new AtomicInteger();
		Tool tool = tool("lookup", "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}}}",
				(ignored, context) -> {
					executions.incrementAndGet();
					return CompletableFuture.completedFuture(ToolResult.text("unexpected"));
				});
		AgentOutcome outcome = ReActAgent.builder(client).tools(new ToolRegistry(List.of(tool)))
				.validateToolArguments(true).build().invoke(request(), context()).get(3, TimeUnit.SECONDS);
		assertEquals("recovered", outcome.messages().get(outcome.messages().size() - 1).text());
		assertEquals(0, executions.get());
		assertTrue(outcome.messages().get(2).metadata().containsKey("errorCode"));
	}

	@Test
	void toolFailureIsDeliveredAsModelVisibleErrorAndInterceptorsWrapTheTurn() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("use", List.of(call)),
				List.of(call), 1), response(ChatMessage.assistant("recovered"), List.of(), 1)));
		AtomicInteger interceptorCalls = new AtomicInteger();
		Tool failingTool = tool("lookup", "{}", (ignored, context) -> CompletableFuture.failedFuture(
				new IllegalStateException("backend unavailable")));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "test-interceptor";
			}

			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				interceptorCalls.incrementAndGet();
				return next.call(request);
			}
		};
		AgentOutcome outcome = ReActAgent.builder(client).tools(new ToolRegistry(List.of(failingTool)))
				.interceptors(List.of(interceptor)).build().invoke(request(), context()).get(3, TimeUnit.SECONDS);
		assertEquals("recovered", outcome.messages().get(outcome.messages().size() - 1).text());
		assertTrue(outcome.messages().stream().anyMatch(message -> Boolean.TRUE.equals(message.metadata().get("tool.error"))));
		assertEquals(2, interceptorCalls.get());
	}

	@Test
	void emitsStreamingDeltasAndSupportsDynamicTools() throws Exception {
		Tool dynamic = tool("dynamic", "{\"type\":\"object\"}",
				(ignored, context) -> CompletableFuture.completedFuture(ToolResult.text("dynamic result")));
		StreamingClient client = new StreamingClient();
		ReActAgent agent = ReActAgent.builder(client).streamModel(true).dynamicTools(state -> new ToolRegistry(List.of(dynamic)))
				.build();
		List<AgentEvent> events = new ArrayList<>();
		try (AgentExecutionContext context = context()) {
			CompletableFuture<AgentOutcome> outcome = new CompletableFuture<>();
			agent.stream(request(), context).subscribe(new Flow.Subscriber<>() {
				public void onSubscribe(Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}

				public void onNext(AgentEvent event) {
					events.add(event);
					if (event instanceof AgentEvent.Finished finished) {
						outcome.complete(finished.outcome());
					}
				}

				public void onError(Throwable error) {
					outcome.completeExceptionally(error);
				}

				public void onComplete() {
				}
			});
			assertEquals("answer", outcome.get(3, TimeUnit.SECONDS).messages().get(1).text());
		}
		assertTrue(client.requests.get(0).tools().stream().anyMatch(tool -> tool.name().equals("dynamic")));
		assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.MessageDelta));
	}

	@Test
	void deniedToolCanSuspendForHumanApproval() throws Exception {
		ToolCall call = new ToolCall("call-1", "danger", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("approve", List.of(call)),
				List.of(call), 1)));
		AgentOutcome.Suspension suspension = new AgentOutcome.Suspension("resume-1", Map.of("tool", "danger"));
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(tool("danger", "{}", (c, x) -> {
			throw new AssertionError("denied tool was executed");
		})))).authorizationPolicy(ToolAuthorizationPolicy.denyAll())
				.humanInTheLoop((request, context) -> suspension).build();
		AgentOutcome outcome = agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
		assertEquals(AgentOutcome.Status.SUSPENDED, outcome.status());
		assertSame(suspension, outcome.suspension());
	}

	@Test
	void cancellationCancelsAnInFlightModelCall() throws Exception {
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		AtomicReference<ChatRequest> request = new AtomicReference<>();
		ChatClient client = new ChatClient() {
			public CompletionStage<ChatResponse> call(ChatRequest value) {
				request.set(value);
				return pending;
			}

			public Flow.Publisher<ChatEvent> stream(ChatRequest value) {
				throw new UnsupportedOperationException();
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
		try (AgentExecutionContext context = context()) {
			CompletableFuture<AgentOutcome> result = ReActAgent.builder(client).build().invoke(request(), context);
			while (request.get() == null) {
				Thread.sleep(5);
			}
			assertTrue(result.cancel(true));
			assertTrue(pending.isCancelled());
			assertThrows(RuntimeException.class, context.control()::assertActive);
		}
	}

	@Test
	void admitsValidatedToolContextPatch() throws Exception {
		ToolCall call = new ToolCall("call-1", "write", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("write", List.of(call)),
				List.of(call), 1), response(ChatMessage.assistant("done"), List.of(), 1)));
		class PatchTool implements Tool, ToolContextPatchProvider {
			public ToolDefinition definition() {
				return new ToolDefinition("write", "write", "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("ok"));
			}

			public Map<String, Object> contextPatch(ToolCall call, ToolResult result) {
				return Map.of("count", 1);
			}
		}
		Tool tool = new PatchTool();
		AgentOutcome outcome = ReActAgent.builder(client).tools(new ToolRegistry(List.of(tool)))
				.patchPolicy(ToolContextPatchPolicy.builder().specFor("write",
						io.github.agentic.ai.graph.agent.tool.patch.ToolContextPatchSpec.builder().writableKey("count")
							.keyType("count", Integer.class).build()).build())
				.build().invoke(request(), context()).get(3, TimeUnit.SECONDS);
		assertEquals(1, outcome.outputs().get("count"));
	}

	private static AgentRequest request() {
		return AgentRequest.messages(List.of(ChatMessage.user("hello")));
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
				.tenant(new TenantReference("test", "tenant"))
				.principal(new PrincipalReference("test", "principal"))
				.runId("run").correlationId("correlation").budget(ExecutionBudget.unlimited())
				.delegationScope(DelegationScope.none()).build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	private static Tool tool(String name, String schema, ToolExecutor executor) {
		return new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, schema);
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return executor.execute(call, context);
			}
		};
	}

	private static ChatResponse response(ChatMessage message, List<ToolCall> calls, long total) {
		return new ChatResponse(null, "test", List.of(message), calls, FinishReason.STOP,
				new Usage(1L, 1L, total), Map.of());
	}

	@FunctionalInterface
	private interface ToolExecutor {
		CompletionStage<ToolResult> execute(ToolCall call, ToolContext context);
	}

	private static class ScriptedClient implements ChatClient {

		protected final Queue<ChatResponse> responses;

		protected final List<ChatRequest> requests = new ArrayList<>();

		private ScriptedClient(List<ChatResponse> responses) {
			this.responses = new ArrayDeque<>(responses);
		}

		public synchronized CompletionStage<ChatResponse> call(ChatRequest request) {
			requests.add(request);
			return CompletableFuture.completedFuture(responses.remove());
		}

		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException();
		}

		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}
	}

	private static final class StreamingClient extends ScriptedClient {

		private StreamingClient() {
			super(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		}

		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			requests.add(request);
			return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					ChatEvent delta = ChatEvent.textDelta("answer");
					subscriber.onNext(delta);
					ChatEvent response = ChatEvent.response(responses.remove());
					subscriber.onNext(response);
					subscriber.onComplete();
				}

				public void cancel() {
				}
			});
		}
	}

}
