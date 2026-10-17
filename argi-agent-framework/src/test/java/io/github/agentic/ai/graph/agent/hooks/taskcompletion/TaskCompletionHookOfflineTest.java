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

package io.github.agentic.ai.graph.agent.hooks.taskcompletion;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.adapter.springai.SpringAiToolAdapter;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.agent.action.ActionDecision;
import io.github.agentic.ai.graph.agent.action.ActionType;
import io.github.agentic.ai.graph.agent.hook.taskcompletion.TaskCompletionExceededException;
import io.github.agentic.ai.graph.agent.hook.taskcompletion.TaskCompletionHook;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.Todo;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.TodoStatus;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.agentic.ai.graph.agent.hook.taskcompletion.TaskCompletionHook.TerminalAction.NEED_INPUT;
import static io.github.agentic.ai.graph.agent.hook.taskcompletion.TaskCompletionHook.TerminalAction.STOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-independent tests for {@link TaskCompletionHook} behavior: the nudge re-ask loop
 * for unfinished required tasks and the explicit terminal states when the budget is
 * exhausted (T36).
 */
class TaskCompletionHookOfflineTest {

	@Test
	void noTodosHookStaysSilentAndEndsAfterFirstAnswer() throws Exception {
		ScriptedClient client = new ScriptedClient(text("done"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build());

		Optional<OverAllState> result = agent
			.invoke(Map.of("messages", List.of(ChatMessage.user("do the work"))));

		assertTrue(result.isPresent());
		assertEquals(1, client.requests.size());
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
		List<ChatMessage> messages = messagesOf(result.get());
		assertEquals(2, messages.size());
		assertEquals(ChatRole.ASSISTANT, messages.get(messages.size() - 1).role());
	}

	@Test
	void completedTodosHookStaysSilentAndEndsAfterFirstAnswer() throws Exception {
		ScriptedClient client = new ScriptedClient(text("done"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build());

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")),
				"todos", todos("completed:run tests")));

		assertTrue(result.isPresent());
		assertEquals(1, client.requests.size());
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
	}

