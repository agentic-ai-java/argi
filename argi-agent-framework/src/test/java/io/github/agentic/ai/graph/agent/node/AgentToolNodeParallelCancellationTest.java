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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class AgentToolNodeParallelCancellationTest {

	@Test
	void outerTimeoutCancelsOnlyPendingSourceStage() throws Exception {
		var quick = CompletableFuture.completedFuture(ToolResult.text("quick-success"));
		var slow = new CompletableFuture<ToolResult>();
		CountDownLatch cancelled = new CountDownLatch(1);
		slow.whenComplete((value, error) -> cancelled.countDown());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			var output = node(call -> call.id().equals("quick") ? quick : slow, Duration.ofMillis(200)).apply(state(),
					config(executor));
			assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(slow).isCancelled();
			assertThat(quick).isNotCancelled();
			assertThat(messages(output)).extracting(ChatMessage::toolCallId).containsExactly("quick", "slow");
			assertThat(messages(output).get(0).text()).isEqualTo("quick-success");
			assertThat(messages(output).get(1).metadata()).containsEntry("failureKind", "timeout");
			assertThat(slow.complete(ToolResult.text("late result"))).isFalse();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void successfulParallelStagesAreNotCancelledAndKeepInvocationOrder() throws Exception {
		var quick = new CompletableFuture<ToolResult>();
		var slow = new CompletableFuture<ToolResult>();
		CountDownLatch started = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		ExecutorService invocation = Executors.newSingleThreadExecutor();
		try {
			var node = node(call -> {
				started.countDown();
				return call.id().equals("quick") ? quick : slow;
			}, Duration.ofSeconds(5));
			var output = invocation.submit(() -> node.apply(state(), config(executor)));
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
			slow.complete(ToolResult.text("second"));
			quick.complete(ToolResult.text("first"));
			assertThat(messages(output.get(2, TimeUnit.SECONDS))).extracting(ChatMessage::text)
				.containsExactly("first", "second");
			assertThat(quick).isNotCancelled();
			assertThat(slow).isNotCancelled();
		}
		finally {
			quick.cancel(true);
			slow.cancel(true);
			invocation.shutdownNow();
			executor.shutdownNow();
		}
	}

	@Test
	void cancellationOfOneInvocationDoesNotCancelAnother() throws Exception {
		var quick = new CompletableFuture<ToolResult>();
		quick.cancel(true);
		var slow = CompletableFuture.completedFuture(ToolResult.text("success"));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			var output = node(call -> call.id().equals("quick") ? quick : slow, Duration.ofSeconds(5)).apply(state(),
					config(executor));
			assertThat(messages(output).get(0).metadata()).containsEntry("failureKind", "cancelled");
			assertThat(messages(output).get(1).text()).isEqualTo("success");
			assertThat(slow).isNotCancelled();
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static AgentToolNode node(Function<ToolCall, CompletionStage<ToolResult>> execution, Duration timeout) {
		Tool tool = new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", "Lookup", "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return execution.apply(call);
			}
		};
		return AgentToolNode.builder()
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.parallelToolExecution(true)
			.maxParallelTools(2)
			.throwOnToolError(false)
			.toolExecutionTimeout(timeout)
			.build();
	}

	private static OverAllState state() {
		return new OverAllState(Map.of("messages", new ArrayList<>(List.of(ChatMessage.assistant("",
				List.of(new ToolCall("quick", "lookup", "{}"), new ToolCall("slow", "lookup", "{}")))))));
	}

	private static RunnableConfig config(ExecutorService executor) {
		return RunnableConfig.builder().addParallelNodeExecutor(RunnableConfig.AGENT_TOOL_NAME, executor).build();
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messages(Map<String, Object> output) {
		return (List<ChatMessage>) output.get("messages");
	}

}
