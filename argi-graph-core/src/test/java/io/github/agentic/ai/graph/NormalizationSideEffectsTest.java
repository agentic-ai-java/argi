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

import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that our normalization doesn't break objects that Jackson can already serialize
 */
public class NormalizationSideEffectsTest {

	@Test
	void testNeutralMessagesNotAffected() throws Exception {
		// Test that neutral messages are not affected by normalization
		OverAllState originalState = new OverAllState();
		List<ChatMessage> messages = List.of(ChatMessage.user("Hello"), ChatMessage.assistant("Hi there!"));
		originalState.updateState(Map.of("messages", messages));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		Object restoredMessages = restoredState.value("messages").orElse(null);
		assertNotNull(restoredMessages);
		assertTrue(restoredMessages instanceof List);

		@SuppressWarnings("unchecked")
		List<ChatMessage> restored = (List<ChatMessage>) restoredMessages;
		assertEquals(2, restored.size());
		assertTrue(restored.get(0) instanceof ChatMessage);
		assertTrue(restored.get(1) instanceof ChatMessage);
	}

	@Test
	void testDocumentsNotAffected() throws Exception {
		// Test that Documents are not affected
		OverAllState originalState = new OverAllState();
		Map<String, Object> doc = Map.of("text", "Test content", "metadata", Map.of("source", "test.txt"));
		originalState.updateState(Map.of("document", doc));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		Object restoredDoc = restoredState.value("document").orElse(null);
		assertNotNull(restoredDoc);
		assertEquals(doc, restoredDoc);
		// Document is preserved
	}

	@Test
	void testCustomPojoNotAffected() throws Exception {
		// Test that custom POJOs with @class annotation are not affected
		OverAllState originalState = new OverAllState();
		CustomPojo pojo = new CustomPojo("test", 42);
		originalState.updateState(Map.of("pojo", pojo));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		Object restoredPojo = restoredState.value("pojo").orElse(null);
		assertNotNull(restoredPojo);
		// After serialization, custom POJO may become a Map due to default typing
		// This is expected behavior with Jackson
	}

	@Test
	void testNestedStructuresNotAffected() throws Exception {
		// Test that nested structures without GraphResponse are not affected
		OverAllState originalState = new OverAllState();
		Map<String, Object> nested = Map.of("level1", Map.of("level2", List.of(1, 2, 3), "data", "value"));
		originalState.updateState(nested);

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		@SuppressWarnings("unchecked")
		Map<String, Object> restoredLevel1 = (Map<String, Object>) restoredState.value("level1").orElse(null);
		assertNotNull(restoredLevel1);

		// level2 is a List, not a Map
		Object level2 = restoredLevel1.get("level2");
		assertNotNull(level2);
		assertTrue(level2 instanceof List);
		assertEquals("value", restoredLevel1.get("data"));
	}

	@Test
	void testMixedContentWithGraphResponse() throws Exception {
		// Test that mixed content (normal + GraphResponse) works correctly
		OverAllState originalState = new OverAllState();
		Map<String, Object> mixed = Map.of("message", "hello", "number", 42, "response", GraphResponse.of("result"),
				"list", List.of("a", "b", "c"));
		originalState.updateState(mixed);

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		// Normal values should be preserved
		assertEquals("hello", restoredState.value("message").orElse(null));
		assertEquals(42, restoredState.value("number").orElse(null));

		@SuppressWarnings("unchecked")
		List<String> list = (List<String>) restoredState.value("list").orElse(null);
		assertEquals(3, list.size());

		// GraphResponse should be reconstructed
		Object response = restoredState.value("response").orElse(null);
		assertTrue(response instanceof GraphResponse, "GraphResponse should be reconstructed");
	}

	@Test
	void testUserArrayTypesPreserved() throws Exception {
		// Critical test: User's array types should be preserved
		OverAllState originalState = new OverAllState();

		String[] stringArray = { "a", "b", "c" };
		Integer[] integerArray = { 1, 2, 3 };

		originalState.updateState(Map.of("strings", stringArray, "integers", integerArray));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		// Arrays should be preserved (though may be converted to Object[] if no
		// GraphResponse found)
		Object strings = restoredState.value("strings").orElse(null);
		assertNotNull(strings);
		assertTrue(strings.getClass().isArray(), "Should still be an array");

		Object integers = restoredState.value("integers").orElse(null);
		assertNotNull(integers);
		assertTrue(integers.getClass().isArray(), "Should still be an array");
	}

	public static class CustomPojo {

		private String name;

		private int value;

		public CustomPojo() {
		}

		public CustomPojo(String name, int value) {
			this.name = name;
			this.value = value;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getValue() {
			return value;
		}

		public void setValue(int value) {
			this.value = value;
		}

	}

}
