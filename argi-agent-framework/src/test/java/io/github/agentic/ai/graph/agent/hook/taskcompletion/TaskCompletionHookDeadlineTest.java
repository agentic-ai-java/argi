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

package io.github.agentic.ai.graph.agent.hook.taskcompletion;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.action.ActionDecision;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.Todo;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.TodoStatus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deadline behavior of {@link TaskCompletionHook} driven through the package-private
 * {@code nanoTime} test seam.
 */
class TaskCompletionHookDeadlineTest {

	@Test
	void expiredDeadlineStopsTheNudgeLoopWithoutFurtherModelCalls() throws Exception {
		// First read anchors the deadline at the first nudge; the second read (the next
		// nudge check) is already past the one-second budget.
		long[] clockReads = { 0L, Duration.ofSeconds(5).toNanos() };
		AtomicLong reads = new AtomicLong();
		ScriptedClient client = new ScriptedClient(response("answer one"), response("answer two"));
		TaskCompletionHook hook = TaskCompletionHook.builder()
			.maxAttempts(3)
			.deadline(Duration.ofSeconds(1))
			.nanoTime(() -> clockReads[(int) Math.min(reads.getAndIncrement(), clockReads.length - 1)])
			.build();
		ReactAgent agent = ReactAgent.builder()
			.name("deadline-agent")
			.chatClient(client)
			.hooks(hook)
			.build();

		var result = agent.invoke(Map.of("messages", List.of(ChatMessage.user("do the work")), "todos",
				List.of(new Todo("run tests", TodoStatus.PENDING))));

		assertTrue(result.isPresent());
		assertEquals(2, client.requests.size());
		Object decision = result.get().value(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY).orElse(null);
		ActionDecision typed = assertInstanceOf(ActionDecision.class, decision);
		assertEquals("NEED_INPUT", typed.type().name());
		assertEquals("UNFINISHED_REQUIRED_TASKS", typed.reasonCode());
	}

	private static ChatResponse response(String content) {
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
			ChatResponse next = turns.poll();
			if (next == null) {
				throw new AssertionError("No scripted model turn left");
			}
			return CompletableFuture.completedFuture(next);
		}

		@Override
		public java.util.concurrent.Flow.Publisher<io.github.agentic.ai.api.chat.ChatEvent> stream(
				ChatRequest request) {
			throw new UnsupportedOperationException("Stream not configured");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(false, true, false, false, true, true);
		}

	}

}
