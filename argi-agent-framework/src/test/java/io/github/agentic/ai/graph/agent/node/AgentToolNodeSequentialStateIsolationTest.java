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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failed invocation must discard only its own updates, never earlier successful
 * updates.
 */
@Timeout(10)
class AgentToolNodeSequentialStateIsolationTest {

	@Test
	void preservesSuccessfulUpdatesWhenNextToolTimesOut() throws Exception {
		var stalled = new CompletableFuture<ToolResult>();
		Tool first = writing("first", "one", CompletableFuture.completedFuture(ToolResult.text("Success")));
		Tool second = writing("second", "failed", stalled);
		var output = execute(first, second);
		assertThat(output).containsEntry("one", "saved").doesNotContainKey("failed");
		assertThat(messages(output)).hasSize(2);
		assertThat(messages(output).get(0).text()).isEqualTo("Success");
		assertThat(messages(output).get(1).metadata()).containsEntry("failureKind", "timeout");
		assertThat(stalled).isCancelled();
	}

	@Test
	void preservesUpdatesFromAllSuccessfulTools() throws Exception {
		var output = execute(writing("first", "one", CompletableFuture.completedFuture(ToolResult.text("first"))),
				writing("second", "two", CompletableFuture.completedFuture(ToolResult.text("second"))));
		assertThat(output).containsEntry("one", "saved").containsEntry("two", "saved");
		assertThat(messages(output)).extracting(ChatMessage::text).containsExactly("first", "second");
	}

	@Test
	void mixedBlockingAndAsyncToolsPreserveOnlySuccessfulUpdates() throws Exception {
		var executor = Executors.newSingleThreadExecutor();
		var stalled = new CompletableFuture<ToolResult>();
		try {
			Tool blocking = tool("first", (call, context) -> {
				updates(context).put("one", "saved");
				return CompletableFuture.completedFuture(ToolResult.text("blocking"));
			});
			Tool asynchronous = tool("second", (call, context) -> CompletableFuture.supplyAsync(() -> {
				updates(context).put("two", "saved");
				return ToolResult.text("async");
			}, executor));
			var output = execute(blocking, asynchronous, writing("third", "failed", stalled));
			assertThat(output).containsEntry("one", "saved").containsEntry("two", "saved").doesNotContainKey("failed");
			assertThat(messages(output)).hasSize(3);
			assertThat(stalled).isCancelled();
		}
		finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
		}
	}

	private static Map<String, Object> execute(Tool... tools) throws Exception {
		var calls = new ArrayList<ToolCall>();
		for (int index = 0; index < tools.length; index++) {
			calls.add(new ToolCall("call-" + index, tools[index].definition().name(), "{}"));
		}
		var state = new OverAllState(Map.of("messages", new ArrayList<>(List.of(ChatMessage.assistant("", calls)))));
		return AgentToolNode.builder()
			.toolRegistry(new ToolRegistry(List.of(tools)))
			.parallelToolExecution(false)
			.throwOnToolError(false)
			.toolExecutionTimeout(Duration.ofMillis(300))
			.build()
			.apply(state, RunnableConfig.builder().build());
	}

	private static Tool writing(String name, String key, CompletionStage<ToolResult> stage) {
		return tool(name, (call, context) -> {
			updates(context).put(key, "saved");
			return stage;
		});
	}

	private static Tool tool(String name, BiFunction<ToolCall, ToolContext, CompletionStage<ToolResult>> execution) {
		return new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return execution.apply(call, context);
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> updates(ToolContext context) {
		return (Map<String, Object>) context.attributes().get(AGENT_STATE_FOR_UPDATE_CONTEXT_KEY);
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messages(Map<String, Object> output) {
		return (List<ChatMessage>) output.get("messages");
	}

}
