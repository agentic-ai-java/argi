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

package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.adapter.springai.SpringAiToolAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.hook.taskcompletion.TaskCompletionHook;
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionFailurePolicy;
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.Todo;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.TodoStatus;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression pins for issue #139: the task completion guard is strictly opt-in (default
 * routing is unchanged even with unfinished todos), the strict tool-selection fallback
 * still narrows tools when both components are registered, and a legal empty selection
 * still proceeds to a plain final answer.
 */
class Issue139TaskCompletionRoutingRegressionTest {

	@Test
	void defaultRoutingUnchangedWithoutHookEvenWithUnfinishedTodos() throws Exception {
		ScriptedClient client = new ScriptedClient(text("partial answer"));

		Optional<OverAllState> result = ReactAgent.builder()
			.name("hookless-agent")
			.chatClient(client)
			.build()
			.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")), "todos",
					List.of(new Todo("run tests", TodoStatus.PENDING))));

		assertTrue(result.isPresent());
		assertEquals(1, client.requests.size());
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
		List<ChatMessage> messages = messagesOf(result.get());
		assertEquals("partial answer", messages.get(messages.size() - 1).text());
	}

	@Test
	void strictAlwaysIncludeFallbackStillHonoredWithCompletionHookRegistered() throws Exception {
		ScriptedClient client = new ScriptedClient(toolCall("call-1", "lookup", "{}"), text("done"));
		DualTools tools = new DualTools();
		ToolSelectionInterceptor selection = ToolSelectionInterceptor.builder()
			.chatClient(new SelectionModel("not json"))
			.maxTools(1)
			.alwaysInclude("lookup")
			.failurePolicy(ToolSelectionFailurePolicy.FAIL_CLOSED_ALWAYS_INCLUDE)
			.build();

		Optional<OverAllState> result = agent(client, selection, tools)
			.invoke(Map.of("messages", List.of(ChatMessage.user("look up"))));

		assertTrue(result.isPresent());
		// The strict failure fallback narrowed the tool set to the mandatory tool instead
		// of widening it to every available tool.
		assertEquals(List.of("lookup"),
				client.requests.get(0).tools().stream().map(tool -> tool.name()).toList());
		assertEquals(1, tools.lookupExecutions.get());
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
	}

	@Test
	void emptyToolSelectionProceedsToFinalAnswerWithHookPresent() throws Exception {
		ScriptedClient client = new ScriptedClient(text("plain answer"));
		ToolSelectionInterceptor selection = ToolSelectionInterceptor.builder()
			.chatClient(new SelectionModel("{\"tools\":[]}"))
			.maxTools(1)
			.build();

		Optional<OverAllState> result = agent(client, selection, new DualTools())
			.invoke(Map.of("messages", List.of(ChatMessage.user("just answer"))));

		assertTrue(result.isPresent());
		assertTrue(client.requests.get(0).tools().isEmpty());
		assertEquals(1, client.requests.size());
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
	}

	// ------------------- harness -------------------

	private static ReactAgent agent(ChatClient client, ToolSelectionInterceptor selection, DualTools tools) {
		return ReactAgent.builder()
			.name("regression-agent")
			.chatClient(client)
			.toolRegistry(SpringAiToolAdapter.fromCallbacks(ToolCallbacks.from(tools)))
			.interceptors(selection)
			.hooks(TaskCompletionHook.builder().build())
			.build();
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messagesOf(OverAllState state) {
		return (List<ChatMessage>) state.value("messages").orElse(List.of());
	}

	private static ChatResponse text(String content) {
		return response(content, List.of());
	}

	private static ChatResponse toolCall(String id, String name, String arguments) {
		return response("", List.of(new ToolCall(id, name, arguments)));
	}

	private static ChatResponse response(String content, List<ToolCall> tools) {
		return new ChatResponse("response-id", "test-model", List.of(ChatMessage.assistant(content, tools)), tools,
				tools.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS, new Usage(2L, 3L, 5L), Map.of());
	}

	static final class DualTools {

		final AtomicInteger lookupExecutions = new AtomicInteger();

		@Tool(name = "lookup", description = "Looks up a value")
		public String lookup(String query) {
			lookupExecutions.incrementAndGet();
			return "result for " + query;
		}

		@Tool(name = "lookup2", description = "Looks up another value")
		public String lookup2(String query) {
			return "result2 for " + query;
		}

	}

	/** Selection model stub answering with a fixed JSON body, as in the slice-1 tests. */
	private static final class SelectionModel implements ChatClient {

		private final String json;

		SelectionModel(String json) {
			this.json = json;
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			return CompletableFuture.completedFuture(
					new ChatResponse(null, null, List.of(ChatMessage.assistant(json)), List.of(), null, null, Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new AssertionError("Tool selection must use a single non-streaming turn");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

	private static final class ScriptedClient implements ChatClient {

		private final Queue<ChatResponse> turns;

		final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

		ScriptedClient(ChatResponse... responses) {
			this.turns = new ArrayDeque<>(List.of(responses));
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			requests.add(request);
			return CompletableFuture.completedFuture(requireNextTurn());
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new AssertionError("Regression tests drive the agent through the blocking API");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(false, true, false, false, true, true);
		}

		private ChatResponse requireNextTurn() {
			ChatResponse next = turns.poll();
			if (next == null) {
				throw new AssertionError("No scripted model turn left");
			}
			return next;
		}

	}

}
