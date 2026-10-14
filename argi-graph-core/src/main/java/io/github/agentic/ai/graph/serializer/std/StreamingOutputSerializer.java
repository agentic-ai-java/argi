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
package io.github.agentic.ai.graph.serializer.std;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.state.AgentStateFactory;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import io.github.agentic.ai.api.chat.ChatMessage;
import java.util.List;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Map;
import java.util.Objects;

public class StreamingOutputSerializer implements NullableObjectSerializer<StreamingOutput> {

	private final AgentStateFactory<OverAllState> stateFactory;

	public StreamingOutputSerializer() {
		this(OverAllState::new);
	}

	public StreamingOutputSerializer(AgentStateFactory<OverAllState> stateFactory) {
		this.stateFactory = Objects.requireNonNull(stateFactory, "stateFactory must not be null");
	}

	@Override
	public void write(StreamingOutput object, ObjectOutput out) throws IOException {
		// Write base NodeOutput fields
		writeNullableUTF(object.node(), out);
		writeNullableUTF(object.agent(), out);
		// Persist state data through the mapper; restore runtime services with the state
		// factory.
		writeNullableObject(object.state() == null ? null : object.state().data(), out);
		out.writeBoolean(object.isSubGraph());

		// Write StreamingOutput specific fields
		Object origin = object.getOriginData();
		writeNullableObject(ArgiChatSerializers.supports(origin) || origin instanceof String ? origin : null, out);
		out.writeObject(object.tokenUsage());

		out.writeObject(object.messages());

		// Write outputType if present
		if (object.getOutputType() != null) {
			out.writeByte(1);
			writeNullableUTF(object.getOutputType().name(), out);
		}
		else {
			out.writeByte(0);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public StreamingOutput read(ObjectInput in) throws IOException, ClassNotFoundException {
		// Read base NodeOutput fields
		String node = readNullableUTF(in).orElse(null);
		String agent = readNullableUTF(in).orElse(null);
		Map<String, Object> data = (Map<String, Object>) readNullableObject(in).orElse(null);
		OverAllState state = data == null ? null : stateFactory.apply(data);
		boolean subGraph = in.readBoolean();

		// Read StreamingOutput specific fields
		Object origin = readNullableObject(in).orElse(null);
		Usage usage = (Usage) in.readObject();

		Object restoredMessages = in.readObject();
		if (!(restoredMessages instanceof List<?> values)
				|| values.stream().anyMatch(value -> !(value instanceof ChatMessage))) {
			throw new IOException("Streaming messages must be ARGI ChatMessage values");
		}
		List<ChatMessage> messages = (List<ChatMessage>) restoredMessages;

		// Read outputType if present
		OutputType outputType = null;
		byte hasOutputType = in.readByte();
		if (hasOutputType == 1) {
			String outputTypeStr = readNullableUTF(in).orElse(null);
			if (outputTypeStr != null) {
				try {
					outputType = OutputType.valueOf(outputTypeStr);
				}
				catch (IllegalArgumentException e) {
					throw new IOException("Invalid streaming output type: " + outputTypeStr, e);
				}
			}
		}

		StreamingOutput<?> output = new StreamingOutput<>(messages, origin, node, agent, state, outputType);
		output.setTokenUsage(usage);
		output.setSubGraph(subGraph);
		return output;
	}

}
