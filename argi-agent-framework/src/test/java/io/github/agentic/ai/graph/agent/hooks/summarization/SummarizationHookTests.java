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
package io.github.agentic.ai.graph.agent.hooks.summarization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.agent.hook.summarization.SummarizationHook;
import io.github.agentic.ai.graph.state.ReplaceAllWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SummarizationHookTests {

	private ChatClient chatClient;

	@BeforeEach
	void setUp() {
		this.chatClient = mock(ChatClient.class);
		when(this.chatClient.call(any(ChatRequest.class)))
			.thenReturn(CompletableFuture.completedFuture(new ChatResponse(null, null,
					List.of(ChatMessage.assistant("Summary")), List.of(), FinishReason.STOP, null, Map.of())));
		when(this.chatClient.callBlocking(any(ChatRequest.class))).thenCallRealMethod();
	}

	@Test
	void includesTextMessagesWithTheirRoles() {
		String input = summarize(List.of(ChatMessage.system("System context"), ChatMessage.user("Earlier question"),
				ChatMessage.assistant("Earlier answer")));

		assertThat(input).contains("System: System context", "Human: Earlier question", "Assistant: Earlier answer");
	}

	@ParameterizedTest
	@NullAndEmptySource
	void includesToolCallsWithoutAssistantText(String text) {
		ChatMessage call = toolCall(text, "call-1", "search", "{\"query\":\"release notes\"}");
		ChatMessage response = toolResponse("call-1", "search", "Retrieved release notes");

		String input = summarize(List.of(call, response));

		assertThat(input).contains("call-1", "search", "{\"query\":\"release notes\"}");
	}

	@Test
	void includesAssistantTextAlongsideToolCalls() {
		ChatMessage call = toolCall("Checking the source", "call-2", "lookup", "{\"id\":42}");
		ChatMessage response = toolResponse("call-2", "lookup", "Record 42");

		String input = summarize(List.of(call, response));

		assertThat(input).contains("Assistant: Checking the source", "call-2", "lookup", "{\"id\":42}");
	}

	@Test
	void includesToolResponseDataAndSourceUrls() {
		String data = "{\"fact\":\"SENTINEL_EVIDENCE\",\"url\":\"https://example.org/source\"}";
		ChatMessage call = toolCall("", "call-3", "search", "{\"query\":\"source\"}");
		ChatMessage response = toolResponse("call-3", "search", data);

		String input = summarize(List.of(call, response));

		assertThat(input).contains(data);
		assertThat(response.text()).isEqualTo(data);
	}

	@Test
	void includesAllToolCallsAndResponsesWithTheirIdentifiers() {
		ChatMessage calls = ChatMessage.builder(ChatRole.ASSISTANT)
			.text("")
			.toolCalls(List.of(new ToolCall("first", "search", "{\"query\":\"alpha\"}"),
					new ToolCall("second", "search", "{\"query\":\"beta\"}")))
			.build();
		var second = ChatMessage.tool("second", "search", "Beta evidence");
		var first = ChatMessage.tool("first", "search", "Alpha evidence");

		String input = summarize(List.of(calls, second, first));

		assertThat(input).contains("Tool call: [id=first, name=search]: {\"query\":\"alpha\"}",
				"Tool call: [id=second, name=search]: {\"query\":\"beta\"}",
				"Tool result: [id=second, name=search]: Beta evidence",
				"Tool result: [id=first, name=search]: Alpha evidence");
	}

	@Test
	void excludesPreservedMessagesFromSummaryInput() {
		String input = summarize(List.of(ChatMessage.user("Older question"), ChatMessage.assistant("Older answer")));

		assertThat(input).contains("Older question", "Older answer")
			.doesNotContain("Original user request", "Recent question", "Recent answer");
	}

	@Test
	void doesNotCallSummaryModelBelowThreshold() {
		SummarizationHook hook = SummarizationHook.builder()
			.chatClient(this.chatClient)
			.tokenCounter(messages -> 0)
			.maxTokensBeforeSummary(1)
			.messagesToKeep(2)
			.build();

		hook.beforeModel(
				List.of(ChatMessage.user("Question"), ChatMessage.assistant("Answer"), ChatMessage.user("Follow-up")),
				RunnableConfig.builder().build());

		verifyNoInteractions(this.chatClient);
	}

	@Test
	void failedSummaryPreservesOriginalHistory() {
		when(chatClient.call(any()))
			.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")));
		List<ChatMessage> history = List.of(ChatMessage.user("Question"), ChatMessage.assistant("Evidence"),
				ChatMessage.user("Follow-up"));
		assertThat(apply(hook(1), history)).containsExactlyElementsOf(history);
	}

	@ParameterizedTest
	@NullAndEmptySource
	void emptySummaryPreservesOriginalHistory(String text) {
		ChatResponse empty = new ChatResponse(null, null,
				text == null ? List.of() : List.of(ChatMessage.assistant(text)), List.of(), null, null, Map.of());
		when(chatClient.call(any())).thenReturn(CompletableFuture.completedFuture(empty));
		List<ChatMessage> history = List.of(ChatMessage.user("Question"), ChatMessage.assistant("Evidence"),
				ChatMessage.user("Follow-up"));
		assertThat(apply(hook(1), history)).containsExactlyElementsOf(history);
	}

	@Test
	void preservesDelayedToolResultAndItsCallBeyondFiveMessages() {
		List<ChatMessage> history = new ArrayList<>();
		history.add(ChatMessage.user("Original request"));
		history.add(ChatMessage.assistant("Earlier evidence"));
		ChatMessage call = toolCall("", "delayed", "lookup", "{}");
		history.add(call);
		for (int i = 0; i < 10; i++) {
			history.add(ChatMessage.user("Intermediate message " + i));
		}
		history.add(toolResponse("delayed", "lookup", "Evidence"));
		history.add(ChatMessage.assistant("Recent answer"));
		List<ChatMessage> result = apply(hook(2), history);
		assertThat(result.subList(2, result.size())).containsExactlyElementsOf(history.subList(2, history.size()));
		assertThat(result.get(1).text()).contains("Summary");
		ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
		verify(chatClient).call(captured.capture());
		assertThat(captured.getValue().messages().get(0).text()).contains("Earlier evidence").doesNotContain("delayed");
	}

	@Test
	void preservesOverlappingToolPairs() {
		List<ChatMessage> history = List.of(ChatMessage.user("Original request"), ChatMessage.assistant("Old evidence"),
				toolCall("", "a", "search", "{}"), toolCall("", "b", "lookup", "{}"), toolResponse("a", "search", "A"),
				toolResponse("b", "lookup", "B"));
		List<ChatMessage> result = apply(hook(1), history);
		assertThat(result.subList(2, result.size())).containsExactlyElementsOf(history.subList(2, history.size()));
	}

	@Test
	void preservesUnresolvedToolCallsEvenWhenNoRecentMessagesAreRequested() {
		List<ChatMessage> history = List.of(ChatMessage.user("Original request"), ChatMessage.assistant("Old evidence"),
				toolCall("", "pending", "search", "{}"));
		List<ChatMessage> result = apply(hook(0), history);
		assertThat(result.get(result.size() - 1)).isSameAs(history.get(2));
	}

	@Test
	void noSafeCutoffLeavesHistoryUntouched() {
		List<ChatMessage> history = List.of(toolCall("", "pending", "search", "{}"), ChatMessage.user("Continue"));
		assertThat(apply(hook(1), history)).containsExactlyElementsOf(history);
		verifyNoInteractions(chatClient);
	}

	@Test
	void firstUserInRecentMessagesIsNotDuplicated() {
		List<ChatMessage> history = List.of(ChatMessage.system("Old context"), ChatMessage.assistant("Old answer"),
				ChatMessage.user("First user request"), ChatMessage.assistant("Recent answer"));
		List<ChatMessage> result = apply(hook(2), history);
		assertThat(result).hasSize(3);
		assertThat(result.subList(1, result.size())).containsExactlyElementsOf(history.subList(2, history.size()));
	}

	@Test
	void canSummarizeFirstUserAndUseCustomPromptAndPrefix() {
		SummarizationHook hook = SummarizationHook.builder()
			.chatClient(chatClient)
			.tokenCounter(messages -> 100)
			.maxTokensBeforeSummary(1)
			.messagesToKeep(1)
			.keepFirstUserMessage(false)
			.summaryPrompt("Extract evidence: %s")
			.summaryPrefix("Prior context")
			.build();
		List<ChatMessage> history = List.of(ChatMessage.user("Original request"), ChatMessage.assistant("Evidence"),
				ChatMessage.user("Recent request"));
		List<ChatMessage> result = apply(hook, history);
		assertThat(result).hasSize(2);
		assertThat(result.get(0).text()).isEqualTo("Prior context\nSummary");
		assertThat(result.get(1)).isSameAs(history.get(2));
		ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
		verify(chatClient).call(captured.capture());
		assertThat(captured.getValue().messages().get(0).text()).startsWith("Extract evidence: ")
			.contains("Original request", "Evidence");
		assertThat(hook.getName()).isEqualTo("Summarization");
		assertThat(hook.canJumpTo()).isEmpty();
	}

	@Test
	void disabledThresholdAndShortHistoryDoNotCallClient() {
		List<ChatMessage> history = List.of(ChatMessage.user("Question"));
		assertThat(apply(SummarizationHook.builder().chatClient(chatClient).build(), history))
			.containsExactlyElementsOf(history);
		assertThat(apply(hook(2), history)).containsExactlyElementsOf(history);
		verifyNoInteractions(chatClient);
	}

	@Test
	void validatesRequiredClientAndLimits() {
		assertThatThrownBy(() -> SummarizationHook.builder().build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SummarizationHook.builder().maxTokensBeforeSummary(0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SummarizationHook.builder().maxTokensBeforeSummary(-1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SummarizationHook.builder().messagesToKeep(-1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private SummarizationHook hook(int keep) {
		return SummarizationHook.builder()
			.chatClient(chatClient)
			.tokenCounter(messages -> 100)
			.maxTokensBeforeSummary(1)
			.messagesToKeep(keep)
			.build();
	}

	private List<ChatMessage> apply(SummarizationHook hook, List<ChatMessage> history) {
		Map<String, Object> update = MessagesModelHook.beforeModelAction(hook)
			.apply(new OverAllState(Map.of("messages", history)), RunnableConfig.builder().build())
			.join();
		ReplaceAllWith<?> replacement = (ReplaceAllWith<?>) update.get("messages");
		return replacement.newValues().stream().map(ChatMessage.class::cast).toList();
	}

	private String summarize(List<ChatMessage> olderMessages) {
		List<ChatMessage> history = new ArrayList<>();
		history.add(ChatMessage.user("Original user request"));
		history.addAll(olderMessages);
		history.add(ChatMessage.user("Recent question"));
		history.add(ChatMessage.assistant("Recent answer"));
		List<ChatMessage> originalHistory = List.copyOf(history);
		SummarizationHook hook = SummarizationHook.builder()
			.chatClient(this.chatClient)
			.tokenCounter(messages -> 100)
			.maxTokensBeforeSummary(1)
			.messagesToKeep(2)
			.build();

		hook.beforeModel(history, RunnableConfig.builder().build());

		assertThat(history).containsExactlyElementsOf(originalHistory);
		ArgumentCaptor<ChatRequest> prompt = ArgumentCaptor.forClass(ChatRequest.class);
		verify(this.chatClient).call(prompt.capture());
		assertThat(prompt.getValue().tools()).isEmpty();
		return prompt.getValue().messages().get(0).text();
	}

	private ChatMessage toolCall(String text, String id, String name, String arguments) {
		return ChatMessage.builder(ChatRole.ASSISTANT)
			.text(text)
			.toolCalls(List.of(new ToolCall(id, name, arguments)))
			.build();
	}

	private ChatMessage toolResponse(String id, String name, String data) {
		return ChatMessage.tool(id, name, data);
	}

}
