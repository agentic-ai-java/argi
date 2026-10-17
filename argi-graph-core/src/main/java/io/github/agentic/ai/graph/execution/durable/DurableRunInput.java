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
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.run.RunPayload;

import java.util.Objects;

/** Additive input envelope: submission atomically pins definitions with original input. */
record DurableRunInput(RunDefinitionRecord definitions, RunPayload input) {
	private static final String TYPE = "argi.durable.input";
	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	DurableRunInput {
		Objects.requireNonNull(definitions, "definitions cannot be null");
		Objects.requireNonNull(input, "input cannot be null");
	}

	RunPayload encode() {
		try {
			return new RunPayload(1, TYPE, MAPPER.writeValueAsString(this));
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Cannot encode durable input", ex);
		}
	}

	static DurableRunInput decode(RunPayload payload) {
		if (payload.schemaVersion() != 1 || !TYPE.equals(payload.type())) {
			throw new IllegalArgumentException("Unsupported durable input envelope");
		}
		try {
			return MAPPER.readValue(payload.json(), DurableRunInput.class);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Invalid durable input envelope", ex);
		}
	}
}
