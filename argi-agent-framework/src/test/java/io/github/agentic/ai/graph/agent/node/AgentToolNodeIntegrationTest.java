/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.tools.ToolContextHelper;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;
import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_CONTEXT_KEY;
import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for AgentToolNode covering apply(), sequential execution, partial
 * response handling, interceptor chain, and tool resolution.
 *
 * @author Claude
 * @since 1.0.0
 */
@DisplayName("AgentToolNode Integration Tests")
class AgentToolNodeIntegrationTest {

	private AgentToolNode.Builder baseBuilder;

	@BeforeEach
	void setUp() {
		baseBuilder = AgentToolNode.builder()
			.agentName("test-agent")
			.toolExecutionTimeout(Duration.ofSeconds(5))
			.throwOnToolError(false);
	}

	@Nested
	@DisplayName("apply() Method Tests")
	class ApplyMethodTests {

		@Test
		@DisplayName("should execute single tool and return updated state")
		void apply_shouldExecuteSingleTool_andReturnUpdatedState() throws Exception {
			// Given
			Tool echoTool = createSimpleTool("echo", args -> "Echo: " + args);
			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(echoTool))).build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "echo", "{\"message\":\"hello\"}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then
			assertNotNull(result);
			assertTrue(result.containsKey("messages"));
			List<ChatMessage> responseMessage = messages(result);
			assertEquals(1, responseMessage.size());
			assertEquals("Echo: {\"message\":\"hello\"}", responseMessage.get(0).text());
		}

		@Test
		@DisplayName("should execute multiple tools sequentially when parallel disabled")
		void apply_shouldExecuteMultipleToolsSequentially_whenParallelDisabled() throws Exception {
			// Given
			AtomicInteger executionOrder = new AtomicInteger(0);
			List<Integer> orderRecord = new ArrayList<>();

			Tool tool1 = createSimpleTool("tool1", args -> {
				orderRecord.add(executionOrder.incrementAndGet());
				return "result1";
			});
			Tool tool2 = createSimpleTool("tool2", args -> {
				orderRecord.add(executionOrder.incrementAndGet());
				return "result2";
			});

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2)))
				.parallelToolExecution(false)
				.build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(createToolCall("call-1", "tool1", "{}"),
					createToolCall("call-2", "tool2", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then
			List<ChatMessage> responseMessage = messages(result);
			assertEquals(2, responseMessage.size());
			assertEquals(List.of(1, 2), orderRecord, "Tools should execute in order");
		}

		@Test
		@DisplayName("should execute tools in parallel when enabled")
		void apply_shouldExecuteToolsInParallel_whenEnabled() throws Exception {
			// Given
			CountDownLatch startLatch = new CountDownLatch(2);
			CountDownLatch continueLatch = new CountDownLatch(1);

			Tool tool1 = createSimpleTool("tool1", args -> {
				startLatch.countDown();
				try {
					continueLatch.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "result1";
			});
			Tool tool2 = createSimpleTool("tool2", args -> {
				startLatch.countDown();
				try {
					continueLatch.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "result2";
			});

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2)))
				.parallelToolExecution(true)
				.maxParallelTools(5)
				.build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(createToolCall("call-1", "tool1", "{}"),
					createToolCall("call-2", "tool2", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			ExecutorService invocationExecutor = Executors.newSingleThreadExecutor();
			ExecutorService toolExecutor = Executors.newFixedThreadPool(2);
			RunnableConfig config = RunnableConfig.builder()
				.addParallelNodeExecutor(RunnableConfig.AGENT_TOOL_NAME, toolExecutor)
				.build();

			// When
			try {
				CompletableFuture<Map<String, Object>> futureResult = CompletableFuture.supplyAsync(() -> {
					try {
						return node.apply(state, config);
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
				}, invocationExecutor);

				// Wait for both tools to start (proves parallel execution)
				boolean bothStarted = startLatch.await(5, TimeUnit.SECONDS);
				assertTrue(bothStarted, "Both tools should start in parallel");

				// Allow tools to complete
				continueLatch.countDown();

				// Then
				Map<String, Object> result = futureResult.get(5, TimeUnit.SECONDS);
				List<ChatMessage> responseMessage = messages(result);
				assertEquals(2, responseMessage.size());
			}
			finally {
				continueLatch.countDown();
				invocationExecutor.shutdownNow();
				toolExecutor.shutdownNow();
			}
		}

		@Test
		@DisplayName("should inject state for Tool")
		void apply_shouldInjectState_forTool() throws Exception {
			// Given
			AtomicReference<OverAllState> capturedState = new AtomicReference<>();
			AtomicReference<RunnableConfig> capturedConfig = new AtomicReference<>();
			AtomicReference<Map<String, Object>> capturedUpdateMap = new AtomicReference<>();

			Tool stateAwareTool = contextualTool("stateAware", (call, context) -> {
				capturedState.set((OverAllState) context.attributes().get(AGENT_STATE_CONTEXT_KEY));
				capturedConfig.set((RunnableConfig) context.attributes().get(AGENT_CONFIG_CONTEXT_KEY));
				capturedUpdateMap.set(updates(context));
				return CompletableFuture.completedFuture(ToolResult.text("state-aware-result"));
			});

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(stateAwareTool))).build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "stateAware", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			node.apply(state, config);

			// Then
			assertNotNull(capturedState.get(), "State should be injected");
			assertNotNull(capturedConfig.get(), "Config should be injected");
			assertNotNull(capturedUpdateMap.get(), "Update map should be injected");
		}

		@ParameterizedTest
		@CsvSource({ "false, false", "false, true", "true, false", "true, true" })
		@DisplayName("should replace forged config metadata for neutral Tool")
		void apply_shouldReplaceForgedConfigMetadata_forNeutralTool(boolean parallel, boolean rewriteContext)
				throws Exception {
			TrustedExecutionContext trusted = trustedContext("tenant-real", "principal-real");
			TrustedExecutionContext forged = trustedContext("tenant-forged", "principal-forged");
			List<TrustedExecutionContext> captured = new CopyOnWriteArrayList<>();
			Tool plainTool = contextualTool("plain", (call, context) -> {
				captured.add(ToolContextHelper.getTrustedExecutionContext(context).orElseThrow());
				return CompletableFuture.completedFuture(ToolResult.text("plain-result"));
			});
			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(plainTool)))
				.parallelToolExecution(parallel)
				.build();
			OverAllState state = createStateWithMessages(createAssistantMessageWithToolCalls(
					createToolCall("call-1", "plain", "{}"), createToolCall("call-2", "plain", "{}")));
			RunnableConfig forgedConfig = RunnableConfig.builder().trustedExecutionContext(forged).build();
			RunnableConfig config = RunnableConfig.builder()
				.trustedExecutionContext(trusted)
				.addMetadata(AGENT_CONFIG_CONTEXT_KEY, forgedConfig)
				.build();
			if (rewriteContext) {
				node.setToolInterceptors(List.of(new ToolInterceptor() {
					@Override
					public String getName() {
						return "rewriteContext";
					}

					@Override
					public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
						return handler.call(ToolCallRequest.builder(request)
							.context(Map.of(AGENT_CONFIG_CONTEXT_KEY, forgedConfig))
							.build());
					}
				}));
			}

			node.apply(state, config);

			assertEquals(List.of(trusted, trusted), captured);
		}

		@Test
		@DisplayName("should throw exception for non-ChatMessage and non-ToolResponseMessage")
		void apply_shouldThrowException_forInvalidMessageType() {
			// Given
			Tool tool = createSimpleTool("tool", args -> "result");
			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool))).build();

			// Create state with a user message (invalid)
			Map<String, Object> stateData = new HashMap<>();
			stateData.put("messages", List.of(ChatMessage.user("test")));
			OverAllState state = new OverAllState(stateData);

			RunnableConfig config = RunnableConfig.builder().build();

			// When/Then
			assertThrows(IllegalStateException.class, () -> node.apply(state, config));
		}

	}

	private TrustedExecutionContext trustedContext(String tenant, String principal) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("example-iam", tenant))
			.principal(new PrincipalReference("example-iam", principal))
			.runId("run-a")
			.correlationId("correlation-a")
			.delegationScope(DelegationScope.of(Set.of("tools:read")))
			.build();
	}

	@Nested
	@DisplayName("Sequential Execution Tests")
	class SequentialExecutionTests {

		@Test
		@DisplayName("should degrade gracefully when tool callback cannot be resolved")
		void apply_shouldReturnUnavailableToolResponse_whenToolMissing() throws Exception {
			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of())).build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "fuyao-web-search", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			Map<String, Object> result = node.apply(state, config);

			List<ChatMessage> responseMessage = messages(result);
			assertEquals(1, responseMessage.size());
			ChatMessage response = responseMessage.get(0);
			assertEquals("call-1", response.toolCallId());
			assertEquals("fuyao-web-search", response.toolName());
			assertEquals("Tool not available: fuyao-web-search", response.text());
		}

		@Test
		@DisplayName("should maintain tool response order in sequential mode")
		void executeSequential_shouldMaintainOrder() throws Exception {
			// Given
			Tool tool1 = createSimpleTool("tool1", args -> "result1");
			Tool tool2 = createSimpleTool("tool2", args -> "result2");
			Tool tool3 = createSimpleTool("tool3", args -> "result3");

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2, tool3)))
				.parallelToolExecution(false)
				.build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(createToolCall("call-1", "tool1", "{}"),
					createToolCall("call-2", "tool2", "{}"), createToolCall("call-3", "tool3", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then
			List<ChatMessage> responseMessage = messages(result);
			List<ChatMessage> responses = responseMessage;
			assertEquals(3, responses.size());
			assertEquals("call-1", responses.get(0).toolCallId());
			assertEquals("call-2", responses.get(1).toolCallId());
			assertEquals("call-3", responses.get(2).toolCallId());
		}

		@Test
		@DisplayName("should accumulate state updates from all tools")
		void executeSequential_shouldAccumulateStateUpdates() throws Exception {
			// Given
			Tool tool1 = createToolWithUpdate("tool1", "key1", "value1");
			Tool tool2 = createToolWithUpdate("tool2", "key2", "value2");

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2)))
				.parallelToolExecution(false)
				.build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(createToolCall("call-1", "tool1", "{}"),
					createToolCall("call-2", "tool2", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then
			assertEquals("value1", result.get("key1"));
			assertEquals("value2", result.get("key2"));
		}

	}

	@Nested
	@DisplayName("Partial Response Handling Tests")
	class PartialResponseHandlingTests {

		@Test
		@DisplayName("should resume from partial tool responses")
		void handlePartialResponses_shouldResume() throws Exception {
			// Given
			Tool tool1 = createSimpleTool("tool1", args -> "result1");
			Tool tool2 = createSimpleTool("tool2", args -> "result2");
			Tool tool3 = createSimpleTool("tool3", args -> "result3");

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2, tool3)))
				.parallelToolExecution(false)
				.build();

			// Create assistant message with 3 tool calls
			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(createToolCall("call-1", "tool1", "{}"),
					createToolCall("call-2", "tool2", "{}"), createToolCall("call-3", "tool3", "{}"));

			// Create partial response (only tool1 completed)
			ChatMessage partialResponse = ChatMessage.tool("call-1", "tool1", "result1");

			// State with both messages (last message is partial response)
			Map<String, Object> stateData = new HashMap<>();
			List<ChatMessage> messages = new ArrayList<>();
			messages.add(assistantMessage);
			messages.add(partialResponse);
			stateData.put("messages", messages);
			OverAllState state = new OverAllState(stateData);

			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then
			assertTrue(result.containsKey("messages"));
			// The result should contain merged responses for all tools
			Object messagesResult = result.get("messages");
			assertTrue(messagesResult instanceof List, "Messages should be a list with merged response and removal");
		}

		@Test
		@DisplayName("should return empty map when all tools already completed")
		void handlePartialResponses_shouldReturnEmpty_whenAllCompleted() throws Exception {
			// Given
			Tool tool1 = createSimpleTool("tool1", args -> "result1");

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1)))
				.parallelToolExecution(false)
				.build();

			// Create assistant message with 1 tool call
			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(createToolCall("call-1", "tool1", "{}"));

			// Create complete response (all tools completed)
			ChatMessage completeResponse = ChatMessage.tool("call-1", "tool1", "result1");

			Map<String, Object> stateData = new HashMap<>();
			List<ChatMessage> messages = new ArrayList<>();
			messages.add(assistantMessage);
			messages.add(completeResponse);
			stateData.put("messages", messages);
			OverAllState state = new OverAllState(stateData);

			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then
			assertTrue(result.isEmpty(), "Should return empty map when all tools completed");
		}

	}

	@Nested
	@DisplayName("Interceptor Chain Integration Tests")
	class InterceptorChainTests {

		@Test
		@DisplayName("should execute interceptors before and after tool")
		void interceptorChain_shouldExecuteBeforeAndAfter() throws Exception {
			// Given
			List<String> executionOrder = new ArrayList<>();

			ToolInterceptor interceptor = new ToolInterceptor() {
				@Override
				public String getName() {
					return "testInterceptor";
				}

				@Override
				public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
					executionOrder.add("before-" + request.getToolName());
					ToolCallResponse response = handler.call(request);
					executionOrder.add("after-" + request.getToolName());
					return response;
				}
			};

			Tool tool = createSimpleTool("testTool", args -> {
				executionOrder.add("execute-testTool");
				return "result";
			});

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool))).build();
			node.setToolInterceptors(List.of(interceptor));

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "testTool", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			node.apply(state, config);

			// Then
			assertEquals(List.of("before-testTool", "execute-testTool", "after-testTool"), executionOrder);
		}

		@Test
		@DisplayName("should allow request modification in interceptor")
		void interceptorChain_shouldAllowRequestModification() throws Exception {
			// Given
			AtomicReference<String> capturedArgs = new AtomicReference<>();

			ToolInterceptor modifyingInterceptor = new ToolInterceptor() {
				@Override
				public String getName() {
					return "modifyingInterceptor";
				}

				@Override
				public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
					// Modify the request arguments
					ToolCallRequest modifiedRequest = ToolCallRequest.builder(request)
						.arguments("{\"modified\":true}")
						.build();
					return handler.call(modifiedRequest);
				}
			};

			Tool tool = createSimpleTool("testTool", args -> {
				capturedArgs.set(args);
				return "result";
			});

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool))).build();
			node.setToolInterceptors(List.of(modifyingInterceptor));

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "testTool", "{\"original\":true}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			node.apply(state, config);

			// Then
			assertEquals("{\"modified\":true}", capturedArgs.get());
		}

		@Test
		@DisplayName("should chain multiple interceptors in order")
		void interceptorChain_shouldChainMultipleInterceptors() throws Exception {
			// Given
			List<String> executionOrder = new ArrayList<>();

			ToolInterceptor interceptor1 = new ToolInterceptor() {
				@Override
				public String getName() {
					return "interceptor1";
				}

				@Override
				public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
					executionOrder.add("interceptor1-before");
					ToolCallResponse response = handler.call(request);
					executionOrder.add("interceptor1-after");
					return response;
				}
			};

			ToolInterceptor interceptor2 = new ToolInterceptor() {
				@Override
				public String getName() {
					return "interceptor2";
				}

				@Override
				public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
					executionOrder.add("interceptor2-before");
					ToolCallResponse response = handler.call(request);
					executionOrder.add("interceptor2-after");
					return response;
				}
			};

			Tool tool = createSimpleTool("testTool", args -> {
				executionOrder.add("execute");
				return "result";
			});

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool))).build();
			node.setToolInterceptors(List.of(interceptor1, interceptor2));

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "testTool", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			node.apply(state, config);

			// Then
			assertEquals(List.of("interceptor1-before", "interceptor2-before", "execute", "interceptor2-after",
					"interceptor1-after"), executionOrder);
		}

	}

	@Nested
	@DisplayName("Tool Resolution Tests")
	class ToolResolutionTests {

		@Test
		void resolveUsesTurnBindingSnapshotBeforeConfiguredRegistry() throws Exception {
			Tool resolvedTool = createSimpleTool("dynamicTool", args -> "resolved-result");
			AgentToolBindingStore store = new AgentToolBindingStore();
			RunnableConfig config = RunnableConfig.builder().build();
			var configured = new AgentToolBindings(new ToolRegistry(List.of()));
			var selected = new AgentToolBindings(new ToolRegistry(List.of(resolvedTool)));
			var assistant = store.attach(
					createAssistantMessageWithToolCalls(createToolCall("call-1", "dynamicTool", "{}")), selected,
					configured);
			var node = baseBuilder.toolRegistry(new ToolRegistry(List.of())).toolBindingStore(store).build();
			var result = node.apply(createStateWithMessages(assistant), config);
			assertEquals("resolved-result", messages(result).get(0).text());
		}

		@Test
		@DisplayName("should return unavailable response when tool not found anywhere")
		void resolve_shouldReturnUnavailableResponse_whenToolNotFound() throws Exception {
			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of())).build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "nonExistentTool", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			Map<String, Object> result = node.apply(state, config);

			List<ChatMessage> responseMessage = messages(result);
			assertEquals(1, responseMessage.size());
			ChatMessage response = responseMessage.get(0);
			assertEquals("call-1", response.toolCallId());
			assertEquals("nonExistentTool", response.toolName());
			assertEquals("Tool not available: nonExistentTool", response.text());
		}

	}

	@Nested
	@DisplayName("Sync Tool Execution Tests")
	class SyncToolExecutionTests {

		@Test
		@DisplayName("should handle generic exceptions")
		void executeSyncTool_shouldHandleGenericException() throws Exception {
			// Given
			Tool failingTool = createSimpleTool("failingTool", args -> {
				throw new RuntimeException("Unexpected error");
			});

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(failingTool))).build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "failingTool", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then - should return error response
			List<ChatMessage> responseMessage = messages(result);
			assertNotNull(responseMessage);
			assertEquals("execution", responseMessage.get(0).metadata().get("failureKind"));
			assertTrue(responseMessage.get(0).text().contains("Unexpected error"));
		}

	}

	@Nested
	@DisplayName("Async Tool Execution Tests")
	class AsyncToolExecutionIntegrationTests {

		@Test
		@DisplayName("should execute async tool successfully")
		void executeAsyncTool_shouldSucceed() throws Exception {
			// Given
			Tool asyncTool = contextualTool("asyncTool",
					(call, context) -> CompletableFuture.completedFuture(ToolResult.text("async-result")));

			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(asyncTool))).build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "asyncTool", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			// When
			Map<String, Object> result = node.apply(state, config);

			// Then
			List<ChatMessage> responseMessage = messages(result);
			assertEquals("async-result", responseMessage.get(0).text());
		}

		@Test
		void executeAsyncToolTimeoutCancelsReturnedStage() throws Exception {
			var source = new CompletableFuture<ToolResult>();
			var tool = contextualTool("cancellableTool", (call, context) -> source);
			var node = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool)))
				.toolExecutionTimeout(Duration.ofMillis(100))
				.build();
			var result = node.apply(
					createStateWithMessages(
							createAssistantMessageWithToolCalls(createToolCall("call-1", "cancellableTool", "{}"))),
					RunnableConfig.builder().build());
			assertTrue(source.isCancelled());
			assertEquals("timeout", messages(result).get(0).metadata().get("failureKind"));
		}

		@Test
		@DisplayName("should degrade gracefully when tool callback cannot be resolved")
		void apply_shouldReturnUnavailableToolResponse_whenToolMissing() throws Exception {
			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of())).build();

			ChatMessage assistantMessage = createAssistantMessageWithToolCalls(
					createToolCall("call-1", "fuyao-web-search", "{}"));

			OverAllState state = createStateWithMessages(assistantMessage);
			RunnableConfig config = RunnableConfig.builder().build();

			Map<String, Object> result = node.apply(state, config);

			List<ChatMessage> responseMessage = messages(result);
			assertEquals(1, responseMessage.size());
			ChatMessage response = responseMessage.get(0);
			assertEquals("call-1", response.toolCallId());
			assertEquals("fuyao-web-search", response.toolName());
			assertEquals("Tool not available: fuyao-web-search", response.text());
		}

	}

	private Tool createSimpleTool(String name, Function<String, String> logic) {
		return contextualTool(name,
				(call, context) -> CompletableFuture.completedFuture(ToolResult.text(logic.apply(call.arguments()))));
	}

	private Tool createToolWithUpdate(String name, String updateKey, String updateValue) {
		return contextualTool(name, (call, context) -> {
			updates(context).put(updateKey, updateValue);
			return CompletableFuture.completedFuture(ToolResult.text("result-" + name));
		});
	}

	private Tool contextualTool(String name,
			java.util.function.BiFunction<ToolCall, ToolContext, java.util.concurrent.CompletionStage<ToolResult>> logic) {
		return new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{}");
			}

			public java.util.concurrent.CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return logic.apply(call, context);
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> updates(ToolContext context) {
		return (Map<String, Object>) context.attributes().get(AGENT_STATE_FOR_UPDATE_CONTEXT_KEY);
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messages(Map<String, Object> result) {
		return (List<ChatMessage>) result.get("messages");
	}

	private ToolCall createToolCall(String id, String name, String arguments) {
		return new ToolCall(id, name, arguments);
	}

	private ChatMessage createAssistantMessageWithToolCalls(ToolCall... toolCalls) {
		return ChatMessage.assistant("", List.of(toolCalls));
	}

	private OverAllState createStateWithMessages(ChatMessage... messages) {
		return new OverAllState(Map.of("messages", new ArrayList<>(List.of(messages))));
	}

}
