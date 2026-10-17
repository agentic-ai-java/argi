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
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.action.ActionDecision;
import io.github.agentic.ai.graph.agent.hook.taskcompletion.TaskCompletionHook;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.Todo;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.TodoStatus;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stream vs non-stream consistency of {@link TaskCompletionHook}: the same scripted run
 * must reach the same terminal decision and the same message sequence in both modes
 * (T36 acceptance: stream/非stream一致性).
 */
class TaskCompletionHookStreamConsistencyOfflineTest {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void exhaustedNudgeLoopProducesIdenticalOutcomeAcrossModes(boolean streaming) throws Exception {
		ScriptedClient client = new ScriptedClient(text("answer one"), text("answer two"), text("answer three"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build());

		OverAllState finalState = run(agent, unfinishedInputs(), streaming);

		assertEquals(3, client.requests.size());
		ActionDecision decision = assertInstanceOf(ActionDecision.class,
				finalState.value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).orElse(null));
		assertEquals("NEED_INPUT", decision.type().name());
		assertEquals("UNFINISHED_REQUIRED_TASKS", decision.reasonCode());
		assertEquals(
				List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER,
						ChatRole.ASSISTANT, ChatRole.ASSISTANT),
				messagesOf(finalState).stream().map(ChatMessage::role).collect(Collectors.toList()));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void completedTodosEndAfterOneTurnInBothModes(boolean streaming) throws Exception {
		ScriptedClient client = new ScriptedClient(text("all done"));
		ReactAgent agent = agent(client, TaskCompletionHook.builder().build());

		Map<String, Object> inputs = Map.of("messages", List.of(ChatMessage.user("do the work")), "todos",
				List.of(new Todo("run tests", TodoStatus.COMPLETED)));
		OverAllState finalState = run(agent, inputs, streaming);

		assertEquals(1, client.requests.size());
		assertTrue(finalState.value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).isEmpty());
		List<ChatMessage> messages = messagesOf(finalState);
		assertEquals("all done", messages.get(messages.size() - 1).text());
	}

	private static OverAllState run(ReactAgent agent, Map<String, Object> inputs, boolean streaming)
			throws Exception {
		if (!streaming) {
			return agent.invoke(inputs).orElseThrow();
		}
		List<NodeOutput> outputs = agent.stream(inputs).collectList().block(Duration.ofSeconds(10));
		return outputs.get(outputs.size() - 1).state();
	}

	private static Map<String, Object> unfinishedInputs() {
		return Map.of("messages", List.of(ChatMessage.user("do the work")), "todos",
				List.of(new Todo("run tests", TodoStatus.PENDING)));
	}

	private static ReactAgent agent(ChatClient client, TaskCompletionHook hook) {
		return ReactAgent.builder().name("stream-consistency-agent").chatClient(client).hooks(hook).build();
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messagesOf(OverAllState state) {
		return (List<ChatMessage>) state.value("messages").orElse(List.of());
	}

	private static ChatResponse text(String content) {
		return new ChatResponse("response-id", "test-model", List.of(ChatMessage.assistant(content)), List.of(),
				FinishReason.STOP, new Usage(2L, 3L, 5L), Map.of());
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
