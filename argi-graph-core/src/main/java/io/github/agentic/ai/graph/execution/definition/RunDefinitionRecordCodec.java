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

package io.github.agentic.ai.graph.execution.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Strict data-only codec for {@link RunDefinitionRecord}, with no default typing, Java
 * serialization or identity trust.
 *
 * <p>
 * The writer that produced the data and the reader that consumes it are independent
 * runtime builds. {@link #decode} therefore reads the envelope version from the raw JSON
 * before binding, and reports an unreadable envelope as a structured
 * {@link CodecIncompatibilityException} rather than a parse error.
 */
public final class RunDefinitionRecordCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
		.build();

	private static final String SCHEMA_VERSION_FIELD = "schemaVersion";

	public String encode(RunDefinitionRecord record) {
		if (record == null) {
			throw new IllegalArgumentException("record cannot be null");
		}
		try {
			return MAPPER.writeValueAsString(record);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Cannot encode run definition record", ex);
		}
	}

	/**
	 * Bind a record whose envelope version this runtime can read; a newer envelope fails
	 * with {@link CodecIncompatibilityException}.
	 */
	public RunDefinitionRecord decode(String json) {
		int recorded = readSchemaVersion(json);
		if (recorded != RunDefinitionRecord.CURRENT_SCHEMA_VERSION) {
			throw CodecIncompatibilityException.envelopeVersion(recorded,
					RunDefinitionRecord.CURRENT_SCHEMA_VERSION);
		}
		try {
			JsonNode tree = MAPPER.readValue(json, JsonNode.class);
			return MAPPER.treeToValue(tree, RunDefinitionRecord.class);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Invalid run definition record", ex);
		}
	}

	/**
	 * Read only the envelope version, so a reader can reject newer data before binding any
	 * field. This is the check that makes an unreadable envelope explicit.
	 */
	public int readSchemaVersion(String json) {
		DefinitionValidation.text(json, "json");
		try {
			JsonNode tree = MAPPER.readValue(json, JsonNode.class);
			if (tree == null || !tree.isObject()) {
				throw new IllegalArgumentException("Run definition record must be a JSON object");
			}
			JsonNode version = tree.get(SCHEMA_VERSION_FIELD);
			if (version == null || !version.isIntegralNumber()) {
				throw new IllegalArgumentException(
						"Run definition record requires an integer " + SCHEMA_VERSION_FIELD);
			}
			return version.intValue();
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Invalid run definition record", ex);
		}
	}

}
