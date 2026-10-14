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

import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.WritableTypeId;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * Persists streaming output snapshots, including neutral chat origin data.
 * Provider-native runtime objects remain transient.
 */
public class StreamingOutputSerializer extends StdSerializer<StreamingOutput> {

	public StreamingOutputSerializer() {
		super(StreamingOutput.class);
	}

	@Override
	public void serialize(StreamingOutput value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		gen.writeStartObject();
		serializeFields(value, gen, provider);
		gen.writeEndObject();
	}

	@Override
	public void serializeWithType(StreamingOutput value, JsonGenerator gen, SerializerProvider provider,
			TypeSerializer typeSer) throws IOException {
		WritableTypeId typeIdDef = typeSer.writeTypePrefix(gen, typeSer.typeId(value, JsonToken.START_OBJECT));
		serializeFields(value, gen, provider);
		typeSer.writeTypeSuffix(gen, typeIdDef);
	}

	private void serializeFields(StreamingOutput value, JsonGenerator gen, SerializerProvider provider)
			throws IOException {
		gen.writeStringField(TypeMapper.TYPE_PROPERTY, "ARGI_STREAMING_OUTPUT");
		gen.writeStringField("node", value.node());
		gen.writeStringField("agent", value.agent());
		gen.writeObjectField("tokenUsage", value.tokenUsage());
		gen.writeObjectField("state", value.state() == null ? null
				: java.util.Map.of("data", JacksonStateSerializer.normalizeValue(value.state().data())));
		gen.writeBooleanField("subGraph", value.isSubGraph());

		// Serialize message if present
		gen.writeObjectField("messages", JacksonStateSerializer.normalizeValue(value.messages()));

		// Serialize outputType if present
		OutputType outputType = value.getOutputType();
		if (outputType != null) {
			gen.writeStringField("outputType", outputType.name());
		}

		Object origin = value.getOriginData();
		// Native model responses and live resources are not portable checkpoint payloads.
		if (origin instanceof io.github.agentic.ai.api.chat.ChatEvent
				|| origin instanceof io.github.agentic.ai.api.chat.ChatMessage
				|| origin instanceof io.github.agentic.ai.api.chat.ChatResponse
				|| origin instanceof io.github.agentic.ai.api.chat.ContentPart
				|| origin instanceof io.github.agentic.ai.api.chat.ToolCall
				|| origin instanceof io.github.agentic.ai.api.chat.Usage || origin instanceof String) {
			gen.writeObjectField("originData", JacksonStateSerializer.normalizeValue(origin));
		}

	}

}
