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
package io.github.agentic.ai.graph.agent.result;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.agent.AgentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StructuredOutputFinalizer}: the component must never throw and
 * must map every output failure mode to {@link AgentResult.Status#VALIDATION_FAILED}
 * with a machine-readable issue, while a schema-less run completes without validation.
 */
@DisplayName("StructuredOutputFinalizer delivery boundary")
class StructuredOutputFinalizerTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "required": ["status"],
			  "additionalProperties": false,
			  "properties": {
			    "status": {"type": "string"}
			  }
			}""";

	private final StructuredOutputFinalizer finalizer = new StructuredOutputFinalizer();

	@Test
	@DisplayName("schema-valid output completes with parsed data")
	void validOutputCompletesWithData() {
		ChatMessage terminal = ChatMessage.assistant("{\"status\":\"ok\"}");

		AgentResult result = finalizer.finalizeOutput(terminal, SCHEMA, new Usage(2L, 3L, 5L));

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.data()).isNotNull();
		assertThat(result.message()).isSameAs(terminal);
		assertThat(result.usage().totalTokens()).isEqualTo(5L);
		assertThat(result.issues()).isEmpty();
	}

	@Test
	@DisplayName("non-JSON output fails validation with INVALID_JSON")
	void invalidJsonFailsValidation() {
		ChatMessage terminal = ChatMessage.assistant("I cannot answer in JSON, sorry.");

		AgentResult result = finalizer.finalizeOutput(terminal, SCHEMA, Usage.unknown());

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		assertThat(result.data()).isNull();
		assertThat(result.issues()).hasSize(1);
		assertThat(result.issues().get(0).errorCode()).isEqualTo(AgentResult.ERROR_CODE_INVALID_JSON);
		// The full output content is never echoed back as part of the issue.
		assertThat(result.issues().get(0).message()).doesNotContain("sorry");
	}

	@Test
	@DisplayName("schema violation reports the instance path of each offending location")
	void schemaViolationCarriesInstancePath() {
		ChatMessage terminal = ChatMessage.assistant("{\"status\": 42, \"extra\": true}");

		AgentResult result = finalizer.finalizeOutput(terminal, SCHEMA, Usage.unknown());

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		assertThat(result.issues()).isNotEmpty();
		assertThat(result.issues()).allMatch(issue -> AgentResult.ERROR_CODE_SCHEMA_VIOLATION.equals(issue.errorCode()));
		// networknt 3.x reports JSON instance paths in document-pointer style (/status).
		assertThat(result.issues()).anyMatch(issue -> "/status".equals(issue.fieldPath()));
	}

	@Test
	@DisplayName("no configured schema completes without validation")
	void missingSchemaCompletesWithoutValidation() {
		ChatMessage terminal = ChatMessage.assistant("plain text answer");

		AgentResult result = finalizer.finalizeOutput(terminal, null, Usage.unknown());

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.data()).isNull();
		assertThat(result.message()).isSameAs(terminal);
	}

	@Test
	@DisplayName("a malformed output schema fails validation instead of throwing")
	void malformedSchemaFailsValidation() {
		ChatMessage terminal = ChatMessage.assistant("{\"status\":\"ok\"}");

		AgentResult result = finalizer.finalizeOutput(terminal, "not a json schema at all {", Usage.unknown());

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		assertThat(result.issues()).hasSize(1);
		assertThat(result.issues().get(0).errorCode()).isEqualTo(AgentResult.ERROR_CODE_SCHEMA_MALFORMED);
	}

	@Test
	@DisplayName("trailing tokens after the JSON document are rejected")
	void trailingTokensFailValidation() {
		ChatMessage terminal = ChatMessage.assistant("{\"status\":\"ok\"} and some trailing prose");

		AgentResult result = finalizer.finalizeOutput(terminal, SCHEMA, Usage.unknown());

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		assertThat(result.issues().get(0).errorCode()).isEqualTo(AgentResult.ERROR_CODE_INVALID_JSON);
	}

}
