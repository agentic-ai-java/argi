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
import io.github.agentic.ai.api.chat.ChatRole;
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
import io.github.agentic.ai.agent.interceptor.authorization.ToolAuthorizationPolicy;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.result.StructuredOutputFinalizer;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.ExecutionStoppedException;
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

	// ------------------------------------------------------------------
	// issue #270: interceptor-set system message reaches the model request
	// ------------------------------------------------------------------
	
	@Test
	void interceptorSystemMessageReachesTheModelRequest() throws Exception {
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "policy";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).systemMessage(ChatMessage.system("required-policy")).build());
			}
		};
		ReActAgent agent = ReActAgent.builder(client).interceptors(List.of(interceptor)).build();
	
		agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		List<ChatMessage> sent = client.requests.get(0).messages();
		assertEquals(ChatRole.SYSTEM, sent.get(0).role());
		assertEquals("required-policy", sent.get(0).text());
	}
	
	@Test
	void interceptorSystemMessageReplacesExistingSystemMessagesExactlyOnce() throws Exception {
		// A user-provided SYSTEM message in the conversation is replaced by the
		// interceptor-set one; exactly one SYSTEM entry is transmitted.
		AgentRequest withSystem = AgentRequest.messages(List.of(
			ChatMessage.system("user-provided-policy"), ChatMessage.user("hello")));
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "policy";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).systemMessage(ChatMessage.system("interceptor-policy")).build());
			}
		};
		ReActAgent agent = ReActAgent.builder(client).interceptors(List.of(interceptor)).build();
	
		agent.invoke(withSystem, context()).get(3, TimeUnit.SECONDS);
	
		List<ChatMessage> sent = client.requests.get(0).messages();
		assertEquals(1, sent.stream().filter(message -> message.role() == ChatRole.SYSTEM).count());
		assertEquals("interceptor-policy", sent.get(0).text());
		assertTrue(sent.stream().anyMatch(message -> "hello".equals(message.text())));
	}
	
	@Test
	void withoutInterceptorSystemMessageTheConversationIsUnchanged() throws Exception {
		AgentRequest withSystem = AgentRequest.messages(List.of(
			ChatMessage.system("user-provided-policy"), ChatMessage.user("hello")));
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		ReActAgent agent = ReActAgent.builder(client).build();
	
		agent.invoke(withSystem, context()).get(3, TimeUnit.SECONDS);
	
		List<ChatMessage> sent = client.requests.get(0).messages();
		assertEquals("user-provided-policy", sent.get(0).text());
		assertEquals("hello", sent.get(1).text());
	}
	
	@Test
	void systemMessageDoesNotAccumulateAcrossToolCallTurns() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("use", List.of(call)),
			List.of(call), 1), response(ChatMessage.assistant("done"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "policy";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).systemMessage(ChatMessage.system("required-policy")).build());
			}
		};
		Tool lookup = tool("lookup", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("lookup result")));
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(lookup)))
			.interceptors(List.of(interceptor)).build();
	
		agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		assertEquals(2, client.requests.size());
		for (ChatRequest sent : client.requests) {
			assertEquals(1, sent.messages().stream()
				.filter(message -> message.role() == ChatRole.SYSTEM).count());
			assertEquals("required-policy", sent.messages().get(0).text());
		}
	}
	
	@Test
	void streamingRunAlsoReceivesTheInterceptorSystemMessage() throws Exception {
		StreamingClient client = new StreamingClient();
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "policy";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).systemMessage(ChatMessage.system("required-policy")).build());
			}
		};
		ReActAgent agent = ReActAgent.builder(client).streamModel(true)
			.interceptors(List.of(interceptor)).build();
	
		try (AgentExecutionContext context = context()) {
			CompletableFuture<AgentOutcome> outcome = new CompletableFuture<>();
			agent.stream(request(), context).subscribe(new Flow.Subscriber<>() {
				public void onSubscribe(Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}
	
				public void onNext(AgentEvent event) {
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
			outcome.get(3, TimeUnit.SECONDS);
		}
		List<ChatMessage> sent = client.requests.get(0).messages();
		assertEquals(ChatRole.SYSTEM, sent.get(0).role());
		assertEquals("required-policy", sent.get(0).text());
	}
	
	@Test
	void nonSystemMessagesKeepTheirOrderAroundTheInjectedSystemMessage() throws Exception {
		AgentRequest multi = AgentRequest.messages(List.of(
			ChatMessage.user("first"), ChatMessage.assistant("reply"), ChatMessage.user("second")));
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "policy";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).systemMessage(ChatMessage.system("required-policy")).build());
			}
		};
		ReActAgent agent = ReActAgent.builder(client).interceptors(List.of(interceptor)).build();
	
		agent.invoke(multi, context()).get(3, TimeUnit.SECONDS);
	
		List<String> texts = client.requests.get(0).messages().stream()
			.map(ChatMessage::text).toList();
		assertEquals(List.of("required-policy", "first", "reply", "second"), texts);
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

	// ------------------------------------------------------------------
	// issue #268: interceptor tool selection must gate disclosure AND execution
	// ------------------------------------------------------------------
	
	@Test
	void unfilteredRunDisclosesStaticAndDynamicTools() throws Exception {
		Tool staticTool = tool("chosen", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("static")));
		Tool dynamicTool = tool("dyn", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("dynamic")));
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(staticTool)))
			.dynamicTools(state -> new ToolRegistry(List.of(dynamicTool))).build();
	
		agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		List<String> disclosed = client.requests.get(0).tools().stream()
			.map(ToolDefinition::name).toList();
		assertEquals(List.of("chosen", "dyn"), disclosed);
	}
	
	@Test
	void explicitEmptySelectionDisablesDisclosureAndExecution() throws Exception {
		ToolCall call = new ToolCall("call-1", "secret", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("try", List.of(call)),
			List.of(call), 1), response(ChatMessage.assistant("blocked"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "disable-tools";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).tools(List.of()).build());
			}
		};
		Tool secret = tool("secret", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("leaked")));
		AgentOutcome outcome = ReActAgent.builder(client).tools(new ToolRegistry(List.of(secret)))
			.interceptors(List.of(interceptor)).build().invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		// The model sees no tools at all.
		assertTrue(client.requests.get(0).tools().isEmpty());
		// A tool call for a filtered-out tool surfaces via the unknown-tool error path.
		assertEquals("blocked", outcome.messages().get(outcome.messages().size() - 1).text());
		assertTrue(outcome.messages().stream()
			.anyMatch(message -> message.text().contains("Unknown tool: secret")));
	}
	
	@Test
	void subsetSelectionDisclosesAndExecutesOnlyChosenTools() throws Exception {
		ToolCall call = new ToolCall("call-1", "chosen", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("use", List.of(call)),
			List.of(call), 1), response(ChatMessage.assistant("done"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "subset";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).tools(List.of("chosen")).build());
			}
		};
		Tool chosen = tool("chosen", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("chosen result")));
		Tool secret = tool("secret", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("leaked")));
		AgentOutcome outcome = ReActAgent.builder(client).tools(new ToolRegistry(List.of(chosen, secret)))
			.interceptors(List.of(interceptor)).build().invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		List<String> disclosed = client.requests.get(0).tools().stream()
			.map(ToolDefinition::name).toList();
		assertEquals(List.of("chosen"), disclosed);
		assertTrue(outcome.messages().stream().anyMatch(message -> "chosen result".equals(message.text())));
		assertTrue(outcome.messages().stream().noneMatch(message -> "leaked".equals(message.text())));
	}
	
	@Test
	void dynamicToolsParticipateInSelection() throws Exception {
		Tool dynamic = tool("dyn", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("dynamic result")));
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "dynamic-only";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).tools(List.of("dyn")).build());
			}
		};
		ReActAgent agent = ReActAgent.builder(client).dynamicTools(state -> new ToolRegistry(List.of(dynamic)))
			.interceptors(List.of(interceptor)).build();
	
		agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		List<String> disclosed = client.requests.get(0).tools().stream()
			.map(ToolDefinition::name).toList();
		assertEquals(List.of("dyn"), disclosed);
	}
	
	@Test
	void staticToolShadowsDynamicToolOnNameConflict() throws Exception {
		AtomicInteger staticExecutions = new AtomicInteger();
		AtomicInteger dynamicExecutions = new AtomicInteger();
		Tool staticTool = tool("lookup", "{}", (ignored, context) -> {
			staticExecutions.incrementAndGet();
			return CompletableFuture.completedFuture(ToolResult.text("original"));
		});
		Tool dynamicTool = tool("lookup", "{}", (ignored, context) -> {
			dynamicExecutions.incrementAndGet();
			return CompletableFuture.completedFuture(ToolResult.text("replacement"));
		});
		ToolCall call = new ToolCall("call-1", "lookup", "{}");
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("use", List.of(call)),
			List.of(call), 1), response(ChatMessage.assistant("done"), List.of(), 1)));
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(staticTool)))
			.dynamicTools(state -> new ToolRegistry(List.of(dynamicTool))).build();
	
		agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		// Static bindings shadow same-named dynamic disclosures (getTools() semantics).
		assertEquals(1, staticExecutions.get());
		assertEquals(0, dynamicExecutions.get());
	}
	
	@Test
	void streamingRunDisclosesTheSameFilteredSetAsNonStreaming() throws Exception {
		Tool secret = tool("secret", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("leaked")));
		StreamingClient client = new StreamingClient();
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "disable-tools";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).tools(List.of()).build());
			}
		};
		ReActAgent agent = ReActAgent.builder(client).streamModel(true).tools(new ToolRegistry(List.of(secret)))
			.interceptors(List.of(interceptor)).build();
	
		try (AgentExecutionContext context = context()) {
			CompletableFuture<AgentOutcome> outcome = new CompletableFuture<>();
			agent.stream(request(), context).subscribe(new Flow.Subscriber<>() {
				public void onSubscribe(Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}
	
				public void onNext(AgentEvent event) {
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
			outcome.get(3, TimeUnit.SECONDS);
		}
		assertTrue(client.requests.get(0).tools().isEmpty());
	}
	
	@Test
	void selectionDoesNotLeakAcrossSequentialRuns() throws Exception {
		ToolCall chosenCall = new ToolCall("call-1", "chosen", "{}");
		ScriptedClient client = new ScriptedClient(List.of(
			response(ChatMessage.assistant("use", List.of(chosenCall)), List.of(chosenCall), 1),
			response(ChatMessage.assistant("done"), List.of(), 1),
			response(ChatMessage.assistant("use", List.of(chosenCall)), List.of(chosenCall), 1),
			response(ChatMessage.assistant("done"), List.of(), 1)));
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "subset";
			}
	
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(ModelRequest.builder(request).tools(List.of("chosen")).build());
			}
		};
		Tool chosen = tool("chosen", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("chosen result")));
		Tool secret = tool("secret", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("leaked")));
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(chosen, secret)))
			.interceptors(List.of(interceptor)).build();
	
		AgentOutcome first = agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
		AgentOutcome second = agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		for (AgentOutcome outcome : List.of(first, second)) {
			assertTrue(outcome.messages().stream().anyMatch(message -> "chosen result".equals(message.text())));
			assertTrue(outcome.messages().stream().noneMatch(message -> "leaked".equals(message.text())));
		}
	}
	
	@Test
	void unfilteredRunWithNoDynamicToolsKeepsRegistryFastPath() throws Exception {
		Tool staticTool = tool("only", "{}", (ignored, context) ->
			CompletableFuture.completedFuture(ToolResult.text("ok")));
		ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("answer"), List.of(), 1)));
		ReActAgent agent = ReActAgent.builder(client).tools(new ToolRegistry(List.of(staticTool))).build();
	
		agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);
	
		List<String> disclosed = client.requests.get(0).tools().stream()
			.map(ToolDefinition::name).toList();
		assertEquals(List.of("only"), disclosed);
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
						io.github.agentic.ai.agent.tool.patch.ToolContextPatchSpec.builder().writableKey("count")
							.keyType("count", Integer.class).build()).build())
				.build().invoke(request(), context()).get(3, TimeUnit.SECONDS);
		assertEquals(1, outcome.outputs().get("count"));
	}

	@Test
	void stoppedControlSkipsOutputRepairAndPropagatesTheStop() throws Exception {
		try (AgentExecutionContext context = context()) {
			ScriptedClient client = new ScriptedClient(List.of(response(ChatMessage.assistant("not json"), List.of(), 1))) {
				public synchronized CompletionStage<ChatResponse> call(ChatRequest request) {
					context.control().cancel(); // the run is stopped while its terminal turn is produced
					return super.call(request);
				}
			};
			AtomicInteger repairs = new AtomicInteger();
			ChatClient repairClient = new ChatClient() {
				public CompletionStage<ChatResponse> call(ChatRequest request) {
					repairs.incrementAndGet();
					throw new AssertionError("the repair client must not be called");
				}

				public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
					throw new UnsupportedOperationException();
				}

				public ChatClientCapabilities capabilities() {
					return ChatClientCapabilities.basic();
				}
			};
			ReActAgent agent = ReActAgent.builder(client)
					.outputSchema("{\"type\":\"object\",\"required\":[\"status\"],"
							+ "\"additionalProperties\":false,\"properties\":{\"status\":{\"type\":\"string\"}}}")
					.outputFinalizer(new StructuredOutputFinalizer(repairClient))
					.build();

			ExecutionStoppedException stopped = assertThrows(ExecutionStoppedException.class,
					() -> agent.invoke(request(), context).get(3, TimeUnit.SECONDS));

			assertEquals(ExecutionStoppedException.Reason.CANCELLED, stopped.reason());
			assertEquals(0, repairs.get());
		}
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
