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
package io.github.agentic.ai.graph.agent.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict version-1 JSON codec. Wire validation precedes structured conversion: a parser
 * alone is not a schema validator. Decoding historical status data does not authorize it
 * for execution; use {@link PlanValidator#validateGenerated(PlanSpec)} for model output.
 */
public final class PlanCodec {

	static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

	private static final String JSON_SCHEMA = loadSchema();

	private static final Schema WIRE_SCHEMA = SCHEMAS.getSchema(MAPPER.readTree(JSON_SCHEMA));

	/** Return the authoritative, self-contained wire schema for fixtures and prompts. */
	public String jsonSchema() {
		return JSON_SCHEMA;
	}

	/**
	 * Encode schema-valid plan data without changing IDs, revision or execution status.
	 */
	public String encode(PlanSpec plan) {
		String json = MAPPER.writeValueAsString(plan);
		validateWire(MAPPER.readTree(json));
		return json;
	}

	/** Reject malformed JSON, unknown fields, unsupported versions and missing fields. */
	public PlanSpec decode(String json) {
		try {
			validateWire(MAPPER.readTree(json));
			return MAPPER.readValue(json, PlanSpec.class);
		}
		catch (PlanValidationException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new PlanValidationException("Invalid plan JSON", ex);
		}
	}

	private static void validateWire(JsonNode node) {
		if (node == null || !WIRE_SCHEMA.validate(node).isEmpty()) {
			throw new PlanValidationException("Plan does not match the version-1 wire schema");
		}
	}

	private static String loadSchema() {
		ClassPathResource resource = new ClassPathResource(
				"io/github/agentic/ai/graph/agent/planner/plan-v1.schema.json");
		try (var stream = resource.getInputStream()) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot load the plan wire schema", ex);
		}
	}

}
