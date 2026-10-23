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

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.Serializer;
import io.github.agentic.ai.graph.serializer.plain_text.PlainTextStateSerializer;
import io.github.agentic.ai.graph.state.AgentStateFactory;
import io.github.agentic.ai.graph.streaming.StreamingOutput;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Base Implementation of {@link PlainTextStateSerializer} using Jackson library. Need to
 * be extended from specific state implementation
 */
public abstract class JacksonStateSerializer extends PlainTextStateSerializer {

	private static final TypeReference<Map<String, Object>> STATE_TYPE = new TypeReference<>() {
	};

	protected final ObjectMapper objectMapper;

	protected TypeMapper typeMapper = new TypeMapper();

	protected JacksonStateSerializer(AgentStateFactory<OverAllState> stateFactory) {
		this(stateFactory, new ObjectMapper());
	}

	protected JacksonStateSerializer(AgentStateFactory<OverAllState> stateFactory, ObjectMapper objectMapper) {
		super(stateFactory);
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
		// Stable markers are required for final records held in untyped graph state.
		typeMapper.register(new TypeMapper.Reference<ChatMessage>("ARGI_CHAT_MESSAGE") {
		}).register(new TypeMapper.Reference<ChatEvent>("ARGI_CHAT_EVENT") {
		}).register(new TypeMapper.Reference<io.github.agentic.ai.api.chat.ChatResponse>("ARGI_CHAT_RESPONSE") {
		}).register(new TypeMapper.Reference<ContentPart>("ARGI_CONTENT_PART") {
		}).register(new TypeMapper.Reference<ToolCall>("ARGI_TOOL_CALL") {
		}).register(new TypeMapper.Reference<io.github.agentic.ai.api.chat.Usage>("ARGI_USAGE") {
		});
		this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

		this.objectMapper.registerModule(new Jdk8Module());
		this.objectMapper.registerModule(new JavaTimeModule());
		this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
				false);
		this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
				false);
		this.objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);

		objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		// objectMapper.registerModule(new ParameterNamesModule());
		objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

		var module = new SimpleModule();
		module.addDeserializer(Map.class, new GenericMapDeserializer(typeMapper));
		module.addDeserializer(List.class, new GenericListDeserializer(typeMapper));
		module.addSerializer(StreamingOutput.class, new StreamingOutputSerializer());
		module.addDeserializer(StreamingOutput.class, new StreamingOutputDeserializer(typeMapper, stateFactory));

		this.objectMapper.registerModule(module);

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private abstract static class NonNullUsageMixin {

	}

	public TypeMapper typeMapper() {
		return typeMapper;
	}

	public ObjectMapper objectMapper() {
		return objectMapper;
	}

	@Override
	public String contentType() {
		return "application/json";
	}

	@Override
	public final void writeData(Map<String, Object> data, ObjectOutput out) throws IOException {
		// Normalize state before serialization to convert non-serializable objects
		// (GraphResponse, CompletableFuture) into serializable structures
		Map<String, Object> normalized = normalizeForSerialization(data);
		String json = objectMapper.writeValueAsString(normalized);
		Serializer.writeUTF(json, out);
	}

	@Override
	public final Map<String, Object> readData(ObjectInput in) throws IOException, ClassNotFoundException {
		String json = Serializer.readUTF(in);
		return objectMapper.readValue(json, STATE_TYPE);
	}

	@Override
	public OverAllState cloneObject(OverAllState object) throws IOException, ClassNotFoundException {
		Objects.requireNonNull(object, "object cannot be null");
		// Keep the JSON deep-copy semantics without wrapping JSON in Java object streams.
		byte[] json = objectMapper.writeValueAsBytes(normalizeForSerialization(object.data()));
		return stateFactory().apply(objectMapper.readValue(json, STATE_TYPE));
	}

	/**
	 * Normalize state map before serialization. Converts non-serializable objects
	 * (GraphResponse, CompletableFuture) to Maps.
	 */
	private static Map<String, Object> normalizeForSerialization(Map<String, Object> state) {
		if (state == null || state.isEmpty()) {
			return state;
		}
		Map<String, Object> result = new LinkedHashMap<>(state.size());
		state.forEach((key, value) -> result.put(key, normalizeValue(value)));
		return hasTypeMarkerKey(state) ? mapEnvelope(state.getClass(), result) : result;
	}

	/**
	 * Normalize a single value for serialization. Normalizes neutral chat records and
	 * runtime snapshots, recursively checking containers.
	 */
	static Object normalizeValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof ChatEvent event) {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("type", event.type().name());
			fields.put("text", event.text());
			fields.put("toolCall", event.toolCall());
			fields.put("response", event.response());
			fields.put("usage", event.usage());
			fields.put("metadata", event.metadata());
			return normalizeChatRecord("ARGI_CHAT_EVENT", fields);
		}
		if (value instanceof ChatMessage message) {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("role", message.role().name());
			fields.put("content", message.content());
			fields.put("toolCalls", message.toolCalls());
			fields.put("toolCallId", message.toolCallId());
			fields.put("toolName", message.toolName());
			fields.put("metadata", message.metadata());
			return normalizeChatRecord("ARGI_CHAT_MESSAGE", fields);
		}
		if (value instanceof io.github.agentic.ai.api.chat.ChatResponse response) {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("id", response.id());
			fields.put("model", response.model());
			fields.put("messages", response.messages());
			fields.put("toolCalls", response.toolCalls());
			fields.put("finishReason", response.finishReason() == null ? null : response.finishReason().name());
			fields.put("usage", response.usage());
			fields.put("metadata", response.metadata());
			return normalizeChatRecord("ARGI_CHAT_RESPONSE", fields);
		}
		if (value instanceof ContentPart content) {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("type", content.type().name());
			fields.put("text", content.text());
			fields.put("mimeType", content.mimeType());
			fields.put("data",
					content.data() == null ? null : java.util.Base64.getEncoder().encodeToString(content.data()));
			fields.put("metadata", content.metadata());
			return normalizeChatRecord("ARGI_CONTENT_PART", fields);
		}
		if (value instanceof ToolCall call) {
			return normalizeChatRecord("ARGI_TOOL_CALL",
					Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments()));
		}
		if (value instanceof io.github.agentic.ai.api.chat.Usage usage) {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("inputTokens", usage.inputTokens());
			fields.put("outputTokens", usage.outputTokens());
			fields.put("totalTokens", usage.totalTokens());
			return normalizeChatRecord("ARGI_USAGE", fields);
		}
		if (value instanceof java.time.Duration duration) {
			return normalizeChatRecord("ARGI_DURATION", Map.of("value", duration.toString()));
		}

		// GraphResponse -> snapshot map (fully normalize internally)
		if (value instanceof GraphResponse) {
			return normalizeGraphResponse((GraphResponse<?>) value);
		}

		// 3. CompletableFuture → snapshot map (fully normalize internally)
		if (value instanceof CompletableFuture) {
			return normalizeCompletableFuture((CompletableFuture<?>) value);
		}

		// 4. Map → shallow scan for GraphResponse/ChatResponse/CompletableFuture
		if (value instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) value;
			Map<Object, Object> result = new LinkedHashMap<>(map.size());
			boolean changed = false;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				Object normalized = normalizeValue(entry.getValue());
				result.put(entry.getKey(), normalized);
				if (normalized != entry.getValue()) {
					changed = true;
				}
			}
			return hasTypeMarkerKey(map) ? mapEnvelope(map.getClass(), result) : changed ? result : value;
		}

		// 5. Collection → shallow scan for GraphResponse/ChatResponse/CompletableFuture
		if (value instanceof Collection) {
			Collection<?> collection = (Collection<?>) value;
			List<Object> result = new ArrayList<>(collection.size());
			boolean changed = false;
			for (Object item : collection) {
				Object normalized = normalizeValue(item);
				result.add(normalized);
				if (normalized != item) {
					changed = true;
				}
			}
			return changed ? result : value;
		}

		// 6. Array → shallow scan for GraphResponse/ChatResponse/CompletableFuture
		if (value.getClass().isArray()) {
			// Check if it's a primitive array (int[], double[], etc.)
			Class<?> componentType = value.getClass().getComponentType();
			if (componentType.isPrimitive()) {
				// Primitive arrays cannot contain
				// GraphResponse/ChatResponse/CompletableFuture
				// Return as-is, let Jackson handle the serialization
				return value;
			}

			// Object array - check for GraphResponse/ChatResponse/CompletableFuture
			Object[] array = (Object[]) value;
			Object[] result = new Object[array.length];
			boolean changed = false;
			for (int i = 0; i < array.length; i++) {
				Object normalized = normalizeValue(array[i]);
				result[i] = normalized;
				if (normalized != array[i]) {
					changed = true;
				}
			}
			// If nothing changed, return original array to preserve type
			return changed ? result : value;
		}

		// 7. Optional → unwrap and check
		if (value instanceof Optional) {
			Optional<?> opt = (Optional<?>) value;
			if (opt.isEmpty()) {
				return null;
			}
			Object unwrapped = opt.get();
			Object normalized = normalizeValue(unwrapped);
			return normalized == unwrapped ? value : normalized;
		}

		// 7. Other types → return as-is (assumed serializable)
		return value;
	}

	private static Map<String, Object> normalizeChatRecord(String type, Map<String, Object> fields) {
		Map<String, Object> result = normalizeForSerialization(fields);
		result.put(TypeMapper.TYPE_PROPERTY, type);
		return result;
	}

	/**
	 * Convert GraphResponse into serializable snapshot map with type marker.
	 */
	private static Map<String, Object> normalizeGraphResponse(GraphResponse<?> response) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("@type", "GraphResponse");
		snapshot.put("error", response.isError());

		// Extract result or error
		if (response.isError()) {
			// Error case
			snapshot.put("status", "error");
			if (response.getOutput() != null) {
				try {
					response.getOutput().getNow(null);
				}
				catch (Exception e) {
					Map<String, Object> errorMap = new LinkedHashMap<>();
					errorMap.put("message", e.getMessage());
					errorMap.put("class", e.getClass().getName());
					snapshot.put("errorDetails", errorMap);
				}
			}
		}
		else if (response.isDone()) {
			// Done case (output == null, value in resultValue)
			snapshot.put("status", "done");
			response.resultValue().ifPresent(result -> snapshot.put("result", deepNormalizeValue(result)));
		}
		else {
			// Pending or completed case (output != null and not error)
			// The output CompletableFuture contains the value
			if (response.getOutput() != null && response.getOutput().isDone()) {
				snapshot.put("status", "completed");
				try {
					Object result = response.getOutput().getNow(null);
					snapshot.put("result", deepNormalizeValue(result));
				}
				catch (Exception ignored) {
					// Should not happen as we checked isDone() and !isError()
				}
			}
			else {
				snapshot.put("status", "pending");
			}
		}

		snapshot.put("metadata", deepNormalizeValue(response.getAllMetadata()));
		return snapshot;
	}

	/**
	 * Deep normalize a value (for use inside
	 * GraphResponse/ChatResponse/CompletableFuture). Recursively normalizes all
	 * containers.
	 */
	private static Object deepNormalizeValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof ChatEvent || value instanceof ChatMessage || value instanceof ContentPart
				|| value instanceof ToolCall || value instanceof io.github.agentic.ai.api.chat.ChatResponse
				|| value instanceof io.github.agentic.ai.api.chat.Usage) {
			return normalizeValue(value);
		}
		if (value instanceof GraphResponse<?> response) {
			return normalizeGraphResponse(response);
		}
		if (value instanceof CompletableFuture<?> future) {
			return normalizeCompletableFuture(future);
		}
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> normalized = new LinkedHashMap<>(map.size());
			map.forEach((key, item) -> normalized.put(key, deepNormalizeValue(item)));
			return hasTypeMarkerKey(map) ? mapEnvelope(map.getClass(), normalized) : normalized;
		}
		if (value instanceof Collection<?> collection) {
			return collection.stream().map(JacksonStateSerializer::deepNormalizeValue).collect(Collectors.toList());
		}
		if (value.getClass().isArray()) {
			return value.getClass().getComponentType().isPrimitive() ? value
					: Arrays.stream((Object[]) value).map(JacksonStateSerializer::deepNormalizeValue).toArray();
		}
		if (value instanceof Optional<?> optional) {
			return optional.map(JacksonStateSerializer::deepNormalizeValue).orElse(null);
		}
		return value;
	}

	/**
	 * Convert CompletableFuture into serializable snapshot map with type marker.
	 */
	private static Map<String, Object> normalizeCompletableFuture(CompletableFuture<?> future) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("@type", "CompletableFuture");

		if (future.isDone()) {
			if (future.isCompletedExceptionally()) {
				snapshot.put("status", "failed");
				try {
					future.getNow(null);
				}
				catch (Exception e) {
					Map<String, Object> errorMap = new LinkedHashMap<>();
					errorMap.put("message", e.getMessage());
					errorMap.put("class", e.getClass().getName());
					snapshot.put("error", errorMap);
				}
			}
			else {
				snapshot.put("status", "completed");
				try {
					Object result = future.getNow(null);
					snapshot.put("result", deepNormalizeValue(result));
				}
				catch (Exception ignored) {
					snapshot.put("result", null);
				}
			}
		}
		else {
			snapshot.put("status", "pending");
		}

		return snapshot;
	}

	private static boolean hasTypeMarkerKey(Map<?, ?> map) {
		return map.containsKey("@class") || map.containsKey("@type") || map.containsKey("@typeHint");
	}

	private static Map<String, Object> mapEnvelope(Class<?> mapClass, Map<?, ?> map) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("@type", MapEnvelopeSupport.MAP_ENVELOPE_TYPE);
		envelope.put(MapEnvelopeSupport.MAP_CLASS_PROPERTY, mapClass.getName());
		Object[] entries = new Object[map.size()];
		int index = 0;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			entries[index++] = new Object[] { entry.getKey(), entry.getValue() };
		}
		envelope.put(MapEnvelopeSupport.MAP_ENTRIES_PROPERTY, entries);
		return envelope;
	}

}
