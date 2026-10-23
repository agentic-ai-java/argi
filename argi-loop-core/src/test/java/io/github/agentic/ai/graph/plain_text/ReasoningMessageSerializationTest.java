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
package io.github.agentic.ai.graph.plain_text;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import io.github.agentic.ai.graph.state.AgentStateFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test case for ChatMessage serialization/deserialization bug fix. This test reproduces
 * issue #3186 where ChatMessage fails to deserialize when it appears in arrays or nested
 * structures.
 */
class ReasoningMessageSerializationTest {

	private ArgiJacksonStateSerializer serializer;

	@BeforeEach
	void setUp() {
		AgentStateFactory<OverAllState> stateFactory = OverAllState::new;
		serializer = new ArgiJacksonStateSerializer(stateFactory);
	}

	/**
	 * Create a ChatMessage instance.
	 */
	private ChatMessage createDeepSeekMessage(String text, String reasoningContent, Map<String, Object> metadata,
			List<ToolCall> toolCalls) {
		Map<String, Object> values = new HashMap<>(metadata);
		if (reasoningContent != null)
			values.put(ChatMetadataKeys.REASONING, reasoningContent);
		return ChatMessage.assistant(text, toolCalls).withMetadata(values);
	}

	@Test
	void testDeepSeekAssistantMessageInArray() throws Exception {
		// This test reproduces the bug reported in issue #3186
		// The bug occurs when ChatMessage is in an array/list

		// Create a ChatMessage with tool calls
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("model", "deepseek-chat");
		metadata.put("temperature", 0.7);

		List<ToolCall> toolCalls = List.of(new ToolCall("call_1", "calculator", "{\"a\": 1, \"b\": 2}"),
				new ToolCall("call_2", "weather", "{\"city\": \"Beijing\"}"));

		ChatMessage originalMessage1 = createDeepSeekMessage("Let me calculate that for you.",
				"I need to use the calculator tool to add 1 and 2.", metadata, toolCalls);

		ChatMessage originalMessage2 = createDeepSeekMessage("The weather in Beijing is sunny.",
				"I used the weather tool to get the current weather.", new HashMap<>(), List.of());

		// Create a list containing ChatMessage objects
		// This is the scenario that triggers the bug
		List<Object> messages = List.of(originalMessage1, originalMessage2);

		// Wrap in a map for serialization
		Map<String, Object> data = new HashMap<>();
		data.put("messages", messages);

		// Serialize
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		serializer.writeData(data, oos);
		oos.flush();

		// Deserialize - this should work after the fix
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		ObjectInputStream ois = new ObjectInputStream(bais);
		Map<String, Object> deserializedData = serializer.readData(ois);

		// Verify deserialization succeeded
		assertNotNull(deserializedData);
		assertTrue(deserializedData.containsKey("messages"));

		@SuppressWarnings("unchecked")
		List<Object> deserializedMessages = (List<Object>) deserializedData.get("messages");
		assertNotNull(deserializedMessages);
		assertEquals(2, deserializedMessages.size());

		// Verify first message
		Object deserializedMessage1 = deserializedMessages.get(0);
		assertNotNull(deserializedMessage1);
		assertTrue(deserializedMessage1 instanceof ChatMessage);
		ChatMessage msg1 = (ChatMessage) deserializedMessage1;
		assertEquals("Let me calculate that for you.", msg1.text());
		assertEquals("I need to use the calculator tool to add 1 and 2.",
				msg1.metadata().get(ChatMetadataKeys.REASONING));

		// Verify metadata - messageType may be automatically added during deserialization
		Map<String, Object> deserializedMetadata1 = msg1.metadata();
		assertNotNull(deserializedMetadata1);
		assertEquals("deepseek-chat", deserializedMetadata1.get("model"));
		assertEquals(0.7, deserializedMetadata1.get("temperature"));
		// messageType may be present in deserialized metadata

		assertEquals(2, msg1.toolCalls().size());

		// Verify second message
		Object deserializedMessage2 = deserializedMessages.get(1);
		assertNotNull(deserializedMessage2);
		assertTrue(deserializedMessage2 instanceof ChatMessage);
		ChatMessage msg2 = (ChatMessage) deserializedMessage2;
		assertEquals("The weather in Beijing is sunny.", msg2.text());
		assertEquals("I used the weather tool to get the current weather.",
				msg2.metadata().get(ChatMetadataKeys.REASONING));

		// Verify metadata - messageType may be automatically added during deserialization
		Map<String, Object> deserializedMetadata2 = msg2.metadata();
		assertNotNull(deserializedMetadata2);
		// Original metadata was empty, but messageType may be added during
		// deserialization
		// So we only check that our original fields are not present
		assertNull(deserializedMetadata2.get("model"));
		assertNull(deserializedMetadata2.get("temperature"));

		assertTrue(msg2.toolCalls().isEmpty());
	}

