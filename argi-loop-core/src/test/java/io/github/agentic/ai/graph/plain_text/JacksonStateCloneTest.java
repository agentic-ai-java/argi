/*
 * Copyright 2025-2026 the original author or authors.
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
import io.github.agentic.ai.api.chat.ChatRole;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JacksonStateCloneTest {

	private final ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);

	@Test
	void cloneMatchesPersistedRoundTripForNumbersArraysAndLargeText() throws Exception {
		Map<String, Object> data = new HashMap<>();
		data.put("numbers", List.of(1, 1L, Long.MAX_VALUE, 0.1F, 0.1D, new BigInteger("123456789012345678901234567890"),
				new BigDecimal("0.12345678901234567890")));
		data.put("array", new int[] { 1, 2, 3 });
		data.put("text", "text-\u20ac\ud83d\ude00".repeat(20_000));
		data.put("null", null);
		var expected = serializer.dataFromBytes(serializer.dataToBytes(data));
		var actual = serializer.cloneObject(data).data();
		assertEquals(expected.keySet(), actual.keySet());
		assertArrayEquals((int[]) expected.get("array"), (int[]) actual.get("array"));
		assertEquals(expected.get("numbers"), actual.get("numbers"));
		assertEquals(expected.get("text"), actual.get("text"));
		assertEquals(expected.get("null"), actual.get("null"));
	}

	@Test
	void nestedMutableStateIsIsolated() throws Exception {
		List<String> values = new ArrayList<>(List.of("original"));
		Map<String, Object> nested = new HashMap<>(Map.of("values", values));
		ChatMessage message = ChatMessage.builder(ChatRole.USER)
			.text("question")
			.metadata(Map.of("source", "original"))
			.build();
		OverAllState original = new OverAllState(Map.of("nested", nested, "message", message));
		OverAllState cloned = serializer.cloneObject(original);
		Map<?, ?> clonedNested = (Map<?, ?>) cloned.data().get("nested");
		assertNotSame(nested, clonedNested);
		assertNotSame(values, clonedNested.get("values"));
		assertNotSame(message, cloned.data().get("message"));
		values.add("changed");
		nested.put("extra", "changed");
		assertEquals(Map.of("values", List.of("original")), clonedNested);
		assertEquals(message, cloned.data().get("message"));
	}

	@Test
	void specialValuesStillUseSnapshotNormalization() throws Exception {
		Map<String, Object> data = Map.of("response", GraphResponse.done("done", Map.of("trace", "trace-1")), "future",
				CompletableFuture.completedFuture("completed"));
		var cloned = serializer.cloneObject(data).data();
		GraphResponse<?> response = assertInstanceOf(GraphResponse.class, cloned.get("response"));
		assertEquals("done", response.resultValue().orElseThrow());
		assertEquals("trace-1", response.getMetadata("trace"));
		assertEquals("completed", assertInstanceOf(CompletableFuture.class, cloned.get("future")).join());
	}

	@Test
	void clonePreservesTopLevelBusinessKeysThatLookLikeTypeMarkers() throws Exception {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("@type", "invoice");
		data.put("@class", "customer-facing");
		data.put("@typeHint", "external-system");
		data.put("id", "123");

		Map<String, Object> cloned = serializer.cloneObject(data).data();

		assertEquals(data, cloned);
	}

	@Test
	void clonePreservesNestedBusinessMapsThatLookLikeTypedValues() throws Exception {
		Map<String, Object> invoice = new InvoiceMap();
		invoice.put("@type", "invoice");
		invoice.put("@class", "customer-facing");
		invoice.put("@typeHint", "external-system");
		invoice.put("id", "123");
		Map<String, Object> knownMarkerValues = new LinkedHashMap<>();
		knownMarkerValues.put("@type", "SYSTEM");
		knownMarkerValues.put("@class", ChatMessage.class.getName());
		knownMarkerValues.put("id", "456");

		Map<String, Object> data = Map.of("payload", invoice, "knownMarkerValues", knownMarkerValues, "history",
				List.of(invoice, new LinkedHashMap<>(Map.of("@type", "invoice-line", "sku", "SKU-1"))));

		Map<String, Object> cloned = serializer.cloneObject(data).data();

		assertInstanceOf(InvoiceMap.class, cloned.get("payload"));
		assertEquals(invoice, cloned.get("payload"));
		assertEquals(knownMarkerValues, cloned.get("knownMarkerValues"));
		assertEquals(List.of(invoice, Map.of("@type", "invoice-line", "sku", "SKU-1")), cloned.get("history"));
	}

	@Test
	void cloneUsesTheConfiguredStateFactoryAndRejectsNull() throws Exception {
		AtomicInteger factoryCalls = new AtomicInteger();
		var custom = new ArgiJacksonStateSerializer(data -> {
			factoryCalls.incrementAndGet();
			return new OverAllState(data);
		});
		assertEquals(Map.of("value", 1), custom.cloneObject(Map.of("value", 1)).data());
		assertEquals(2, factoryCalls.get());
		assertThrows(NullPointerException.class, () -> custom.cloneObject((OverAllState) null));
	}

	public static class InvoiceMap extends LinkedHashMap<String, Object> {

	}

}