	@Test
	void unfinishedTodosNudgeRedirectsBackToTheModel() throws Exception {
		// Completes as soon as the second model turn has run, so the run ends normally
		// after exactly one nudge.
		ScriptedClient client = new ScriptedClient(text("not done yet"), text("all done"));
		TaskCompletionHook hook = TaskCompletionHook.builder()
			.completionPredicate(state -> client.requests.size() >= 2)
			.build();
		ReactAgent agent = agent(client, hook);

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")),
				"todos", todos("pending:run tests", "in_progress:write docs")));

		assertTrue(result.isPresent());
		assertEquals(2, client.requests.size());
		List<ChatMessage> secondRequest = client.requests.get(1).messages();
		ChatMessage nudge = secondRequest.get(secondRequest.size() - 1);
		assertEquals(ChatRole.USER, nudge.role());
		assertTrue(nudge.text().contains("Required tasks are not complete"));
		assertTrue(nudge.text().contains("pending: run tests"));
		assertTrue(nudge.text().contains("in_progress: write docs"));
		// The run ends with the model's own answer, not a terminal notice.
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
		List<ChatMessage> messages = messagesOf(result.get());
		assertEquals("all done", messages.get(messages.size() - 1).text());
	}

	@Test
	void exhaustedNudgesTerminateWithNeedInputAndUnfinishedRequiredTasks() throws Exception {
		ScriptedClient client = new ScriptedClient(text("answer one"), text("answer two"), text("answer three"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build());

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")),
				"todos", todos("pending:run tests")));

		assertTrue(result.isPresent());
		assertEquals(3, client.requests.size());
		terminal(result.get(), NEED_INPUT, "UNFINISHED_REQUIRED_TASKS");
		List<ChatMessage> messages = messagesOf(result.get());
		assertEquals(ChatRole.ASSISTANT, messages.get(messages.size() - 1).role());
		assertTrue(messages.get(messages.size() - 1).text().contains("run tests"));
	}

	@Test
	void exhaustionCanBeConfiguredToRecordStopInstead() throws Exception {
		ScriptedClient client = new ScriptedClient(text("answer one"), text("answer two"), text("answer three"));
		ReactAgent agent = agent(client,
				TaskCompletionHook.builder().onUnfinishedExhaustion(STOP).build());

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")),
				"todos", todos("pending:run tests")));

		assertTrue(result.isPresent());
		terminal(result.get(), STOP, "UNFINISHED_REQUIRED_TASKS");
	}

	@Test
	void staleTerminalDecisionIsClearedOnAFreshRunWithTheSameThread() throws Exception {
		MemorySaver saver = new MemorySaver();
		ScriptedClient exhausted = new ScriptedClient(text("answer one"), text("answer two"), text("answer three"));
		ReactAgent agent = agent(exhausted, TaskCompletionHook.builder().build(), null, saver);

		Optional<OverAllState> first = agent.invoke(
				Map.of("messages", List.of(ChatMessage.user("do the work")), "todos", todos("pending:run tests")),
				RunnableConfig.builder().threadId("task-completion-thread").build());
		assertTrue(first.isPresent());
		terminal(first.get(), NEED_INPUT, "UNFINISHED_REQUIRED_TASKS");

		ScriptedClient recovered = new ScriptedClient(text("done"));
		ReactAgent secondAgent = agent(recovered, TaskCompletionHook.builder().build(), null, saver);

		Optional<OverAllState> second = secondAgent.invoke(
				Map.of("messages", List.of(ChatMessage.user("finished now")), "todos", todos("completed:run tests")),
				RunnableConfig.builder().threadId("task-completion-thread").build());

		assertTrue(second.isPresent());
		assertFalse(second.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
	}

	@Test
	void runScopedCountersNeverEnterTheCheckpointedState() throws Exception {
		ScriptedClient client = new ScriptedClient(text("answer one"), text("answer two"), text("answer three"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build());

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")),
				"todos", todos("pending:run tests")));

		assertTrue(result.isPresent());
		assertTrue(result.get().data().keySet().stream().noneMatch(key -> key.contains("task_completion")));
	}

	@Test
	void identicalRepeatedFinalAnswersTerminateAsNoProgress() throws Exception {
		ScriptedClient client = new ScriptedClient(text("same answer"), text("same answer"), text("same answer"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build());

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")),
				"todos", todos("pending:run tests")));

		assertTrue(result.isPresent());
		assertEquals(3, client.requests.size());
		terminal(result.get(), NEED_INPUT, "NO_PROGRESS");
	}

	@Test
	void errorExitBehaviorThrowsTaskCompletionExceededExceptionAfterTerminalState() throws Exception {
		ScriptedClient client = new ScriptedClient(text("answer one"), text("answer two"), text("answer three"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder()
			.exitBehavior(TaskCompletionHook.ExitBehavior.ERROR)
			.build());

		TaskCompletionExceededException error = assertThrows(TaskCompletionExceededException.class,
				() -> agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")), "todos",
						todos("pending:run tests"))));

		assertEquals("UNFINISHED_REQUIRED_TASKS", error.getDecision().reasonCode());
		assertEquals(ActionType.NEED_INPUT, error.getDecision().type());
		assertEquals(3, client.requests.size());
	}

	@Test
	void repeatedIdenticalToolDecisionsStopWithRepeatedDecision() throws Exception {
		ScriptedClient client = new ScriptedClient(toolCall("call-1", "lookup", "{}"),
				toolCall("call-2", "lookup", "{}"), toolCall("call-3", "lookup", "{}"));
		LookupTools tools = new LookupTools();
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build(), tools);

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("look up"))));

		assertTrue(result.isPresent());
		assertEquals(3, client.requests.size());
		// The third identical decision is stopped before it executes.
		assertEquals(2, tools.executions.get());
		terminal(result.get(), STOP, "REPEATED_DECISION");
	}

	@Test
	void sameToolWithDifferentArgumentsDoesNotTriggerTheRepeatBound() throws Exception {
		ScriptedClient client = new ScriptedClient(toolCall("call-1", "lookup", "{\"query\":\"a\"}"),
				toolCall("call-2", "lookup", "{\"query\":\"b\"}"), text("done"));
		LookupTools tools = new LookupTools();
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build(), tools);

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("look up"))));

		assertTrue(result.isPresent());
		assertEquals(2, tools.executions.get());
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
		assertEquals("done", messagesOf(result.get()).get(messagesOf(result.get()).size() - 1).text());
	}

	@Test
	void unknownToolCallsBeyondTheBoundStopBeforeTheNextModelCall() throws Exception {
		ScriptedClient client = new ScriptedClient(toolCall("call-1", "nonexistent", "{}"),
				toolCall("call-2", "nonexistent", "{}"));
		LookupTools tools = new LookupTools();
		ReactAgent agent = agent(client, TaskCompletionHook.builder().maxUnknownToolCalls(1).build(), tools);

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("look up"))));

		assertTrue(result.isPresent());
		assertEquals(2, client.requests.size());
		assertEquals(0, tools.executions.get());
		terminal(result.get(), STOP, "UNKNOWN_TOOL");
	}

	@Test
	void unknownToolBoundIsDisabledByDefault() throws Exception {
		ScriptedClient client = new ScriptedClient(toolCall("call-1", "nonexistent", "{}"),
				toolCall("call-2", "nonexistent", "{}"), text("done anyway"));
		LookupTools tools = new LookupTools();
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build(), tools);

		Optional<OverAllState> result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("look up"))));

		assertTrue(result.isPresent());
		assertEquals(3, client.requests.size());
		assertFalse(result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isPresent());
	}

	// ------------------- harness -------------------

	private static ReactAgent agent(ChatClient client, TaskCompletionHook hook) {
		return agent(client, hook, null, null);
	}

	private static ReactAgent agent(ChatClient client, TaskCompletionHook hook, LookupTools tools) {
		return agent(client, hook, tools, null);
	}

	private static ReactAgent agent(ChatClient client, TaskCompletionHook hook, LookupTools tools,
			MemorySaver saver) {
		var builder = ReactAgent.builder().name("completion-agent").chatClient(client);
		if (hook != null) {
			builder.hooks(hook);
		}
		if (tools != null) {
			builder.toolRegistry(SpringAiToolAdapter.fromCallbacks(ToolCallbacks.from(tools)));
		}
		if (saver != null) {
			builder.saver(saver);
		}
		return builder.build();
	}

	static final class LookupTools {

		final AtomicInteger executions = new AtomicInteger();

		@Tool(name = "lookup", description = "Looks up a value")
		public String lookup(String query) {
			executions.incrementAndGet();
			return "result for " + query;
		}

	}

	private static void terminal(OverAllState state, TaskCompletionHook.TerminalAction expectedType,
			String expectedReason) {
		Object decision = state.value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).orElse(null);
		ActionDecision typed = assertInstanceOf(ActionDecision.class, decision);
		assertEquals(expectedType.name(), typed.type().name());
		assertEquals(expectedReason, typed.reasonCode());
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messagesOf(OverAllState state) {
		return (List<ChatMessage>) state.value("messages").orElse(List.of());
	}

	private static List<Todo> todos(String... specs) {
		List<Todo> result = new ArrayList<>();
		for (String spec : specs) {
			int separator = spec.indexOf(':');
			String status = spec.substring(0, separator).toUpperCase(Locale.ROOT);
			result.add(new Todo(spec.substring(separator + 1), TodoStatus.valueOf(status)));
		}
		return result;
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

	/**
	 * Neutral scripted client: each model call consumes the next scripted turn. Records
	 * every request so tests can assert on the conversation the model actually saw.
	 */
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
			requests.add(request);
			return JdkFlowAdapter.publisherToFlowPublisher(Flux.just(ChatEvent.response(requireNextTurn())));
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(true, true, false, false, true, true);
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
