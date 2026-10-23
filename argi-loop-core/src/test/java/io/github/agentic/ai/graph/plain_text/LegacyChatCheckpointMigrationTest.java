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
package io.github.agentic.ai.graph.plain_text;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.Serializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.JacksonDeserializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.TypeMapper;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures mirror the historical handlers without requiring provider classes to load. */
class LegacyChatCheckpointMigrationTest {

	private final ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);

	private Object read(String json) throws IOException {
		return JacksonDeserializer.valueFromNode(serializer.objectMapper().readTree(json), serializer.objectMapper(),
				new TypeMapper());
	}

	@Test
	void migratesPersistedHistoryAndWritesOnlyNeutralRecords() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (var output = new ObjectOutputStream(bytes)) {
			Serializer.writeUTF("""
					{"messages":[{"@class":"org.springframework.ai.chat.messages.SystemMessage","text":"system"},
					{"@type":"ASSISTANT","text":"answer"},
					{"@type":"TOOL","responses":[{"id":"id","name":"search","responseData":"result"}]}]}
					""", output);
		}
		OverAllState state;
		try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			state = serializer.read(input);
		}
		assertEquals(List.of(ChatMessage.system("system"), ChatMessage.assistant("answer"),
				ChatMessage.tool("id", "search", "result")), state.data().get("messages"));
		var rewritten = serializer.dataToBytes(state.data());
		assertEquals(state.data(), serializer.dataFromBytes(rewritten));
		try (var input = new ObjectInputStream(new ByteArrayInputStream(rewritten))) {
			String json = Serializer.readUTF(input);
			assertTrue(json.contains("ARGI_CHAT_MESSAGE"));
			assertTrue(!json.contains("org.springframework.ai"));
		}
	}

	@Test
	void readsBothWireFormatsForEverySupportedRoleAndReasoningProvider() throws Exception {
		Map<String, String> markers = Map.of("SYSTEM", "org.springframework.ai.chat.messages.SystemMessage", "USER",
				"org.springframework.ai.chat.messages.UserMessage", "ASSISTANT",
				"org.springframework.ai.chat.messages.AssistantMessage", "DEEPSEEK_ASSISTANT",
				"org.springframework.ai.deepseek.DeepSeekAssistantMessage", "ZHI_PU_AI_ASSISTANT",
				"org.springframework.ai.zhipuai.ZhiPuAiAssistantMessage");
		for (var entry : markers.entrySet()) {
			for (String key : List.of("@type", "@class", "@typeHint")) {
				String marker = key.equals("@type") ? entry.getKey() : entry.getValue();
				ChatMessage message = assertInstanceOf(ChatMessage.class, read("""
						{"%s":"%s","text":"history","metadata":{"source":"old","nested":{"n":1}},
						 "reasoningContent":"analysis"}
						""".formatted(key, marker)));
				ChatRole role = switch (entry.getKey()) {
					case "SYSTEM" -> ChatRole.SYSTEM;
					case "USER" -> ChatRole.USER;
					default -> ChatRole.ASSISTANT;
				};
				assertEquals(role, message.role());
				assertEquals("history", message.text());
				assertEquals("old", message.metadata().get("source"));
				assertEquals(Map.of("n", 1), message.metadata().get("nested"));
				assertEquals("analysis", message.metadata().get(ChatMetadataKeys.REASONING));
				assertEquals(message, serializer.dataFromBytes(serializer.dataToBytes(Map.of("m", message))).get("m"));
			}
		}
	}

	@Test
	void preservesToolCallArgumentsAndMetadataReasoning() throws Exception {
		ChatMessage message = assertInstanceOf(ChatMessage.class, read("""
				{"@type":"ASSISTANT","text":null,"metadata":{"reasoningContent":"plan"},
				 "toolCalls":[{"id":"call-1","name":"search","type":"function","arguments":"{\\"q\\":1}"}]}
				"""));
		assertEquals(List.of(new ToolCall("call-1", "search", "{\"q\":1}")), message.toolCalls());
		assertEquals(Map.of(ChatMetadataKeys.REASONING, "plan"), message.metadata());
	}

	@Test
	void expandsGroupedToolResultsWithoutFlatteningBusinessLists() throws Exception {
		for (String key : List.of("@type", "@class", "@typeHint")) {
			String marker = key.equals("@type") ? "TOOL" : "org.springframework.ai.chat.messages.ToolResponseMessage";
			String payload = """
					[{"%s":"%s","metadata":{"source":"old"},"responses":[
					    {"id":"a","name":"one","responseData":"1"},
					    {"id":"b","name":"two","responseData":"2"}]},["business",{"x":1}]]
					""".formatted(key, marker);
			for (String json : List.of(payload, "[\"java.util.ArrayList\"," + payload + "]")) {
				List<?> values = assertInstanceOf(List.class, read(json));
				assertEquals(3, values.size());
				assertEquals(ChatMessage.tool("a", "one", "1").withMetadata(Map.of("source", "old")), values.get(0));
				assertEquals(ChatMessage.tool("b", "two", "2").withMetadata(Map.of("source", "old")), values.get(1));
				assertEquals(List.of("business", Map.of("x", 1)), values.get(2));
			}
		}
	}

	@Test
	void refusesUnsupportedOrMalformedLegacyDataInsteadOfLosingHistory() {
		for (String json : List.of("{\"@type\":\"TEMPLATED_USER\",\"text\":\"template\"}",
				"{\"@class\":\"org.springframework.ai.unknown.Message\"}",
				"{\"@type\":\"ChatResponse\",\"results\":[]}", "{\"@type\":\"TOOL\",\"responses\":[]}",
				"{\"@type\":\"TOOL\",\"responses\":[{\"id\":\"x\"}]}", "{\"@type\":\"USER\",\"text\":42}",
				"{\"@type\":\"USER\",\"media\":[{}]}", "{\"@type\":\"USER\",\"metadata\":[]}",
				"{\"@type\":\"USER\",\"metadata\":{\"source\":null}}",
				"{\"@type\":\"TOOL\",\"responses\":[{\"id\":\"\",\"name\":\"search\",\"responseData\":\"x\"}]}",
				"{\"@type\":\"ASSISTANT\",\"toolCalls\":{}}",
				"{\"@type\":\"ASSISTANT\",\"toolCalls\":[{\"type\":\"other\"}]}")) {
			assertThrows(IOException.class, () -> read(json), json);
		}
	}

	@Test
	void acceptsNullMetadataAndRejectsOldStreamingRecordsExplicitly() throws Exception {
		assertEquals(ChatMessage.user("hello"), read("{\"@type\":\"USER\",\"text\":\"hello\",\"metadata\":null}"));
		IOException error = assertThrows(IOException.class,
				() -> read("{\"@type\":\"ARGI_STREAMING_OUTPUT\",\"message\":{}}"));
		assertTrue(error.getMessage().contains("Legacy StreamingOutput"));
	}

}
