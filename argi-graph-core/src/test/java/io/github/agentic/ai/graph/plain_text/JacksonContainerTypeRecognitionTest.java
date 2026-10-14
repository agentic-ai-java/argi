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

import io.github.agentic.ai.api.chat.ChatMessage;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import io.github.agentic.ai.graph.state.AgentStateFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for Jackson generic container deserialization to verify recognition of
 * both "@type" and "@class" markers when restoring nested elements.
 */
class JacksonContainerTypeRecognitionTest {

	private ArgiJacksonStateSerializer serializer;

	@BeforeEach
	void setUp() {
		AgentStateFactory<OverAllState> stateFactory = OverAllState::new;
		serializer = new ArgiJacksonStateSerializer(stateFactory);
	}

	// EOF

	@Test
	void deserializeNeutralMessageInMap() throws Exception {
		ChatMessage message = ChatMessage.system("hello").withMetadata(Map.of("k", "v"));
		Map<String, Object> restored = serializer.dataFromBytes(serializer.dataToBytes(Map.of("msg", message)));
		assertEquals(message, restored.get("msg"));
	}

	@Test
	void deserializeNeutralMessageInList() throws Exception {
		List<ChatMessage> messages = List.of(ChatMessage.user("hi").withMetadata(Map.of("foo", "bar")));
		Map<String, Object> restored = serializer.dataFromBytes(serializer.dataToBytes(Map.of("messages", messages)));
		assertEquals(messages, restored.get("messages"));
	}

	@Test
	void defaultTypingPreservesCustomPojo() throws Exception {
		// Insert a custom POJO into state; default typing should preserve type via @class
		SamplePojo pojo = new SamplePojo();
		pojo.name = "neo";
		pojo.age = 42;

		Map<String, Object> data = new HashMap<>();
		data.put("pojo", pojo);

		// Round-trip through serializer writeData/readData
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
			serializer.writeData(data, oos);
			oos.flush();
		}

		Map<String, Object> restored;
		try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
			restored = serializer.readData(ois);
		}

		assertNotNull(restored);
		assertTrue(restored.get("pojo") instanceof SamplePojo);
		SamplePojo r = (SamplePojo) restored.get("pojo");
		assertEquals("neo", r.name);
		assertEquals(42, r.age);
	}

	@Test
	void fallbackToGenericObjectWhenTypeAbsent() throws Exception {
		// No @type or @class -> should degrade to generic Map structure
		String json = "{" + "\"unknown\": { \"field\": \"value\" }" + "}";

		ObjectMapper mapper = serializer.objectMapper();
		Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});

		assertNotNull(data);
		assertTrue(data.get("unknown") instanceof Map<?, ?>);
		@SuppressWarnings("unchecked")
		Map<String, Object> inner = (Map<String, Object>) data.get("unknown");
		assertEquals("value", inner.get("field"));
	}

	@Test
	void fallbackToGenericObjectWhenUnknownTypeMarkerIsBusinessData() throws Exception {
		String json = "{" + "\"payload\": {" + "\"@type\": \"invoice\"," + "\"@class\": \"customer-facing\","
				+ "\"@typeHint\": \"external-system\"," + "\"id\": \"123\"" + "}" + "}";

		ObjectMapper mapper = serializer.objectMapper();
		Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});

		assertNotNull(data);
		assertTrue(data.get("payload") instanceof Map<?, ?>);
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) data.get("payload");
		assertEquals("invoice", payload.get("@type"));
		assertEquals("customer-facing", payload.get("@class"));
		assertEquals("external-system", payload.get("@typeHint"));
		assertEquals("123", payload.get("id"));
	}

	@Test
	void fallbackToGenericObjectWhenTopLevelArgiMapMarkerIsBusinessData() throws Exception {
		String json = "{" + "\"@type\": \"ARGI_MAP\"," + "\"id\": \"legacy-business-map\","
				+ "\"entries\": \"not-an-envelope\"" + "}";

		ObjectMapper mapper = serializer.objectMapper();
		Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});

		assertEquals("ARGI_MAP", data.get("@type"));
		assertEquals("legacy-business-map", data.get("id"));
		assertEquals("not-an-envelope", data.get("entries"));
	}

	@Test
	void fallbackToGenericObjectWhenNestedArgiMapMarkerIsBusinessData() throws Exception {
		String json = "{" + "\"payload\": {" + "\"@type\": \"ARGI_MAP\"," + "\"id\": \"legacy-business-map\","
				+ "\"entries\": \"not-an-envelope\"" + "}" + "}";

		ObjectMapper mapper = serializer.objectMapper();
		Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});

		assertTrue(data.get("payload") instanceof Map<?, ?>);
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) data.get("payload");
		assertEquals("ARGI_MAP", payload.get("@type"));
		assertEquals("legacy-business-map", payload.get("id"));
		assertEquals("not-an-envelope", payload.get("entries"));
	}

	@Test
	void fallbackToGenericObjectWhenArgiMapMarkerHasBusinessFieldsBesideTypedEntries() throws Exception {
		String json = "{" + "\"payload\": {" + "\"@class\": \"java.util.LinkedHashMap\"," + "\"@type\": \"ARGI_MAP\","
				+ "\"mapClass\": \"java.util.LinkedHashMap\","
				+ "\"entries\": [\"[Ljava.lang.Object;\", [[\"[Ljava.lang.Object;\", [\"id\", \"from-entries\"]]]],"
				+ "\"id\": \"legacy-business-map\"" + "}" + "}";

		ObjectMapper mapper = serializer.objectMapper();
		Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});

		assertTrue(data.get("payload") instanceof Map<?, ?>);
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) data.get("payload");
		assertEquals("java.util.LinkedHashMap", payload.get("@class"));
		assertEquals("ARGI_MAP", payload.get("@type"));
		assertEquals("java.util.LinkedHashMap", payload.get("mapClass"));
		assertEquals("legacy-business-map", payload.get("id"));
		assertTrue(payload.containsKey("entries"));
	}

	@Test
	void deserializeGeneratedMapEnvelopeWhenShapeIsValid() throws Exception {
		String json = "{" + "\"payload\": {" + "\"@class\": \"java.util.LinkedHashMap\"," + "\"@type\": \"ARGI_MAP\","
				+ "\"mapClass\": \"java.util.LinkedHashMap\"," + "\"entries\": [\"[Ljava.lang.Object;\", ["
				+ "[\"[Ljava.lang.Object;\", [\"@type\", \"invoice\"]],"
				+ "[\"[Ljava.lang.Object;\", [\"id\", \"123\"]]" + "]]" + "}" + "}";

		ObjectMapper mapper = serializer.objectMapper();
		Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});

		assertEquals(Map.of("@type", "invoice", "id", "123"), data.get("payload"));
	}

	/**
	 * Simple non-final POJO with public fields to let Jackson bind without setters.
	 */
	static class SamplePojo {

		public String name;

		public int age;

	}

}
