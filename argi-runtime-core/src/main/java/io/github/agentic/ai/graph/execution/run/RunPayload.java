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

package io.github.agentic.ai.graph.execution.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * Versioned JSON data boundary, never a Java object serializer. Payload types are
 * application-owned schema identifiers, not class names. Applications must redact secrets
 * before admission. Identity references, code references and inputs contain no clients,
 * credentials, Futures or Suppliers. Nested object keys are canonicalized for hashing.
 */
public record RunPayload(int schemaVersion, String type, String json) {

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	public RunPayload {
		if (schemaVersion <= 0) {
			throw new IllegalArgumentException("payload schemaVersion must be positive");
		}
		RunValidation.text(type, "type");
		RunValidation.text(json, "json");
		try {
			JsonNode tree = MAPPER.readTree(json);
			if (!tree.isObject()) {
				throw new IllegalArgumentException("payload must be a JSON object");
			}
			json = MAPPER.writeValueAsString(canonical(tree));
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Invalid run payload JSON", ex);
		}
	}

	/** Hash the schema, type and canonical input, not a caller-supplied hash claim. */
	public String inputHash() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String encoded = schemaVersion + ":" + type.length() + ":" + type + ":" + json;
			return HexFormat.of().formatHex(digest.digest(encoded.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
		}
	}

	private static JsonNode canonical(JsonNode node) {
		if (node.isObject()) {
			ObjectNode result = MAPPER.createObjectNode();
			TreeSet<String> keys = new TreeSet<>();
			node.fieldNames().forEachRemaining(keys::add);
			keys.forEach(key -> result.set(key, canonical(node.get(key))));
			return result;
		}
		if (node.isArray()) {
			ArrayNode result = MAPPER.createArrayNode();
			node.forEach(child -> result.add(canonical(child)));
			return result;
		}
		return node;
	}
}
