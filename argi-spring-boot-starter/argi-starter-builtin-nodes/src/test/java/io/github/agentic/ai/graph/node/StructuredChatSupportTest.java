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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static io.github.agentic.ai.graph.node.QuestionClassifierNodeTest.response;
import static io.github.agentic.ai.graph.node.QuestionClassifierNodeTest.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredChatSupportTest {

	@Test
	void rendersVariablesAndRejectsBrokenTemplates() {
		assertEquals("Hi Alex Alex", StructuredChatSupport.render(state(Map.of("name", "Alex")), "Hi {name} {name}"));
		assertEquals("", StructuredChatSupport.render(state(Map.of()), "{missing}"));
		assertThrows(IllegalArgumentException.class, () -> StructuredChatSupport.render("{broken", Map.of()));
	}

	@Test
	void parsesOnlyOneJsonObjectAndPreservesEmbeddedBackticks() {
		for (String fence : List.of("```\n", "```json\n", "```\r\n", "```json\r\n")) {
			assertEquals("```json",
					StructuredChatSupport.object(fence + "{\"note\":\"```json\"}\n```").get("note").textValue());
		}
		for (String json : List.of("[]", "null", " ", "invalid", "{} {}", "```json\n{}")) {
			assertThrows(IllegalArgumentException.class, () -> StructuredChatSupport.object(json));
		}
	}

	@Test
	void rejectsMissingTextNonAssistantAndToolCallingResponses() {
		ChatClient client = mock(ChatClient.class);
		ToolCall tool = new ToolCall("call", "lookup", "{}");
		for (ChatResponse response : List.of(response(" "),
				new ChatResponse(null, null, List.of(), List.of(), null, null, null),
				new ChatResponse(null, null, List.of(ChatMessage.user("text")), List.of(), null, null, null),
				new ChatResponse(null, null, List.of(ChatMessage.assistant("text", List.of(tool))), List.of(), null,
						null, null),
				new ChatResponse(null, null, List.of(ChatMessage.assistant("text")), List.of(tool), null, null,
						null))) {
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response));
			assertThrows(IllegalStateException.class,
					() -> StructuredChatSupport.call(client, List.of(ChatMessage.user("input"))).block());
		}
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(null));
		assertThrows(IllegalStateException.class,
				() -> StructuredChatSupport.call(client, List.of(ChatMessage.user("input"))).block());
	}

}
