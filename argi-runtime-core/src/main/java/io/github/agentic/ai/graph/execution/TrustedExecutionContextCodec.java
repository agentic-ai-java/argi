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
package io.github.agentic.ai.graph.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Strict JSON codec for {@link TrustedExecutionContextReference}. */
public final class TrustedExecutionContextCodec {

	private static final ObjectMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	public String encode(TrustedExecutionContextReference reference) {
		requireCurrentVersion(reference);
		try {
			return MAPPER.writeValueAsString(reference);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Cannot encode trusted execution context reference", ex);
		}
	}

	public TrustedExecutionContextReference decode(String encoded) {
		if (encoded == null || encoded.isBlank()) {
			throw new IllegalArgumentException("encoded reference must not be blank");
		}
		try {
			TrustedExecutionContextReference reference = MAPPER.readValue(encoded,
					TrustedExecutionContextReference.class);
			requireCurrentVersion(reference);
			return reference;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Invalid trusted execution context reference", ex);
		}
	}

	private static void requireCurrentVersion(TrustedExecutionContextReference reference) {
		if (reference == null) {
			throw new IllegalArgumentException("reference cannot be null");
		}
		if (reference.schemaVersion() != TrustedExecutionContextReference.CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported trusted execution context schema version: "
					+ reference.schemaVersion());
		}
	}
}
