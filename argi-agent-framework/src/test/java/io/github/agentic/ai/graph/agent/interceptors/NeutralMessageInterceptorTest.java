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
package io.github.agentic.ai.graph.agent.interceptors;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.agent.extension.interceptor.PatchToolCallsInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.contextediting.ContextEditingInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeutralMessageInterceptorTest {

	@Test
	void patchesMissingResultsAfterExistingResultsAndBeforeNextTurn() {
		ChatMessage assistant = ChatMessage.assistant(null, List.of(new ToolCall("a", "search", "{}"),
				new ToolCall("b", "fetch", "{}"), new ToolCall("c", "read", "{}")));
		ChatMessage result = ChatMessage.tool("a", "search", "found");
		ChatMessage user = ChatMessage.user("continue");
		ModelRequest original = ModelRequest.builder().messages(List.of(assistant, result, user)).build();
		ModelRequest patched = forward(PatchToolCallsInterceptor.builder().build(), original);
		assertEquals(5, patched.getMessages().size());
		assertSame(assistant, patched.getMessages().get(0));
		assertSame(result, patched.getMessages().get(1));
		assertEquals("b", patched.getMessages().get(2).toolCallId());
		assertEquals("fetch", patched.getMessages().get(2).toolName());
		assertEquals("c", patched.getMessages().get(3).toolCallId());
		assertEquals(Map.of("patched", true), patched.getMessages().get(2).metadata());
		assertSame(user, patched.getMessages().get(4));
		assertEquals(3, original.getMessages().size());
		assertSame(patched, forward(PatchToolCallsInterceptor.builder().build(), patched));
	}

	@Test
	void reusedCallIdsInEarlierTurnsDoNotHideMissingResults() {
		ChatMessage assistant = ChatMessage.assistant(null, List.of(new ToolCall("a", "search", "{}")));
		ModelRequest request = ModelRequest.builder()
			.messages(List.of(assistant, ChatMessage.tool("a", "search", "old"), ChatMessage.user("again"), assistant))
			.build();
		ModelRequest patched = forward(PatchToolCallsInterceptor.builder().build(), request);
		assertEquals(5, patched.getMessages().size());
		assertEquals(Map.of("patched", true), patched.getMessages().get(4).metadata());
	}

	@Test
	void completeAndEmptyHistoriesAreForwardedUnchanged() {
		ModelRequest complete = ModelRequest.builder()
			.messages(List.of(ChatMessage.assistant("", List.of(new ToolCall("a", "search", "{}"))),
					ChatMessage.tool("a", "search", "result")))
			.build();
		assertSame(complete, forward(PatchToolCallsInterceptor.builder().build(), complete));
		ModelRequest empty = ModelRequest.builder().build();
		assertSame(empty, forward(PatchToolCallsInterceptor.builder().build(), empty));
	}

	@Test
	void contextEditingPreservesAssistantContentAndToolResultIdentity() {
		ContentPart image = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1 });
		ChatMessage assistant = new ChatMessage(ChatRole.ASSISTANT, List.of(image, ContentPart.text("thinking")),
				List.of(new ToolCall("a", "search", "{\"query\":\"long input\"}")), null, null, Map.of("trace", "a"));
		ChatMessage result = new ChatMessage(ChatRole.TOOL, List.of(ContentPart.text("long result")), List.of(), "a",
				"search", Map.of("trace", "b"));
		ModelRequest original = ModelRequest.builder().messages(List.of(assistant, result)).build();
		ContextEditingInterceptor editor = ContextEditingInterceptor.builder()
			.trigger(1)
			.keep(0)
			.clearAtLeast(200)
			.clearToolInputs(true)
			.tokenCounter(messages -> messages.size() * 100)
			.build();
		List<ChatMessage> edited = forward(editor, original).getMessages();
		assertEquals(assistant.content(), edited.get(0).content());
		assertEquals(assistant.metadata(), edited.get(0).metadata());
		assertEquals("[cleared]", edited.get(0).toolCalls().get(0).arguments());
		assertEquals("a", edited.get(1).toolCallId());
		assertEquals("search", edited.get(1).toolName());
		assertEquals(result.metadata(), edited.get(1).metadata());
		assertEquals("[cleared]", edited.get(1).text());
		assertEquals(List.of(assistant, result), original.getMessages());
	}

	@Test
	void excludedToolResultsAndNewestResultRemainUntouched() {
		ChatMessage excluded = ChatMessage.tool("a", "private", "secret");
		ChatMessage clearable = ChatMessage.tool("b", "search", "old result");
		ChatMessage newest = ChatMessage.tool("c", "search", "new result");
		ContextEditingInterceptor editor = ContextEditingInterceptor.builder()
			.trigger(1)
			.keep(1)
			.clearAtLeast(100)
			.excludeTools("private")
			.tokenCounter(messages -> messages.size() * 100)
			.build();
		List<ChatMessage> edited = forward(editor,
				ModelRequest.builder().messages(List.of(excluded, clearable, newest)).build())
			.getMessages();
		assertSame(excluded, edited.get(0));
		assertEquals("[cleared]", edited.get(1).text());
		assertSame(newest, edited.get(2));
		assertTrue(edited.get(1).toolCalls().isEmpty());
	}

	private static ModelRequest forward(ModelInterceptor interceptor, ModelRequest request) {
		AtomicReference<ModelRequest> forwarded = new AtomicReference<>();
		interceptor.interceptModel(request, updated -> {
			forwarded.set(updated);
			return ModelResponse.of(ChatMessage.assistant("done"));
		});
		return forwarded.get();
	}

}
