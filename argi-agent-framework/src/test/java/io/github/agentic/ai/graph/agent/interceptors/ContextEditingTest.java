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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.contextediting.ContextEditingInterceptor;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ContextEditingInterceptor. Uses pre-constructed messages with
 * AssistantMessage (ToolCall) and ToolResponseMessage to trigger context compaction
 * without relying on a real model to perform tool calls.
 */
class ContextEditingTest {

	private static final String PLACEHOLDER = "[cleared]";

	/**
	 * Mock ChatModel that returns a simple text response with no tool calls, so the agent
	 * finishes after one call when given initial messages.
	 */
	private static class MockChatModel implements ChatModel {

		private Prompt captured;

		@Override
		public ChatResponse call(Prompt prompt) {
			captured = prompt;
			return new ChatResponse(List.of(new Generation(new AssistantMessage("Task completed."))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Task completed.")))));
		}

	}

	@Test
	void testContextEditingCompaction() throws Exception {
		ContextEditingInterceptor contextEditingInterceptor = ContextEditingInterceptor.builder()
			.trigger(180)
			.keep(1)
			.clearAtLeast(100)
			.build();

		MockChatModel model = new MockChatModel();
		ReactAgent agent = ReactAgent.builder()
			.name("single_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.interceptors(contextEditingInterceptor)
			.saver(new MemorySaver())
			.build();

		List<ChatMessage> messages = buildMessagesWithToolCallsAndResponses();

		Optional<OverAllState> result = agent.invoke(messages);

		assertTrue(result.isPresent(), "Agent result should be present");
		assertTrue(model.captured.getInstructions()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.anyMatch(response -> PLACEHOLDER.equals(response.responseData())));
	}

	@Test
	void testContextEditingUsesConfiguredTokenCounterForCandidateBudget() {
		ContextEditingInterceptor contextEditingInterceptor = ContextEditingInterceptor.builder()
			.trigger(50)
			.keep(0)
			.clearAtLeast(100)
			.tokenCounter(
					messages -> messages.stream().mapToInt(message -> message.role() == ChatRole.TOOL ? 100 : 0).sum())
			.build();
		List<ChatMessage> messages = List.of(ChatMessage.user("Use the tool responses."),
				toolResponse("call-1", "search", "a"), toolResponse("call-2", "search", "b"),
				toolResponse("call-3", "search", "c"));

		AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
		contextEditingInterceptor.interceptModel(ModelRequest.builder().messages(messages).build(), request -> {
			capturedRequest.set(request);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Task completed."));
		});

		assertTrue(capturedRequest.get() != null, "Updated request should be passed to handler");
		assertEquals(1, countClearedToolResponses(capturedRequest.get().getMessages()));
	}

	@Test
	void testContextEditingUsesConfiguredTokenCounterForToolCallBudget() {
		ContextEditingInterceptor contextEditingInterceptor = ContextEditingInterceptor.builder()
			.trigger(50)
			.keep(0)
			.clearAtLeast(100)
			.clearToolInputs(true)
			.tokenCounter(messages -> messages.stream()
				.mapToInt(message -> message.role() == ChatRole.ASSISTANT ? 100 : 0)
				.sum())
			.build();
		List<ChatMessage> messages = List.of(ChatMessage.user("Use the tool calls."),
				assistantToolCall("call-1", "search", "a"), assistantToolCall("call-2", "search", "b"),
				assistantToolCall("call-3", "search", "c"));

		AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
		contextEditingInterceptor.interceptModel(ModelRequest.builder().messages(messages).build(), request -> {
			capturedRequest.set(request);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Task completed."));
		});

		assertTrue(capturedRequest.get() != null, "Updated request should be passed to handler");
		assertEquals(1, countClearedToolCalls(capturedRequest.get().getMessages()));
	}

	/**
	 * Build a message list that exceeds the token trigger (180): UserMessage plus several
	 * AssistantMessage (with ToolCall) + ToolResponseMessage pairs. TokenCounter uses ~4
	 * chars per token, so we need enough content to exceed 720 chars.
	 */
	private List<ChatMessage> buildMessagesWithToolCallsAndResponses() {
		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.user("Write and review a poem using tools."));
		String longContent = "x".repeat(260);
		for (int index = 0; index < 3; index++) {
			String id = "call-" + index;
			String name = index == 1 ? "reviewer" : "poem";
			messages.add(assistantToolCall(id, name, "{\"text\":\"" + longContent + "\"}"));
			messages.add(toolResponse(id, name, "Tool output: " + longContent));
		}
		return messages;
	}

	private ChatMessage toolResponse(String id, String name, String data) {
		return ChatMessage.tool(id, name, data);
	}

	private ChatMessage assistantToolCall(String id, String name, String arguments) {
		return ChatMessage.assistant("", List.of(new ToolCall(id, name, arguments)));
	}

	private int countClearedToolResponses(List<ChatMessage> messages) {
		return (int) messages.stream()
			.filter(message -> message.role() == ChatRole.TOOL)
			.filter(message -> PLACEHOLDER.equals(message.text()))
			.count();
	}

	private int countClearedToolCalls(List<ChatMessage> messages) {
		return (int) messages.stream()
			.flatMap(message -> message.toolCalls().stream())
			.filter(call -> PLACEHOLDER.equals(call.arguments()))
			.count();
	}

}
