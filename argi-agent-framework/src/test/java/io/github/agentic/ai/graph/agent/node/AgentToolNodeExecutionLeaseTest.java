/*
 * Copyright 2025-2026 the original author or authors.
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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionGuard;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolNodeExecutionLeaseTest {

	private final List<ExecutorService> executors = new ArrayList<>();

	@AfterEach
	void shutdownExecutors() {
		executors.forEach(ExecutorService::shutdownNow);
	}

	@Test
	void sequentialDispatchStopsBeforeNextToolAfterLeaseLoss() {
		ManualGuard guard = new ManualGuard();
		AtomicInteger firstToolCalls = new AtomicInteger();
		AtomicInteger secondToolCalls = new AtomicInteger();
		Tool first = tool("first", ignored -> {
			firstToolCalls.incrementAndGet();
			guard.lose("first tool lost ownership");
			return "late-first";
		});
		Tool second = tool("second", ignored -> {
			secondToolCalls.incrementAndGet();
			return "second";
		});
		AgentToolNode node = builder().toolRegistry(new ToolRegistry(List.of(first, second)))
			.parallelToolExecution(false)
			.build();

		LeaseLostException loss = assertThrows(LeaseLostException.class,
				() -> node.apply(state(toolCall("call-1", "first"), toolCall("call-2", "second")), config(guard)));

		assertEquals("first tool lost ownership", loss.getReason());
		assertEquals(1, firstToolCalls.get());
		assertEquals(0, secondToolCalls.get());
	}

	@Test
	void parallelDispatchStopsPermitWaiterAfterLeaseLoss() {
		ManualGuard guard = new ManualGuard();
		AtomicInteger firstToolCalls = new AtomicInteger();
		AtomicInteger secondToolCalls = new AtomicInteger();
		Tool first = tool("first", ignored -> {
			firstToolCalls.incrementAndGet();
			guard.lose("parallel first tool lost ownership");
			return "late-first";
		});
		Tool second = tool("second", ignored -> {
			secondToolCalls.incrementAndGet();
			return "second";
		});
		ExecutorService toolExecutor = remember(Executors.newFixedThreadPool(2));
		AgentToolNode node = builder().toolRegistry(new ToolRegistry(List.of(first, second)))
			.parallelToolExecution(true)
			.maxParallelTools(1)
			.build();

		LeaseLostException loss = assertThrows(LeaseLostException.class,
				() -> node.apply(state(toolCall("call-1", "first"), toolCall("call-2", "second")),
						RunnableConfig.builder()
							.executionGuard(guard)
							.addParallelNodeExecutor(RunnableConfig.AGENT_TOOL_NAME, toolExecutor)
							.build()));

		assertEquals("parallel first tool lost ownership", loss.getReason());
		assertEquals(1, firstToolCalls.get());
		assertEquals(0, secondToolCalls.get());
	}

	@Test
	void interceptorCannotMaskLeaseLossAfterLeafDispatch() {
		ManualGuard guard = new ManualGuard();
		Tool losingTool = tool("losing", ignored -> {
			guard.lose("interceptor mask attempt");
			return "late";
		});
		ToolInterceptor maskingInterceptor = new ToolInterceptor() {
			@Override
			public String getName() {
				return "masking";
			}

			@Override
			public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
				try {
					return handler.call(request);
				}
				catch (LeaseLostException ex) {
					return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), "masked");
				}
			}
		};
		AgentToolNode node = builder().toolRegistry(new ToolRegistry(List.of(losingTool))).build();
		node.setToolInterceptors(List.of(maskingInterceptor));

		LeaseLostException loss = assertThrows(LeaseLostException.class,
				() -> node.apply(state(toolCall("call-1", "losing")), config(guard)));

		assertEquals("interceptor mask attempt", loss.getReason());
	}

	@Test
	void queuedParallelToolDoesNotStartAfterLeaseLoss() throws Exception {
		ManualGuard guard = new ManualGuard();
		CountDownLatch blockerStarted = new CountDownLatch(1);
		CountDownLatch releaseBlocker = new CountDownLatch(1);
		ExecutorService toolExecutor = remember(Executors.newSingleThreadExecutor());
		toolExecutor.execute(() -> {
			blockerStarted.countDown();
			try {
				releaseBlocker.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(blockerStarted.await(5, TimeUnit.SECONDS), "executor should be occupied before tool queues");
		AtomicInteger delegateCalls = new AtomicInteger();
		Tool queued = tool("queued", ignored -> {
			delegateCalls.incrementAndGet();
			return "late";
		});
		AgentToolNode node = builder().toolRegistry(new ToolRegistry(List.of(queued)))
			.parallelToolExecution(true)
			.build();
		ExecutorService invocationExecutor = remember(Executors.newSingleThreadExecutor());
		CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(() -> {
			try {
				return node.apply(state(toolCall("call-1", "queued")),
						RunnableConfig.builder()
							.executionGuard(guard)
							.addParallelNodeExecutor(RunnableConfig.AGENT_TOOL_NAME, toolExecutor)
							.build());
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		}, invocationExecutor);

		guard.lose("queued parallel tool lost ownership");
		releaseBlocker.countDown();

		ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
		LeaseLostException loss = assertRootLeaseLoss(error);
		assertEquals("queued parallel tool lost ownership", loss.getReason());
		assertEquals(0, delegateCalls.get(), "queued delegate must not start after guard loss");
	}

	@Test
	void activeCancellableToolGetsCancellationOnLeaseLossAndLateOutputIsNotMerged() throws Exception {
		ManualGuard guard = new ManualGuard();
		CountDownLatch toolStarted = new CountDownLatch(1);
		CompletableFuture<ToolResult> original = new CompletableFuture<>();
		Tool tool = new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition("slow", "slow", "{}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				toolStarted.countDown();
				return original;
			}
		};
		AgentToolNode node = builder().toolRegistry(new ToolRegistry(List.of(tool))).build();
		ExecutorService invocationExecutor = remember(Executors.newSingleThreadExecutor());

		CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(() -> {
			try {
				return node.apply(state(toolCall("call-1", "slow")), config(guard));
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		}, invocationExecutor);
		assertTrue(toolStarted.await(5, TimeUnit.SECONDS), "tool must start before ownership is lost");
		guard.lose("active async tool lost ownership");

		ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
		LeaseLostException loss = assertRootLeaseLoss(error);
		assertEquals("active async tool lost ownership", loss.getReason());
		assertTrue(original.isCancelled(), "lease loss must request original stage cancellation");
		assertTrue(!original.complete(ToolResult.text("late")), "late output must not be published");
	}

	private static AgentToolNode.Builder builder() {
		return AgentToolNode.builder()
			.agentName("lease-test-agent")
			.toolExecutionTimeout(Duration.ofSeconds(5))
			.throwOnToolError(false);
	}

	private static RunnableConfig config(ExecutionGuard guard) {
		return RunnableConfig.builder().executionGuard(guard).build();
	}

	private static OverAllState state(ToolCall... toolCalls) {
		return new OverAllState(Map.of("messages", List.of(ChatMessage.assistant("", List.of(toolCalls)))));
	}

	private static ToolCall toolCall(String id, String name) {
		return new ToolCall(id, name, "{}");
	}

	private static Tool tool(String name, Function<String, String> call) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall toolCall, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text(call.apply(toolCall.arguments())));
			}
		};
	}

	private <T extends ExecutorService> T remember(T executor) {
		executors.add(executor);
		return executor;
	}

	private static LeaseLostException assertRootLeaseLoss(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return assertInstanceOf(LeaseLostException.class, current);
	}

	private static final class ManualGuard implements ExecutionGuard {

		private final List<Runnable> callbacks = new ArrayList<>();

		private LeaseLostException loss;

		@Override
		public synchronized void assertActive() {
			if (loss != null) {
				throw loss;
			}
		}

		@Override
		public synchronized AutoCloseable onLoss(Runnable cancellation) {
			if (loss != null) {
				cancellation.run();
				return () -> {
				};
			}
			callbacks.add(cancellation);
			return () -> {
				synchronized (ManualGuard.this) {
					callbacks.remove(cancellation);
				}
			};
		}

		synchronized void lose(String reason) {
			if (loss != null) {
				return;
			}
			loss = new LeaseLostException("lease-test", UUID.randomUUID(), 1, reason);
			List<Runnable> toRun = new ArrayList<>(callbacks);
			callbacks.clear();
			toRun.forEach(Runnable::run);
		}

	}

}
