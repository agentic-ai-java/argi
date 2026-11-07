/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.agent.orchestration.hooks.messages;

import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.orchestration.hook.messages.SummarizationRequestCompactor;
import io.github.agentic.ai.agent.orchestration.hook.summarization.SummarizationHook;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the budget compactor reuses the existing summarization instead of
 * implementing a second one.
 */
@DisplayName("SummarizationRequestCompactor")
class SummarizationRequestCompactorTest {

	@Test
	@DisplayName("the conversation is summarized by the existing hook")
	void summarizesTheConversationWithTheExistingHook() {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any(ChatRequest.class)))
			.thenReturn(CompletableFuture.completedFuture(new ChatResponse(null, null,
					List.of(ChatMessage.assistant("Summary of the earlier conversation")), List.of(), FinishReason.STOP,
					null, Map.of())));
		when(client.callBlocking(any(ChatRequest.class))).thenCallRealMethod();

		SummarizationHook hook = SummarizationHook.builder()
			.chatClient(client)
			.maxTokensBeforeSummary(1)
			.messagesToKeep(1)
			.build();

		ModelRequest request = ModelRequest.builder()
			.systemMessage(ChatMessage.system("system prompt"))
			.messages(conversation())
			.build();

		ModelRequest compacted = new SummarizationRequestCompactor(hook).compact(request);

		assertThat(compacted.getMessages()).hasSizeLessThan(request.getMessages().size());
		assertThat(compacted.getMessages())
			.anySatisfy(message -> assertThat(message.text()).contains("Previous conversation summary"));
		assertThat(compacted.getSystemMessage()).isEqualTo(request.getSystemMessage());
	}

	@Test
	@DisplayName("a failed summary keeps the original request")
	void keepsTheOriginalRequestWhenTheSummaryFails() {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any(ChatRequest.class)))
			.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("the summary model is unavailable")));
		when(client.callBlocking(any(ChatRequest.class))).thenCallRealMethod();

		SummarizationHook hook = SummarizationHook.builder()
			.chatClient(client)
			.maxTokensBeforeSummary(1)
			.messagesToKeep(1)
			.build();

		ModelRequest request = ModelRequest.builder().messages(conversation()).build();

		ModelRequest compacted = new SummarizationRequestCompactor(hook).compact(request);

		assertThat(compacted).isSameAs(request);
	}

	@Test
	@DisplayName("a conversation which does not need a summary is returned unchanged")
	void keepsTheOriginalRequestWhenNoSummaryIsNeeded() {
		ChatClient client = mock(ChatClient.class);
		SummarizationHook hook = SummarizationHook.builder()
			.chatClient(client)
			.maxTokensBeforeSummary(100_000)
			.messagesToKeep(1)
			.build();

		ModelRequest request = ModelRequest.builder().messages(conversation()).build();

		ModelRequest compacted = new SummarizationRequestCompactor(hook).compact(request);

		assertThat(compacted).isSameAs(request);
	}

	private static List<ChatMessage> conversation() {
		return List.of(ChatMessage.user("first question with a fair amount of content"),
				ChatMessage.assistant("first answer with a fair amount of content"),
				ChatMessage.user("second question with a fair amount of content"),
				ChatMessage.assistant("second answer with a fair amount of content"),
				ChatMessage.user("third question with a fair amount of content"));
	}

}
