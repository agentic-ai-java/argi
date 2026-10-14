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
package io.github.agentic.ai.graph.agent.flow.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.OverAllState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingMessagesTests {

	@Test
	void retainsRolesMetadataAndMultipleToolCorrelations() {
		var first = new ToolCall("call-1", "lookup", "{}");
		var second = new ToolCall("call-2", "search", "{\"query\":\"q\"}");
		var messages = List.of(ChatMessage.system("system").withMetadata(Map.of("source", "policy")),
				ChatMessage.user("question"), ChatMessage.assistant("", List.of(first, second)),
				ChatMessage.tool("call-1", "lookup", "one").withMetadata(Map.of("source", "tools")),
				ChatMessage.tool("call-2", "search", "two").withMetadata(Map.of("source", "tools")));
		var result = RoutingMessages.read(new OverAllState(Map.of("messages", messages)));
		assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.TOOL),
				result.stream().map(ChatMessage::role).toList());
		assertEquals("policy", result.get(0).metadata().get("source"));
		assertEquals(
				List.of(new ToolCall("call-1", "lookup", "{}"), new ToolCall("call-2", "search", "{\"query\":\"q\"}")),
				result.get(2).toolCalls());
		assertEquals("call-2", result.get(4).toolCallId());
		assertEquals("search", result.get(4).toolName());
		assertEquals("two", result.get(4).text());
		assertEquals("tools", result.get(4).metadata().get("source"));
	}

	@Test
	void retainsNeutralMessagesWithoutRebuilding() {
		ChatMessage message = ChatMessage.user("question");
		assertSame(message, RoutingMessages.read(new OverAllState(Map.of("messages", List.of(message)))).get(0));
	}

	@Test
	void rejectsInvalidHistoryAndUnknownEntries() {
		assertThrows(IllegalArgumentException.class,
				() -> RoutingMessages.read(new OverAllState(Map.of("messages", "not a list"))));
		assertThrows(IllegalArgumentException.class,
				() -> RoutingMessages.read(new OverAllState(Map.of("messages", List.of(42)))));
	}

	@Test
	void doesNotSilentlyDiscardNativeMedia() {
		Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2 }));
		var user = UserMessage.builder().text("inspect").media(media).build();
		var assistant = AssistantMessage.builder().content("image").media(List.of(media)).build();
		assertThrows(IllegalArgumentException.class,
				() -> RoutingMessages.read(new OverAllState(Map.of("messages", List.of(user)))));
		assertThrows(IllegalArgumentException.class,
				() -> RoutingMessages.read(new OverAllState(Map.of("messages", List.of(assistant)))));
	}

	@Test
	void findsLastUserQuestionInsteadOfAssistantProgress() {
		assertEquals("second", RoutingMessages.originalQuery(List.of(ChatMessage.user("first"),
				ChatMessage.assistant("answer"), ChatMessage.user("second"), ChatMessage.assistant("progress"))));
		assertEquals("", RoutingMessages.originalQuery(List.of(ChatMessage.system("instructions"))));
		assertEquals(List.of(), RoutingMessages.read(new OverAllState()));
	}

}
