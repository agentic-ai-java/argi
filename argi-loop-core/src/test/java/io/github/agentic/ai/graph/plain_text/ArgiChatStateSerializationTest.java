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
package io.github.agentic.ai.graph.plain_text;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.JacksonStateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArgiChatStateSerializationTest {

	private static List<JacksonStateSerializer> serializers() {
		return List.of(new JacksonStateSerializer(OverAllState::new) {
		}, new ArgiJacksonStateSerializer(OverAllState::new));
	}

	@Test
	void restoresNeutralMessagesResponseAndUsageThroughPersistenceAndClone() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{\"query\":\"ARGI\"}");
		List<ChatMessage> messages = List.of(ChatMessage.system("system"), ChatMessage.user("question"),
				ChatMessage.assistant("", List.of(call)), ChatMessage.tool(call.id(), call.name(), "result"));
		Usage usage = new Usage(3_000_000_000L, null, null);
		ChatResponse response = new ChatResponse("response-1", "model", List.of(messages.get(2)), List.of(call),
				FinishReason.TOOL_CALLS, usage, Map.of("request", "request-1"));
		Map<String, Object> state = Map.of("messages", messages, "llm_response", response, "usage", usage, "nested",
				Map.of("response", response));
		for (JacksonStateSerializer serializer : serializers()) {
			for (Map<String, Object> restored : List.of(serializer.dataFromBytes(serializer.dataToBytes(state)),
					serializer.cloneObject(state).data())) {
				assertEquals(messages, restored.get("messages"));
				assertEquals(response, assertInstanceOf(ChatResponse.class, restored.get("llm_response")));
				assertEquals(usage, assertInstanceOf(Usage.class, restored.get("usage")));
				assertEquals(response, ((Map<?, ?>) restored.get("nested")).get("response"));
			}
		}
	}

	@Test
	void preservesBinaryContentAndBusinessMetadata() throws Exception {
		byte[] bytes = new byte[] { 0, 1, 2, -1 };
		Map<String, Object> metadata = Map.of("source", "test", "@type", "SYSTEM", "nested",
				Map.of("@class", "literal", "values", List.of(1, "two")));
		ChatMessage message = new ChatMessage(ChatRole.USER,
				List.of(new ContentPart(ContentPart.Type.IMAGE, null, "image/png", bytes, metadata),
						ContentPart.json("{\"ok\":true}")),
				metadata);
		for (JacksonStateSerializer serializer : serializers()) {
			Map<String, Object> restored = serializer.dataFromBytes(serializer.dataToBytes(Map.of("message", message)));
			ChatMessage actual = assertInstanceOf(ChatMessage.class, restored.get("message"));
			assertEquals(ChatRole.USER, actual.role());
			assertEquals(metadata, actual.metadata());
			assertEquals(metadata, actual.content().get(0).metadata());
			assertArrayEquals(bytes, actual.content().get(0).data());
			assertEquals("image/png", actual.content().get(0).mimeType());
			assertEquals(ContentPart.json("{\"ok\":true}"), actual.content().get(1));
		}
	}

	@Test
	void preservesUnknownFinishReasonAndUsage() throws Exception {
		ChatResponse response = new ChatResponse(null, null, List.of(ChatMessage.assistant("answer")), List.of(), null,
				null, null);
		for (JacksonStateSerializer serializer : serializers()) {
			ChatResponse restored = assertInstanceOf(ChatResponse.class,
					serializer.dataFromBytes(serializer.dataToBytes(Map.of("response", response))).get("response"));
			assertEquals(response, restored);
			assertNull(restored.finishReason());
			assertEquals(Usage.unknown(), restored.usage());
		}
	}

}
