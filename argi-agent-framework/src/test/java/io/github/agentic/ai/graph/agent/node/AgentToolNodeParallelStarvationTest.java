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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regression tests for neutral tool dispatch, bounded concurrency, and permit timeouts.
 */
@DisplayName("AgentToolNode Parallel Starvation Tests")
class AgentToolNodeParallelStarvationTest {

	private AgentToolNode.Builder baseBuilder;

	@BeforeEach
	void setUp() {
		baseBuilder = AgentToolNode.builder()
			.agentName("test-agent")
			.toolExecutionTimeout(Duration.ofSeconds(10))
			.throwOnToolError(false);
	}

	@Nested
	@DisplayName("Thread Pool Starvation Prevention Tests")
	class ThreadPoolStarvationTests {

		@Test
		@Timeout(value = 15, unit = TimeUnit.SECONDS)
		@DisplayName("parallel execution with neutral tool stages should not deadlock with single-thread executor")
		void parallelExecutionWithNeutralStages_shouldNotDeadlock_withSingleThreadExecutor() throws Exception {
			// Use single-thread executor to expose the starvation bug
			ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

			try {
				AtomicInteger executionCount = new AtomicInteger(0);

				// Create two simple sync tools
				Tool tool1 = createSyncTool("tool1", () -> {
					executionCount.incrementAndGet();
					return "result1";
				});

				Tool tool2 = createSyncTool("tool2", () -> {
					executionCount.incrementAndGet();
					return "result2";
				});

				// Dispatch completed neutral stages on a single worker.
				AgentToolNode toolNode = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2)))
					.parallelToolExecution(true)
					.maxParallelTools(5)
					.build();

				// Create state with assistant message containing tool calls
				ChatMessage assistantMessage = createChatMessageWithToolCalls(createToolCall("call1", "tool1", "{}"),
						createToolCall("call2", "tool2", "{}"));

				OverAllState state = createStateWithMessages(assistantMessage);

				// Create config with the single-thread executor
				RunnableConfig config = RunnableConfig.builder()
					.threadId("test-thread")
					.addParallelNodeExecutor("_AGENT_TOOL_", singleThreadExecutor)
					.build();

				// Execute - before fix this would deadlock; after fix it completes
				Map<String, Object> result = toolNode.apply(state, config);

				// Verify both tools executed successfully
				assertThat(executionCount.get()).isEqualTo(2);
				assertThat(result).containsKey("messages");
				Object messages = result.get("messages");
				assertThat(messages).isInstanceOf(List.class);

