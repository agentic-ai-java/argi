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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import io.github.agentic.ai.graph.state.AgentStateFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.github.agentic.ai.api.chat.ChatMessage;
import java.util.List;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Custom deserializer for StreamingOutput. Supports deserialization of outputType and
 * message fields.
 */
public class StreamingOutputDeserializer extends StdDeserializer<StreamingOutput> {

	private final TypeMapper types;

	private final AgentStateFactory<OverAllState> stateFactory;

	public StreamingOutputDeserializer() {
		this(new TypeMapper(), OverAllState::new);
	}

	public StreamingOutputDeserializer(TypeMapper types, AgentStateFactory<OverAllState> stateFactory) {
		super(StreamingOutput.class);
		this.types = Objects.requireNonNull(types, "types must not be null");
		this.stateFactory = Objects.requireNonNull(stateFactory, "stateFactory must not be null");
	}

	@Override
	public StreamingOutput deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
			throws IOException {
		ObjectMapper objectMapper = (ObjectMapper) jsonParser.getCodec();
		JsonNode node = jsonParser.getCodec().readTree(jsonParser);
		if (!node.has("messages")) {
			throw new IOException("Legacy StreamingOutput checkpoints are not supported. Drain or export "
					+ "in-flight streaming records with the previous runtime before upgrading.");
		}

		String nodeName = node.has("node") && !node.get("node").isNull() ? node.get("node").asText() : null;
		String agentName = node.has("agent") && !node.get("agent").isNull() ? node.get("agent").asText() : null;
		OverAllState state = null;
		if (node.has("state") && !node.get("state").isNull()) {
			Object data = JacksonDeserializer.valueFromNode(node.get("state").get("data"), objectMapper, types);
			if (!(data instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
				throw new IOException("Streaming state data must be an object with string keys");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> stateData = (Map<String, Object>) map;
			state = stateFactory.apply(stateData);
		}
		Object origin = JacksonDeserializer.valueFromNode(node.get("originData"), objectMapper, types);

		Object restoredMessages = JacksonDeserializer.valueFromNode(node.get("messages"), objectMapper, types);
		if (!(restoredMessages instanceof List<?> values)
				|| values.stream().anyMatch(value -> !(value instanceof ChatMessage))) {
			throw new IOException("Streaming messages must be ARGI ChatMessage values");
		}
		@SuppressWarnings("unchecked")
		List<ChatMessage> messages = (List<ChatMessage>) restoredMessages;

		// Deserialize outputType if present
		OutputType outputType = null;
		if (node.has("outputType") && !node.get("outputType").isNull()) {
			String outputTypeStr = node.get("outputType").asText();
			try {
				outputType = OutputType.valueOf(outputTypeStr);
			}
			catch (IllegalArgumentException e) {
				throw new IOException("Invalid streaming output type: " + outputTypeStr, e);
			}
		}

		Usage usage = node.hasNonNull("tokenUsage")
				? objectMapper.readValue(objectMapper.treeAsTokens(node.get("tokenUsage")), Usage.class)
				: Usage.unknown();
		StreamingOutput<?> output = new StreamingOutput<>(messages, origin, nodeName, agentName, state, outputType);
		output.setTokenUsage(usage);
		output.setOutputType(outputType);
		output.setSubGraph(node.path("subGraph").asBoolean(false));
		return output;
	}

}
