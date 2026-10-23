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
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Neutral responses and durations must persist, rather than being discarded. */
class DurationAndChatResponseSerializationTest {

	private final ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);

	private static ChatResponse response(String text) {
		return new ChatResponse("response", "model", List.of(ChatMessage.assistant(text)), List.of(), FinishReason.STOP,
				new Usage(1L, 2L, 3L), Map.of("latency", Duration.ofMillis(1500)));
	}

	@Test
	void testDurationSerialization() throws Exception {
		assertEquals(Duration.ofSeconds(30), copy(Map.of("duration", Duration.ofSeconds(30))).get("duration"));
	}

	@Test
	void testDurationInMetadata() throws Exception {
		Map<String, Object> metadata = Map.of("execution_time", Duration.ofMillis(1500), "timeout",
				Duration.ofMinutes(5));
		assertEquals(metadata, copy(Map.of("metadata", metadata)).get("metadata"));
	}

	@Test
	void testChatResponseSerialization() throws Exception {
		ChatResponse response = response("Test response");
		assertEquals(response, copy(Map.of("response", response)).get("response"));
	}

	@Test
	void testChatResponseInNestedMap() throws Exception {
		Map<String, Object> nested = Map.of("response", response("nested"), "status", "complete");
		assertEquals(nested, copy(Map.of("nested", nested)).get("nested"));
	}

	@Test
	void testChatResponseInList() throws Exception {
		List<?> responses = List.of(response("first"), response("second"), "string");
		assertEquals(responses, copy(Map.of("responses", responses)).get("responses"));
	}

	@Test
	void testDurationAndChatResponseTogether() throws Exception {
		Map<String, Object> data = Map.of("duration", Duration.ofSeconds(60), "response", response("combined"),
				"metadata", Map.of("timeout", Duration.ofMinutes(2)));
		assertEquals(data, copy(data));
	}

	@Test
	void testChatResponseWithDurationInMetadata() throws Exception {
		ChatResponse original = response("metadata");
		ChatResponse restored = (ChatResponse) copy(Map.of("response", original)).get("response");
		assertEquals(original, restored);
		assertEquals(Duration.ofMillis(1500), restored.metadata().get("latency"));
	}

	@Test
	void testSerializationRoundTripWithRawData() throws Exception {
		Map<String, Object> data = Map.of("duration", Duration.ofHours(1), "response", response("raw"), "string",
				"test");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			serializer.writeData(data, out);
		}
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertEquals(data, serializer.readData(in));
		}
	}

	private Map<String, Object> copy(Map<String, Object> data) throws Exception {
		return serializer.cloneObject(new OverAllState(data)).data();
	}

}