				List<ChatMessage> responseMessage = (List<ChatMessage>) messages;
				assertThat(responseMessage).hasSize(2);
				assertThat(responseMessage.get(0).text()).isEqualTo("result1");
				assertThat(responseMessage.get(1).text()).isEqualTo("result2");
			}
			finally {
				singleThreadExecutor.shutdownNow();
			}
		}

		@Test
		@Timeout(value = 15, unit = TimeUnit.SECONDS)
		@DisplayName("sequential execution with neutral tool stages should still work")
		void sequentialExecutionWithNeutralStages_shouldStillWork() throws Exception {
			ExecutorService executor = Executors.newFixedThreadPool(2);

			try {
				AtomicInteger executionCount = new AtomicInteger(0);

				Tool tool1 = createSyncTool("tool1", () -> {
					executionCount.incrementAndGet();
					return "result1";
				});

				Tool tool2 = createSyncTool("tool2", () -> {
					executionCount.incrementAndGet();
					return "result2";
				});

				// Sequential mode with neutral tool stages - should still work
				AgentToolNode toolNode = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2)))
					.parallelToolExecution(false) // Sequential mode
					.build();

				ChatMessage assistantMessage = createChatMessageWithToolCalls(createToolCall("call1", "tool1", "{}"),
						createToolCall("call2", "tool2", "{}"));

				OverAllState state = createStateWithMessages(assistantMessage);

				RunnableConfig config = RunnableConfig.builder()
					.threadId("test-thread")
					.addParallelNodeExecutor("_AGENT_TOOL_", executor)
					.build();

				Map<String, Object> result = toolNode.apply(state, config);

				assertThat(executionCount.get()).isEqualTo(2);
				assertThat(result).containsKey("messages");
				List<ChatMessage> responseMessage = (List<ChatMessage>) result.get("messages");
				assertThat(responseMessage).hasSize(2);
			}
			finally {
				executor.shutdownNow();
			}
		}

	}

	@Nested
	@DisplayName("Parallel Concurrency Control Tests")
	class ParallelConcurrencyControlTests {

		@Test
		@Timeout(value = 20, unit = TimeUnit.SECONDS)
		@DisplayName("parallel execution with limited thread pool should complete successfully")
		void parallelExecution_withLimitedThreadPool_shouldCompleteSucessfully() throws Exception {
			// Use 2 threads but 4 tools to verify semaphore limits work correctly
			ExecutorService limitedExecutor = Executors.newFixedThreadPool(2);

			try {
				AtomicInteger concurrentExecutions = new AtomicInteger(0);
				AtomicInteger maxConcurrent = new AtomicInteger(0);
				AtomicInteger totalExecutions = new AtomicInteger(0);

				Supplier<String> workSimulator = () -> {
					int current = concurrentExecutions.incrementAndGet();
					maxConcurrent.updateAndGet(max -> Math.max(max, current));
					try {
						Thread.sleep(100); // Simulate work
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					finally {
						concurrentExecutions.decrementAndGet();
					}
					totalExecutions.incrementAndGet();
					return "result";
				};

				Tool tool1 = createSyncTool("tool1", () -> workSimulator.get());
				Tool tool2 = createSyncTool("tool2", () -> workSimulator.get());
				Tool tool3 = createSyncTool("tool3", () -> workSimulator.get());
				Tool tool4 = createSyncTool("tool4", () -> workSimulator.get());

				AgentToolNode toolNode = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2, tool3, tool4)))
					.parallelToolExecution(true)
					.maxParallelTools(2) // Limit to 2 concurrent
					.build();

				ChatMessage assistantMessage = createChatMessageWithToolCalls(createToolCall("call1", "tool1", "{}"),
						createToolCall("call2", "tool2", "{}"), createToolCall("call3", "tool3", "{}"),
						createToolCall("call4", "tool4", "{}"));

				OverAllState state = createStateWithMessages(assistantMessage);

				RunnableConfig config = RunnableConfig.builder()
					.threadId("test-thread")
					.addParallelNodeExecutor("_AGENT_TOOL_", limitedExecutor)
					.build();

				Map<String, Object> result = toolNode.apply(state, config);

				// All 4 tools should have executed
				assertThat(totalExecutions.get()).isEqualTo(4);

				// Max concurrent should be limited to 2 (by semaphore)
				assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2);

				List<ChatMessage> responseMessage = (List<ChatMessage>) result.get("messages");
				assertThat(responseMessage).hasSize(4);
			}
			finally {
				limitedExecutor.shutdownNow();
			}
		}

		@Test
		@Timeout(value = 15, unit = TimeUnit.SECONDS)
		@DisplayName("parallel execution without neutral tool stages should work")
		void parallelExecutionWithMultipleWorkers_shouldWork() throws Exception {
			ExecutorService executor = Executors.newFixedThreadPool(4);

			try {
				AtomicInteger executionCount = new AtomicInteger(0);

				Tool tool1 = createSyncTool("tool1", () -> {
					executionCount.incrementAndGet();
					return "result1";
				});

				Tool tool2 = createSyncTool("tool2", () -> {
					executionCount.incrementAndGet();
					return "result2";
				});

				AgentToolNode toolNode = baseBuilder.toolRegistry(new ToolRegistry(List.of(tool1, tool2)))
					.parallelToolExecution(true)
					.maxParallelTools(5)
					.build();

				ChatMessage assistantMessage = createChatMessageWithToolCalls(createToolCall("call1", "tool1", "{}"),
						createToolCall("call2", "tool2", "{}"));

				OverAllState state = createStateWithMessages(assistantMessage);

				RunnableConfig config = RunnableConfig.builder()
					.threadId("test-thread")
					.addParallelNodeExecutor("_AGENT_TOOL_", executor)
					.build();

				Map<String, Object> result = toolNode.apply(state, config);

				assertThat(executionCount.get()).isEqualTo(2);
				List<ChatMessage> responseMessage = (List<ChatMessage>) result.get("messages");
				assertThat(responseMessage).hasSize(2);
			}
			finally {
				executor.shutdownNow();
			}
		}

	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	@Timeout(15)
	void timedOutPermitWaiterShouldNotExecuteOrKeepWorkerOccupied(boolean releaseBeforeProbe) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicReference<Thread> waitingWorker = new AtomicReference<>();
		AtomicInteger submissions = new AtomicInteger();
		AtomicInteger lateCalls = new AtomicInteger();
		try {
			Tool first = createSyncTool("first", () -> {
				firstStarted.countDown();
				try {
					releaseFirst.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return "first result";
			});
			Tool second = createSyncTool("second", () -> {
				lateCalls.incrementAndGet();
				return "second result";
			});
			AgentToolNode node = baseBuilder.toolRegistry(new ToolRegistry(List.of(first, second)))
				.parallelToolExecution(true)
				.maxParallelTools(1)
				.toolExecutionTimeout(Duration.ofSeconds(1))
				.build();
			RunnableConfig config = RunnableConfig.builder().addParallelNodeExecutor("_AGENT_TOOL_", command -> {
				int index = submissions.getAndIncrement();
				if (index == 0) {
					executor.execute(command);
					await().atMost(Duration.ofSeconds(5)).until(() -> firstStarted.getCount() == 0);
				}
				else {
					executor.execute(() -> {
						waitingWorker.set(Thread.currentThread());
						command.run();
					});
					await().atMost(Duration.ofSeconds(5))
						.until(() -> waitingWorker.get() != null
								&& (waitingWorker.get().getState() == Thread.State.WAITING
										|| waitingWorker.get().getState() == Thread.State.TIMED_WAITING));
				}
			}).build();
			OverAllState state = createStateWithMessages(createChatMessageWithToolCalls(
					createToolCall("call1", "first", "{}"), createToolCall("call2", "second", "{}")));

			List<ChatMessage> response = (List<ChatMessage>) node.apply(state, config).get("messages");
			assertThat(response).extracting(ChatMessage::text)
				.containsExactly("Error: Tool execution timed out", "Error: Tool execution timed out");
			if (releaseBeforeProbe) {
				releaseFirst.countDown();
				executor.shutdown();
				assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			}
			else {
				assertThat(executor.submit(() -> "worker available").get(2, TimeUnit.SECONDS))
					.isEqualTo("worker available");
			}
			assertThat(lateCalls).hasValue(0);
		}
		finally {
			releaseFirst.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	// Helper methods

	private Tool createSyncTool(String name, Supplier<String> execution) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, "Test tool " + name, "{\"type\":\"object\",\"properties\":{}}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text(execution.get()));
			}
		};
	}

	private ToolCall createToolCall(String id, String name, String arguments) {
		return new ToolCall(id, name, arguments);
	}

	private ChatMessage createChatMessageWithToolCalls(ToolCall... toolCalls) {
		return ChatMessage.assistant("", List.of(toolCalls));
	}

	private OverAllState createStateWithMessages(ChatMessage... messages) {
		Map<String, Object> stateData = new HashMap<>();
		stateData.put("messages", new ArrayList<>(List.of(messages)));
		return new OverAllState(stateData);
	}

}
