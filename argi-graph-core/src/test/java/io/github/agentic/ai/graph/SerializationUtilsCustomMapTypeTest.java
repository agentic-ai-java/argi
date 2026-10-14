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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.utils.SerializationUtils;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Test for {@link SerializationUtils#deepCopyMap(Map)} to verify that custom Map types
 * (like fastjson2's JSONObject) preserve their type after deep copy operations.
 * <p>
 * This is a regression test for issue #2877 where custom Map implementations extending
 * LinkedHashMap were incorrectly converted to HashMap during deep copy.
 * </p>
 *
 * @see <a href="https://github.com/agentic-ai-java/argi/issues/2877">Issue #2877</a>
 * @since 1.0.0.5
 */
public class SerializationUtilsCustomMapTypeTest {

	/**
	 * Mock JSONObject class that mimics fastjson2's JSONObject behavior. fastjson2's
	 * JSONObject extends LinkedHashMap, so this mock does the same.
	 */
	static class MockJSONObject extends LinkedHashMap<String, Object> {

		public MockJSONObject() {
			super();
		}

		public MockJSONObject(Map<String, Object> map) {
			super(map);
		}

		// JSONObject has additional methods
		public String toJSONString() {
			return this.toString();
		}

	}

	@Test
	public void testDeepCopyPreservesCustomMapType() {
		// Create a MockJSONObject (simulating fastjson2's JSONObject)
		MockJSONObject jsonObject = new MockJSONObject();
		jsonObject.put("name", "test");
		jsonObject.put("age", 25);

		// Put it in a map
		Map<String, Object> original = new HashMap<>();
		original.put("data", jsonObject);

		// Deep copy
		Map<String, Object> copied = SerializationUtils.deepCopyMap(original);

		// Get the copied value
		Object copiedData = copied.get("data");

		// Verify the type is preserved
		assertInstanceOf(MockJSONObject.class, copiedData,
				"Custom Map type (MockJSONObject) should be preserved after deepCopyMap");

		// Verify the data is correctly copied
		MockJSONObject copiedJson = (MockJSONObject) copiedData;
		assertEquals("test", copiedJson.get("name"));
		assertEquals(25, copiedJson.get("age"));
	}

	@Test
	public void testDeepCopyPreservesNestedCustomMapType() {
		// Create nested JSONObjects
		MockJSONObject innerJson = new MockJSONObject();
		innerJson.put("inner", "value");

		MockJSONObject outerJson = new MockJSONObject();
		outerJson.put("nested", innerJson);
		outerJson.put("name", "outer");

		Map<String, Object> original = new HashMap<>();
		original.put("data", outerJson);

		// Deep copy
		Map<String, Object> copied = SerializationUtils.deepCopyMap(original);

		Object copiedData = copied.get("data");

		// Verify outer type is preserved
		assertInstanceOf(MockJSONObject.class, copiedData, "Outer MockJSONObject type should be preserved");

		// Verify nested type is also preserved
		MockJSONObject copiedOuter = (MockJSONObject) copiedData;
		Object nestedData = copiedOuter.get("nested");
		assertInstanceOf(MockJSONObject.class, nestedData, "Nested MockJSONObject type should be preserved");

		// Verify data integrity
		MockJSONObject copiedInner = (MockJSONObject) nestedData;
		assertEquals("value", copiedInner.get("inner"));
		assertEquals("outer", copiedOuter.get("name"));
	}

	@Test
	public void testDeepCopyPreservesUserMessageType() {
		ChatMessage userMessage = ChatMessage.builder(ChatRole.USER)
			.text("Hello from user")
			.metadata(Map.of("user_id", "123"))
			.build();

		Map<String, Object> original = new HashMap<>();
		original.put("messages", List.of(userMessage));

		Map<String, Object> copied = SerializationUtils.deepCopyMap(original);

		Object copiedMessages = copied.get("messages");
		assertInstanceOf(List.class, copiedMessages);
		Object copiedMessage = ((List<?>) copiedMessages).get(0);
		assertInstanceOf(ChatMessage.class, copiedMessage);
		assertNotSame(userMessage, copiedMessage, "ChatMessage should be deep copied instead of reused");

		ChatMessage copiedUserMessage = (ChatMessage) copiedMessage;
		assertEquals(userMessage.text(), copiedUserMessage.text());
		assertEquals(userMessage.metadata(), copiedUserMessage.metadata());
	}

	@Test
	public void testDeepCopyPreservesSystemMessageType() {
		ChatMessage systemMessage = ChatMessage.builder(ChatRole.SYSTEM)
			.text("You are a helpful assistant")
			.metadata(Map.of("source", "system"))
			.build();

		Map<String, Object> original = new HashMap<>();
		original.put("messages", List.of(systemMessage));

		Map<String, Object> copied = SerializationUtils.deepCopyMap(original);

		Object copiedMessages = copied.get("messages");
		assertInstanceOf(List.class, copiedMessages);
		Object copiedMessage = ((List<?>) copiedMessages).get(0);
		assertInstanceOf(ChatMessage.class, copiedMessage);
		assertNotSame(systemMessage, copiedMessage, "ChatMessage should be deep copied instead of reused");

		ChatMessage copiedSystemMessage = (ChatMessage) copiedMessage;
		assertEquals(systemMessage.text(), copiedSystemMessage.text());
		assertEquals(systemMessage.metadata(), copiedSystemMessage.metadata());
	}

	@Test
	public void testDeepCopyValueOfUserMessage() {
		ChatMessage userMessage = ChatMessage.builder(ChatRole.USER)
			.text("Hello from user")
			.metadata(Map.of("user_id", "123"))
			.build();

		Object copied = SerializationUtils.deepCopyValue(userMessage);

		assertInstanceOf(ChatMessage.class, copied);
		assertNotSame(userMessage, copied, "ChatMessage should be a new instance after deep copy");

		ChatMessage copiedUserMessage = (ChatMessage) copied;
		assertEquals(userMessage.text(), copiedUserMessage.text());
		assertEquals(userMessage.metadata(), copiedUserMessage.metadata());
	}

	@Test
	public void testDeepCopyValueOfSystemMessage() {
		ChatMessage systemMessage = ChatMessage.builder(ChatRole.SYSTEM)
			.text("You are a helpful assistant")
			.metadata(Map.of("priority", 1))
			.build();

		Object copied = SerializationUtils.deepCopyValue(systemMessage);

		assertInstanceOf(ChatMessage.class, copied);
		assertNotSame(systemMessage, copied, "ChatMessage should be a new instance after deep copy");

		ChatMessage copiedSystemMessage = (ChatMessage) copied;
		assertEquals(systemMessage.text(), copiedSystemMessage.text());
		assertEquals(systemMessage.metadata(), copiedSystemMessage.metadata());
	}

	@Test
	public void testDeepCopyUserMessageMetadataIsDecoupled() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("user_id", "123");
		metadata.put("nested", new HashMap<>(Map.of("deep", "value")));

		ChatMessage userMessage = ChatMessage.builder(ChatRole.USER).text("Hello").metadata(metadata).build();

		ChatMessage copied = (ChatMessage) SerializationUtils.deepCopyValue(userMessage);

		// The record's outer map is immutable; nested application values still need deep
		// copying.
		@SuppressWarnings("unchecked")
		Map<String, Object> nested = (Map<String, Object>) userMessage.metadata().get("nested");
		nested.put("deep", "changed");

		assertNotEquals(userMessage.metadata().get("nested"), copied.metadata().get("nested"),
				"Copied ChatMessage metadata should be decoupled from the original");
		assertEquals("123", copied.metadata().get("user_id"));
	}

	@Test
	public void testDeepCopyMixedMessagesInList() {
		ChatMessage userMessage = ChatMessage.builder(ChatRole.USER).text("Hello").build();
		ChatMessage systemMessage = ChatMessage.builder(ChatRole.SYSTEM).text("System prompt").build();

		Map<String, Object> original = new HashMap<>();
		original.put("messages", List.of(systemMessage, userMessage));

		Map<String, Object> copied = SerializationUtils.deepCopyMap(original);

		List<?> copiedMessages = (List<?>) copied.get("messages");
		assertEquals(2, copiedMessages.size());
		assertInstanceOf(ChatMessage.class, copiedMessages.get(0));
		assertInstanceOf(ChatMessage.class, copiedMessages.get(1));
		assertNotSame(systemMessage, copiedMessages.get(0));
		assertNotSame(userMessage, copiedMessages.get(1));
	}

	@Test
	public void testOverAllStatePreservesCustomMapType() {
		// Test with OverAllStateBuilder which uses deepCopyMap internally
		MockJSONObject jsonObject = new MockJSONObject();
		jsonObject.put("key1", "value1");
		jsonObject.put("key2", 123);

		Map<String, Object> inputData = new HashMap<>();
		inputData.put("jsonData", jsonObject);

		OverAllState state = OverAllStateBuilder.builder().withData(inputData).build();

		Object retrievedData = state.data().get("jsonData");

		// Verify type is preserved in OverAllState
		assertInstanceOf(MockJSONObject.class, retrievedData,
				"MockJSONObject type should be preserved in OverAllState");

		// Verify data integrity
		MockJSONObject retrieved = (MockJSONObject) retrievedData;
		assertEquals("value1", retrieved.get("key1"));
		assertEquals(123, retrieved.get("key2"));
	}

}
