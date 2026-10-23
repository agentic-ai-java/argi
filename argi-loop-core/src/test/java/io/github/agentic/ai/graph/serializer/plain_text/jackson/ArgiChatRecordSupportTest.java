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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.Usage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArgiChatRecordSupportTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final TypeMapper types = new TypeMapper();

	private ObjectNode record(String type) {
		ObjectNode node = mapper.createObjectNode().put("@type", type);
		node.putArray("content");
		node.putArray("messages");
		node.putArray("toolCalls");
		node.putObject("metadata");
		return node;
	}

	private Object restore(ObjectNode node) throws IOException {
		return JacksonDeserializer.valueFromNode(node, mapper, types);
	}

	@Test
	void nullableFieldsAndUnknownUsageRoundTripWithoutInventingCounts() throws Exception {
		ChatResponse response = assertInstanceOf(ChatResponse.class, restore(record("ARGI_CHAT_RESPONSE")));
		assertNull(response.id());
		assertNull(response.model());
		assertNull(response.finishReason());
		assertEquals(Usage.unknown(), response.usage());
		assertEquals(new Usage(2L, null, Long.MAX_VALUE), restore(
				record("ARGI_USAGE").put("inputTokens", 2).putNull("outputTokens").put("totalTokens", Long.MAX_VALUE)));
		assertFalse(ArgiChatRecordSupport.supports("Other"));
		assertThrows(IOException.class, () -> ArgiChatRecordSupport.read("Other", record("Other"), mapper, types));
	}

	@Test
	void malformedRecordsFailInsteadOfDegradingToBusinessMaps() throws Exception {
		for (Map.Entry<String, String> invalid : Map
			.ofEntries(Map.entry("role", "4"), Map.entry("content", "{}"), Map.entry("toolCalls", "[4]"),
					Map.entry("metadata", "[]"), Map.entry("toolCallId", "4"))
			.entrySet()) {
			ObjectNode node = record("ARGI_CHAT_MESSAGE").put("role", "USER");
			node.set(invalid.getKey(), mapper.readTree(invalid.getValue()));
			assertThrows(IOException.class, () -> restore(node), invalid.getKey());
		}
		assertThrows(IOException.class, () -> restore(record("ARGI_CHAT_MESSAGE").put("role", "INVALID")));
		assertThrows(IOException.class, () -> restore(record("ARGI_CHAT_MESSAGE")));
		assertThrows(IOException.class, () -> restore(record("ARGI_CHAT_RESPONSE").put("finishReason", "INVALID")));
		ObjectNode invalidUsage = record("ARGI_CHAT_RESPONSE");
		invalidUsage.putObject("usage").put("inputTokens", 2);
		IOException error = assertThrows(IOException.class, () -> restore(invalidUsage));
		assertInstanceOf(ClassCastException.class, error.getCause());
		assertThrows(IOException.class,
				() -> restore(record("ARGI_CONTENT_PART").put("type", "IMAGE").put("data", "!invalid!")));
		assertThrows(IOException.class,
				() -> restore(record("ARGI_TOOL_CALL").put("id", "id").put("name", " ").put("arguments", "{}")));
		assertThrows(IOException.class, () -> restore(record("ARGI_CHAT_EVENT")));
		assertThrows(IOException.class, () -> restore(record("ARGI_CHAT_EVENT").put("type", "INVALID")));
		ObjectNode invalidEvent = record("ARGI_CHAT_EVENT").put("type", "RESPONSE");
		invalidEvent.putObject("response").put("text", "untyped");
		assertThrows(IOException.class, () -> restore(invalidEvent));
	}

	@Test
	void invalidTokenCountsAreNotTruncatedOrCoerced() throws Exception {
		for (String invalid : List.of("1.5", "\"3\"", "true", "9223372036854775808", "{}")) {
			ObjectNode node = record("ARGI_USAGE");
			node.set("inputTokens", mapper.readTree(invalid));
			assertThrows(IOException.class, () -> restore(node), invalid);
		}
	}

}
