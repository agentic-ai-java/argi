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
package io.github.agentic.ai.graph.agent.hooks.toolcalllimit;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.toolcalllimit.ToolCallLimitExceededException;
import io.github.agentic.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallLimitHookOfflineTest {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void oversizedBatchEndsWithoutExecutingAnyTool(boolean parallel) throws Exception {
		Harness harness = harness(runLimit(1, ToolCallLimitHook.ExitBehavior.END), List.of("alpha", "beta"), parallel);
		OverAllState result = harness.agent.invoke("first", config("end-batch")).orElseThrow();
		assertEquals(0, harness.executions.get());
		assertEquals(1, harness.model.calls.get());
		assertTrue(lastMessage(result).text().contains("run limit (2/1)"));
		assertRejectedCallsPaired(result);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void oversizedBatchErrorsWithoutExecutingToolsOrLeavingUnpairedCalls(boolean parallel) throws Exception {
		Harness harness = harness(runLimit(1, ToolCallLimitHook.ExitBehavior.ERROR), List.of("alpha", "beta"),
				parallel);
		RunnableConfig config = config("error-batch");
		assertThrows(ToolCallLimitExceededException.class, () -> harness.agent.invoke("first", config));
		assertEquals(0, harness.executions.get());
		assertEquals(1, harness.model.calls.get());
		assertRejectedCallsPaired(harness.agent.getAndCompileGraph().getState(config).state());
	}

	@Test
	void batchExactlyAtLimitStillExecutes() throws Exception {
		Harness harness = harness(runLimit(2, ToolCallLimitHook.ExitBehavior.END), List.of("alpha", "beta"), true);
		harness.agent.invoke("first", config("exact-limit"));
		assertEquals(2, harness.executions.get());
	}

	@Test
	void namedLimitCountsOnlyTheSelectedTool() throws Exception {
		ToolCallLimitHook hook = ToolCallLimitHook.builder().toolName("alpha").runLimit(1).build();
		Harness harness = harness(hook, List.of("alpha", "beta"), false);
		harness.agent.invoke("first", config("named-limit"));
		assertEquals(2, harness.executions.get());
	}

	@Test
	void explicitThreadLimitIsCumulativeAcrossFreshConfigs() throws Exception {
		ToolCallLimitHook hook = ToolCallLimitHook.builder().threadLimit(1).build();
		Harness harness = harness(hook, List.of("alpha"), false);
		harness.agent.invoke("first", config("thread-limit"));
		harness.agent.invoke("second", config("thread-limit"));
		assertEquals(1, harness.executions.get());
		assertEquals(1, harness.model.calls.get());
		harness.agent.invoke("other", config("another-thread"));
		assertEquals(2, harness.executions.get());
	}

	@Test
	void runLimitResetsWithoutPersistingAThreadCounter() throws Exception {
		Harness harness = harness(runLimit(1, ToolCallLimitHook.ExitBehavior.END), List.of("alpha"), false);
		harness.agent.invoke("first", config("run-limit"));
		OverAllState result = harness.agent.invoke("second", config("run-limit")).orElseThrow();
		assertEquals(2, harness.executions.get());
		assertFalse(result.data().keySet().stream().anyMatch(key -> key.contains("tool_call_limit_thread_count")));
	}

	@Test
	void omittedThreadIdKeepsPerInvocationBehavior() throws Exception {
		Harness harness = harness(ToolCallLimitHook.builder().threadLimit(1).build(), List.of("alpha"), false);
		harness.agent.invoke("first");
		harness.agent.invoke("second");
		assertEquals(2, harness.executions.get());
	}

	@Test
	void rejectedBatchDoesNotSpendThePersistedThreadBudget() throws Exception {
		Harness harness = harness(ToolCallLimitHook.builder().threadLimit(1).build(), List.of("alpha", "beta"), false);
		harness.agent.invoke("first", config("rejected-budget"));
		harness.model.names = List.of("alpha");
		harness.agent.invoke("second", config("rejected-budget"));
		assertEquals(1, harness.executions.get());
	}

	@Test
	void streamingBatchIsRejectedBeforeTools() throws Exception {
		Harness harness = harness(runLimit(1, ToolCallLimitHook.ExitBehavior.END), List.of("alpha", "beta"), true);
		harness.agent.stream("first", config("stream-limit")).collectList().block(Duration.ofSeconds(2));
		assertEquals(0, harness.executions.get());
		assertEquals(1, harness.model.calls.get());
	}

	private static ToolCallLimitHook runLimit(int limit, ToolCallLimitHook.ExitBehavior behavior) {
		return ToolCallLimitHook.builder().runLimit(limit).exitBehavior(behavior).build();
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	private static Harness harness(ToolCallLimitHook hook, List<String> names, boolean parallel) throws Exception {
		AtomicInteger executions = new AtomicInteger();
		BatchModel model = new BatchModel(names);
		ToolCallback alpha = tool("alpha", executions);
		ToolCallback beta = tool("beta", executions);
		ReactAgent agent = ReactAgent.builder()
			.name("tool-limit-test")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(alpha, beta))
			.hooks(hook)
			.saver(new MemorySaver())
			.parallelToolExecution(parallel)
			.build();
		return new Harness(agent, model, executions);
	}

	private static ToolCallback tool(String name, AtomicInteger executions) {
		return FunctionToolCallback.builder(name, () -> {
			executions.incrementAndGet();
			return "done";
		}).description("Count tool executions").build();
	}

	private static ChatMessage lastMessage(OverAllState state) {
		List<?> messages = assertInstanceOf(List.class, state.value("messages").orElseThrow());
		return assertInstanceOf(ChatMessage.class, messages.get(messages.size() - 1));
	}

	private static void assertRejectedCallsPaired(OverAllState state) {
		List<?> messages = assertInstanceOf(List.class, state.value("messages").orElseThrow());
		List<ChatMessage> responses = messages.stream()
			.map(ChatMessage.class::cast)
			.filter(message -> message.role() == ChatRole.TOOL)
			.toList();
		assertEquals(List.of("call-alpha", "call-beta"), responses.stream().map(ChatMessage::toolCallId).toList());
		assertTrue(responses.stream().allMatch(response -> response.text().contains("limit")));
	}

	private record Harness(ReactAgent agent, BatchModel model, AtomicInteger executions) {
	}

	private static final class BatchModel implements ChatModel {

		private List<String> names;

		private final AtomicInteger calls = new AtomicInteger();

		private BatchModel(List<String> names) {
			this.names = names;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			calls.incrementAndGet();
			List<AssistantMessage.ToolCall> toolCalls = names.stream()
				.map(name -> new AssistantMessage.ToolCall("call-" + name, "function", name, "{}"))
				.toList();
			return new ChatResponse(
					List.of(new Generation(AssistantMessage.builder().content("").toolCalls(toolCalls).build())));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

}
