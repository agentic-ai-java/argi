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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import io.github.agentic.ai.api.chat.ChatMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.agentic.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonMapperReuseTest {

	@Test
	void longHistoryReusesMapperAcrossMessagesAndCalls() throws Exception {
		CountingMapper mapper = new CountingMapper();
		var serializer = new ArgiJacksonStateSerializer(OverAllState::new, mapper);
		List<ChatMessage> messages = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			messages.add(ChatMessage.user("question-" + i));
			messages.add(ChatMessage.assistant("answer-" + i));
		}
		byte[] bytes = serializer.dataToBytes(Map.of("messages", messages));
		for (int i = 0; i < 3; i++) {
			List<?> restored = (List<?>) serializer.dataFromBytes(bytes).get("messages");
			assertEquals(messages, restored);
			assertNotSame(messages.get(0), restored.get(0));
		}
		assertEquals(0, mapper.copies.get(), "Neutral chat records need no polymorphic mapper copy");
	}

	@Test
	void typedListsKeepTheirElementTypesWithoutCopyingAgain() throws Exception {
		CountingMapper mapper = new CountingMapper();
		var serializer = new ArgiJacksonStateSerializer(OverAllState::new, mapper);
		Envelope input = new Envelope();
		input.values = List.of(new Value("first"), new Value("second"));
		Envelope restored = (Envelope) serializer.cloneObject(Map.of("envelope", input)).data().get("envelope");
		assertEquals("first", restored.values.get(0).text);
		assertEquals("second", restored.values.get(1).text);
		assertNotSame(input.values.get(0), restored.values.get(0));
		assertEquals(1, mapper.copies.get());
	}

	@Test
	void incompatibleArrayElementsRetainTheGenericArrayFallback() throws Exception {
		CountingMapper mapper = new CountingMapper();
		var serializer = new ArgiJacksonStateSerializer(OverAllState::new, mapper);
		String json = "{\"array\":[\"" + Value[].class.getName() + "\",[1]]}";
		Object restored = serializer.objectMapper().readValue(json, Map.class).get("array");
		assertEquals(Object[].class, restored.getClass());
		assertEquals(1, ((Object[]) restored)[0]);
		assertEquals(1, mapper.copies.get());
	}

	@Test
	void mapperCacheRefreshesAfterConfigurationChanges() {
		var serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		ObjectMapper source = serializer.objectMapper();
		TypeMapper types = serializer.typeMapper();
		ObjectMapper first = types.mapperWithoutDefaultTyping(source);
		assertSame(first, types.mapperWithoutDefaultTyping(source));
		assertSame(first, types.mapperWithoutDefaultTyping(first));
		assertTrue(source.getDeserializationConfig().getDefaultTyper(null) != null);

		source.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
		ObjectMapper second = types.mapperWithoutDefaultTyping(source);
		assertNotSame(first, second);
		assertTrue(second.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY));

		source.enable(SerializationFeature.INDENT_OUTPUT);
		ObjectMapper third = types.mapperWithoutDefaultTyping(source);
		assertNotSame(second, third);
		assertTrue(third.isEnabled(SerializationFeature.INDENT_OUTPUT));
	}

	@Test
	void newModulesAreReflectedWithoutLeakingAcrossSerializers() throws Exception {
		var first = new ArgiJacksonStateSerializer(OverAllState::new);
		var second = new ArgiJacksonStateSerializer(OverAllState::new);
		ObjectMapper initial = first.typeMapper().mapperWithoutDefaultTyping(first.objectMapper());
		first.objectMapper().registerModule(valueModule("first"));
		second.objectMapper().registerModule(valueModule("second"));
		assertNotSame(initial, first.typeMapper().mapperWithoutDefaultTyping(first.objectMapper()));
		Map<String, Object> input = Map.of("value", new Value("original"));
		assertEquals("first", ((Value) first.cloneObject(input).data().get("value")).text);
		assertEquals("second", ((Value) second.cloneObject(input).data().get("value")).text);
	}

	@Test
	void concurrentFirstUsePublishesOneMapper() throws Exception {
		CountingMapper mapper = new CountingMapper();
		var serializer = new ArgiJacksonStateSerializer(OverAllState::new, mapper);
		byte[] input = serializer.dataToBytes(Map.of("value", new Value("concurrent")));
		var executor = Executors.newFixedThreadPool(8);
		CountDownLatch ready = new CountDownLatch(8);
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Object>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> {
				ready.countDown();
				assertTrue(start.await(10, TimeUnit.SECONDS));
				return serializer.dataFromBytes(input).get("value");
			});
		}
		try {
			var futures = tasks.stream().map(executor::submit).toList();
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			for (var future : futures) {
				assertEquals("concurrent", assertInstanceOf(Value.class, future.get(10, TimeUnit.SECONDS)).text);
			}
			assertEquals(1, mapper.copies.get());
		}
		finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	private SimpleModule valueModule(String text) {
		SimpleModule module = new SimpleModule();
		module.addDeserializer(Value.class, new JsonDeserializer<>() {
			@Override
			public Value deserialize(JsonParser parser, DeserializationContext context) throws IOException {
				parser.skipChildren();
				return new Value(text);
			}
		});
		return module;
	}

	static class Envelope {

		public List<Value> values;

	}

	static class Value {

		public String text;

		public Value() {
		}

		Value(String text) {
			this.text = text;
		}

	}

	static class CountingMapper extends ObjectMapper {

		private final AtomicInteger copies;

		CountingMapper() {
			copies = new AtomicInteger();
		}

		private CountingMapper(CountingMapper source) {
			super(source);
			copies = source.copies;
		}

		@Override
		public ObjectMapper copy() {
			copies.incrementAndGet();
			return new CountingMapper(this);
		}

	}

}
