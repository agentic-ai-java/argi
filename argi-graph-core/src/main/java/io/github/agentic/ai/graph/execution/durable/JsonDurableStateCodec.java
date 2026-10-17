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
package io.github.agentic.ai.graph.execution.durable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.execution.run.RunPayload;

import java.util.List;
import java.util.Map;

/**
 * Data-only codec for graphs with JSON-native state (maps, lists, strings, booleans and
 * numbers). Register an application codec for typed messages or other domain values;
 * Java object serialization and polymorphic class metadata are never enabled here.
 */
public final class JsonDurableStateCodec implements DurableStateCodec {
	private static final String CHECKPOINT_TYPE = "argi.durable.checkpoint";
	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
		.build();

	@Override
	public Map<String, Object> decodeInput(RunPayload input) {
		if (input.schemaVersion() != 1) {
			throw new IllegalArgumentException("Unsupported JSON input schema");
		}
		try {
			return MAPPER.readValue(input.json(), new TypeReference<Map<String, Object>>() { });
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Invalid JSON input", ex);
		}
	}

	@Override
	public RunPayload encodeCheckpoint(Checkpoint checkpoint) {
		requireJson(checkpoint.getState());
		try {
			return new RunPayload(1, CHECKPOINT_TYPE, MAPPER.writeValueAsString(checkpoint));
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Cannot encode JSON checkpoint", ex);
		}
	}

	@Override
	public Checkpoint decodeCheckpoint(RunPayload payload) {
		if (payload.schemaVersion() != 1 || !CHECKPOINT_TYPE.equals(payload.type())) {
			throw new IllegalArgumentException("Unsupported JSON checkpoint envelope");
		}
		try {
			return MAPPER.readValue(payload.json(), Checkpoint.class);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Invalid JSON checkpoint", ex);
		}
	}

	private static void requireJson(Object value) {
		if (value == null || value instanceof String || value instanceof Boolean
				|| value instanceof Byte || value instanceof Short || value instanceof Integer
				|| value instanceof Long || value instanceof java.math.BigInteger
				|| value instanceof java.math.BigDecimal) {
			return;
		}
		if (value instanceof Double doubleValue && Double.isFinite(doubleValue)
				|| value instanceof Float floatValue && Float.isFinite(floatValue)) {
			return;
		}
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, child) -> {
				if (!(key instanceof String)) {
					throw new IllegalArgumentException("JSON state requires string map keys");
				}
				requireJson(child);
			});
			return;
		}
		if (value instanceof List<?> list) {
			list.forEach(JsonDurableStateCodec::requireJson);
			return;
		}
		throw new IllegalArgumentException("Checkpoint contains a non-JSON state value");
	}
}
