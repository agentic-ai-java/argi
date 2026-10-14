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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeOutputUsageSerializationTest {

	private static ObjectMapper mapper() {
		SimpleModule module = new SimpleModule();
		module.addSerializer(NodeOutput.class, new JacksonNodeOutputSerializer());
		module.addDeserializer(NodeOutput.class, new JacksonNodeOutputDeserializer());
		module.addSerializer(StreamingOutput.class, new StreamingOutputSerializer());
		module.addDeserializer(StreamingOutput.class, new StreamingOutputDeserializer());
		return new ObjectMapper().registerModule(module);
	}

	@Test
	void preservesUnknownPartialAndLargeUsageInNodeOutput() throws Exception {
		for (Usage usage : List.of(Usage.unknown(), new Usage(1L, null, null),
				new Usage(3_000_000_000L, 5L, 3_000_000_005L))) {
			NodeOutput original = NodeOutput.of("model", "agent", null, usage);
			original.setSubGraph(true);
			NodeOutput restored = mapper().readValue(mapper().writeValueAsString(original), NodeOutput.class);
			assertEquals(usage, restored.tokenUsage());
			assertEquals("model", restored.node());
			assertEquals("agent", restored.agent());
			assertTrue(restored.isSubGraph());
		}
	}

	@Test
	void preservesStreamingUsageAndOutputTypeWithoutAMessage() throws Exception {
		Usage usage = new Usage(null, 3_000_000_000L, null);
		StreamingOutput<?> original = new StreamingOutput<>("model", "agent", null, usage,
				OutputType.AGENT_MODEL_FINISHED);
		original.setSubGraph(true);
		StreamingOutput<?> restored = mapper().readValue(mapper().writeValueAsString(original), StreamingOutput.class);
		assertEquals(usage, restored.tokenUsage());
		assertEquals(OutputType.AGENT_MODEL_FINISHED, restored.getOutputType());
		assertTrue(restored.isSubGraph());
		assertNull(restored.getOriginData());
	}

	@Test
	void readsAbsentUsageAsUnknown() throws Exception {
		String json = "{\"node\":\"model\",\"state\":null,\"messages\":[]}";
		assertEquals(Usage.unknown(), mapper().readValue(json, NodeOutput.class).tokenUsage());
		assertEquals(Usage.unknown(), mapper().readValue(json, StreamingOutput.class).tokenUsage());
		assertEquals(Usage.unknown(), NodeOutput.of("node", "agent", null, null).tokenUsage());
	}

	@Test
	void extractsNeutralResponseAndUsageEventsWithoutInventingCounts() {
		Usage usage = new Usage(8L, 2L, 10L);
		ChatResponse response = new ChatResponse("id", "model", List.of(ChatMessage.assistant("answer")), List.of(),
				FinishReason.STOP, usage, Map.of());
		assertEquals(usage, new StreamingOutput<>(response, "node", null).tokenUsage());
		assertEquals(usage, new StreamingOutput<>(ChatEvent.response(response), "node", null).tokenUsage());
		assertEquals(usage, new StreamingOutput<>(ChatEvent.usage(usage), "node", null).tokenUsage());
		assertEquals(usage, new StreamingOutput<>(usage, "node", null).tokenUsage());
		assertEquals(Usage.unknown(), new StreamingOutput<>(ChatEvent.textDelta("answer"), "node", null).tokenUsage());
	}

}