	@Test
	void testDeepSeekAssistantMessageInNestedStructure() throws Exception {
		// Test ChatMessage in nested structures (Map containing List)

		ChatMessage originalMessage = createDeepSeekMessage("Nested structure test", "Testing nested serialization",
				Map.of("test", "nested"), List.of());

		// Create nested structure
		Map<String, Object> innerMap = new HashMap<>();
		innerMap.put("deepSeekMessage", originalMessage);
		innerMap.put("otherData", "test");

		Map<String, Object> outerMap = new HashMap<>();
		outerMap.put("inner", innerMap);
		outerMap.put("messages", List.of(originalMessage));

		// Serialize
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		serializer.writeData(outerMap, oos);
		oos.flush();

		// Deserialize
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		ObjectInputStream ois = new ObjectInputStream(bais);
		Map<String, Object> deserializedData = serializer.readData(ois);

		// Verify
		assertNotNull(deserializedData);

		@SuppressWarnings("unchecked")
		Map<String, Object> deserializedInner = (Map<String, Object>) deserializedData.get("inner");
		assertNotNull(deserializedInner);

		Object deserializedMessage = deserializedInner.get("deepSeekMessage");
		assertNotNull(deserializedMessage);
		assertTrue(deserializedMessage instanceof ChatMessage);
		ChatMessage msg = (ChatMessage) deserializedMessage;
		assertEquals("Nested structure test", msg.text());

		@SuppressWarnings("unchecked")
		List<Object> deserializedMessages = (List<Object>) deserializedData.get("messages");
		assertNotNull(deserializedMessages);
		assertEquals(1, deserializedMessages.size());
		assertTrue(deserializedMessages.get(0) instanceof ChatMessage);
	}

	@Test
	void testDeepSeekAssistantMessageAsTopLevelObject() throws Exception {
		// Test ChatMessage as a top-level object (should work even before fix)

		ChatMessage originalMessage = createDeepSeekMessage("Top level message", "Reasoning content",
				Map.of("key", "value"), List.of(new ToolCall("id", "name", "args")));

		Map<String, Object> data = new HashMap<>();
		data.put("message", originalMessage);

		// Serialize and deserialize
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		serializer.writeData(data, oos);
		oos.flush();

		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		ObjectInputStream ois = new ObjectInputStream(bais);
		Map<String, Object> deserializedData = serializer.readData(ois);

		// Verify
		assertNotNull(deserializedData);
		Object deserializedMessage = deserializedData.get("message");
		assertNotNull(deserializedMessage);
		assertTrue(deserializedMessage instanceof ChatMessage);
		ChatMessage msg = (ChatMessage) deserializedMessage;
		assertEquals("Top level message", msg.text());
		assertEquals("Reasoning content", msg.metadata().get(ChatMetadataKeys.REASONING));
		assertEquals(1, msg.toolCalls().size());
	}

	@Test
	void testDeepSeekAssistantMessageWithNullReasoningContent() throws Exception {
		// Test ChatMessage with null reasoning content

		ChatMessage originalMessage = createDeepSeekMessage("Message without reasoning", null, // null
																								// reasoning
																								// content
				new HashMap<>(), List.of());

		List<Object> messages = List.of(originalMessage);
		Map<String, Object> data = new HashMap<>();
		data.put("messages", messages);

		// Serialize and deserialize
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		serializer.writeData(data, oos);
		oos.flush();

		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		ObjectInputStream ois = new ObjectInputStream(bais);
		Map<String, Object> deserializedData = serializer.readData(ois);

		// Verify
		@SuppressWarnings("unchecked")
		List<Object> deserializedMessages = (List<Object>) deserializedData.get("messages");
		assertNotNull(deserializedMessages);
		assertEquals(1, deserializedMessages.size());

		Object deserializedMessage = deserializedMessages.get(0);
		assertNotNull(deserializedMessage);
		assertTrue(deserializedMessage instanceof ChatMessage);
		ChatMessage msg = (ChatMessage) deserializedMessage;
		assertEquals("Message without reasoning", msg.text());
		assertEquals(null, msg.metadata().get(ChatMetadataKeys.REASONING));
	}

}
