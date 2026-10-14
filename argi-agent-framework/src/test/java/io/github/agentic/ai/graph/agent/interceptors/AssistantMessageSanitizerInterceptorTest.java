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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.graph.agent.extension.interceptor.AssistantMessageSanitizerInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AssistantMessageSanitizerInterceptor}, covering issue #4561:
 * tool-calling assistant messages without a text part must be rewritten with empty
 * content so strict backends accept them.
 */
class AssistantMessageSanitizerInterceptorTest {

	private final AssistantMessageSanitizerInterceptor interceptor = AssistantMessageSanitizerInterceptor.builder()
		.build();

	private static ToolCall sampleToolCall() {
		return new ToolCall("call_1", "search", "{\"query\": \"what is sy\"}");
	}

	/**
	 * Capture the request that actually reaches the model and echo back a dummy response.
	 */
	private static ModelCallHandler capturingHandler(AtomicReference<ModelRequest> captured) {
		return request -> {
			captured.set(request);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("ok"));
		};
	}

	@Test
	void rewritesNullTextToolCallMessageToEmptyContent() {
		ChatMessage toolCallMessage = ChatMessage.assistant(null, List.of(sampleToolCall()));

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("search what is sy?"), toolCallMessage))
			.build();

		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, capturingHandler(captured));

		ModelRequest forwarded = captured.get();
		assertNotNull(forwarded);
		ChatMessage rewritten = forwarded.getMessages().get(1);
		assertEquals(ChatRole.ASSISTANT, rewritten.role());
		assertEquals(List.of(ContentPart.text("")), rewritten.content());
		assertEquals(List.of(sampleToolCall()), rewritten.toolCalls());
		assertTrue(toolCallMessage.content().isEmpty(), "Original message must remain unchanged");
	}

	@Test
	void preservesToolCallsMetadataAndMedia() {
		ContentPart media = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2, 3 });
		ChatMessage toolCallMessage = new ChatMessage(ChatRole.ASSISTANT, List.of(media), List.of(sampleToolCall()),
				null, null, Map.of("customKey", "customValue"));

		ModelRequest request = ModelRequest.builder().messages(List.of(toolCallMessage)).build();

		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, capturingHandler(captured));

		ChatMessage rewritten = captured.get().getMessages().get(0);
		assertEquals("", rewritten.text());
		assertEquals(List.of(sampleToolCall()), rewritten.toolCalls());
		assertEquals("customValue", rewritten.metadata().get("customKey"));
		assertEquals(List.of(media, ContentPart.text("")), rewritten.content());
	}

	@Test
	void leavesMessagesUntouchedWhenNoRewriteNeeded() {
		ChatMessage textWithToolCalls = ChatMessage.assistant("thinking...", List.of(sampleToolCall()));

		List<ChatMessage> original = List.of(ChatMessage.user("hi"), ChatMessage.assistant("plain answer"),
				textWithToolCalls, ChatMessage.tool("call_1", "search", "result"));

		ModelRequest request = ModelRequest.builder().messages(original).build();

		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, capturingHandler(captured));

		// No message needed fixing: the exact same request instance is forwarded.
		assertSame(request, captured.get());
		assertSame(request.getMessages(), captured.get().getMessages());
		assertEquals(original, captured.get().getMessages());
	}

	@Test
	void handlesEmptyMessageListGracefully() {
		ModelRequest request = ModelRequest.builder().messages(List.of()).build();

		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, capturingHandler(captured));

		assertSame(request, captured.get());
	}

}
