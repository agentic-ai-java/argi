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
package io.github.agentic.ai.graph.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Immutable Jackson codec for runtime JSON values, separate from checkpoint
 * serialization.
 */
public final class JsonCodec {

	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

	public <T> T fromJson(String json, Class<T> type) {
		try {
			return MAPPER.readValue(json, type);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid JSON for " + type.getTypeName(), exception);
		}
	}

	public Map<String, Object> fromJsonToMap(String json) {
		try {
			return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid JSON object", exception);
		}
	}

	public String toJson(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Cannot serialize JSON value", exception);
		}
	}

}
