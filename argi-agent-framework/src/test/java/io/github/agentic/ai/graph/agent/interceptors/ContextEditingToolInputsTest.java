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

import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.contextediting.ContextEditingInterceptor;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextEditingToolInputsTest {

	@Test
	void defaultKeepsToolArgumentsWhileClearingResults() {
		assertInputsPreserved(edit(builder().build()));
	}

	@Test
	void explicitFalseKeepsToolArgumentsWhileClearingResults() {
		assertInputsPreserved(edit(builder().clearToolInputs(false).build()));
	}

	@Test
	void explicitTrueClearsToolArgumentsAndResults() {
		List<ChatMessage> edited = edit(builder().clearToolInputs(true).build());
		assertEquals("[cleared]", toolArguments(edited.get(1)));
		assertEquals("[cleared]", toolArguments(edited.get(3)));
		assertResultsCleared(edited);
	}

	@Test
	void excludedToolKeepsItsArgumentsAndResult() {
		List<ChatMessage> edited = edit(builder().clearToolInputs(true).excludeTools("search").build());
		assertEquals("{\"q\":\"first\"}", toolArguments(edited.get(1)));
		assertEquals("{\"q\":\"second\"}", toolArguments(edited.get(3)));
		assertEquals("first result", toolResult(edited.get(2)));
		assertEquals("second result", toolResult(edited.get(4)));
	}

	private static ContextEditingInterceptor.Builder builder() {
		return ContextEditingInterceptor.builder()
			.trigger(1)
			.keep(0)
			.clearAtLeast(400)
			.tokenCounter(
					messages -> messages.stream().mapToInt(message -> message.role() == ChatRole.USER ? 0 : 100).sum());
	}

	private static List<ChatMessage> edit(ContextEditingInterceptor interceptor) {
		List<ChatMessage> messages = List.of(ChatMessage.user("Use search results"),
				assistant("call-1", "{\"q\":\"first\"}"), result("call-1", "first result"),
				assistant("call-2", "{\"q\":\"second\"}"), result("call-2", "second result"));
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(ModelRequest.builder().messages(messages).build(), request -> {
			captured.set(request);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("done"));
		});
		return captured.get().getMessages();
	}

	private static ChatMessage assistant(String id, String arguments) {
		return ChatMessage.assistant("", List.of(new ToolCall(id, "search", arguments)));
	}

	private static ChatMessage result(String id, String text) {
		return ChatMessage.tool(id, "search", text);
	}

	private static String toolArguments(ChatMessage message) {
		assertEquals(ChatRole.ASSISTANT, message.role());
		return message.toolCalls().get(0).arguments();
	}

	private static String toolResult(ChatMessage message) {
		assertEquals(ChatRole.TOOL, message.role());
		return message.text();
	}

	private static void assertInputsPreserved(List<ChatMessage> edited) {
		assertEquals("{\"q\":\"first\"}", toolArguments(edited.get(1)));
		assertEquals("{\"q\":\"second\"}", toolArguments(edited.get(3)));
		assertResultsCleared(edited);
	}

	private static void assertResultsCleared(List<ChatMessage> edited) {
		assertEquals("[cleared]", toolResult(edited.get(2)));
		assertEquals("[cleared]", toolResult(edited.get(4)));
	}

}
