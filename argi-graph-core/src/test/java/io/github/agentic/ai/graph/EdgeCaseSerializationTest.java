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

import io.github.agentic.ai.graph.serializer.Serializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test edge cases for serialization
 */
public class EdgeCaseSerializationTest {

	@Test
	void testPrimitiveArraySerialization() throws Exception {
		// Test that primitive arrays are handled correctly
		OverAllState originalState = new OverAllState();
		int[] primitiveArray = { 1, 2, 3 };
		originalState.updateState(Map.of("numbers", primitiveArray));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);

		// This should not throw ClassCastException
		assertDoesNotThrow(() -> {
			serializer.cloneObject(originalState);
		}, "Primitive arrays should be handled gracefully");
	}

	@Test
	void testCancelledFutureSerialization() throws Exception {
		// Test that cancelled CompletableFuture is handled correctly
		OverAllState originalState = new OverAllState();
		CompletableFuture<String> cancelledFuture = new CompletableFuture<>();
		cancelledFuture.cancel(true);
		originalState.updateState(Map.of("cancelled", cancelledFuture));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);

		assertDoesNotThrow(() -> {
			OverAllState restoredState = serializer.cloneObject(originalState);
			assertNotNull(restoredState);
		}, "Cancelled CompletableFuture should be handled gracefully");
	}

	@Test
	void testEmptyGraphResponseSerialization() throws Exception {
		// Test GraphResponse with null values
		OverAllState originalState = new OverAllState();
		GraphResponse<?> emptyResponse = GraphResponse.done(null);
		originalState.updateState(Map.of("empty", emptyResponse));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		Object restoredValue = restoredState.value("empty").orElse(null);
		assertNotNull(restoredValue, "Empty GraphResponse should be preserved");
		assertTrue(restoredValue instanceof GraphResponse, "Type should be preserved");

		@SuppressWarnings("unchecked")
		GraphResponse<Object> restoredResponse = (GraphResponse<Object>) restoredValue;
		assertTrue(restoredResponse.resultValue().isEmpty(), "Null result should be preserved");
	}

	@Test
	void testLargeStateSerialization() throws Exception {
		OverAllState originalState = new OverAllState();
		Map<String, Object> largeMap = new HashMap<>();
		for (int i = 0; i < 1000; i++) {
			largeMap.put("key" + i, "value" + i);
		}
		originalState.updateState(largeMap);

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);

		OverAllState restoredState = assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> serializer.cloneObject(originalState));

		assertNotNull(restoredState);
		assertEquals("value999", restoredState.value("key999").orElseThrow(), "Restored state should preserve values");
	}

	@Test
	void testNestedGraphResponseSerialization() throws Exception {
		// Test deeply nested GraphResponse
		OverAllState originalState = new OverAllState();

		GraphResponse<?> inner = GraphResponse.of("inner value");
		Map<String, Object> middleMap = Map.of("inner_response", inner);
		GraphResponse<?> outer = GraphResponse.done(middleMap);

		originalState.updateState(Map.of("nested", outer));

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		OverAllState restoredState = serializer.cloneObject(originalState);

		Object restoredValue = restoredState.value("nested").orElse(null);
		assertNotNull(restoredValue);
		assertTrue(restoredValue instanceof GraphResponse, "Outer GraphResponse should be preserved");
	}

	@Test
	void testWrapperArrayLongDeserialization() throws Exception {
		String json = "{\"result\":[\"java.lang.Long\",300]}";
		byte[] payload;
		try (ByteArrayOutputStream stream = new ByteArrayOutputStream();
				ObjectOutputStream out = new ObjectOutputStream(stream)) {
			Serializer.writeUTF(json, out);
			out.flush();
			payload = stream.toByteArray();
		}

		ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		Map<String, Object> restored = serializer.dataFromBytes(payload);

		Object result = restored.get("result");
		assertInstanceOf(Long.class, result, "Wrapper array Long should deserialize as Long");
		assertEquals(300L, result, "Wrapper array Long value should be preserved");
	}

}
