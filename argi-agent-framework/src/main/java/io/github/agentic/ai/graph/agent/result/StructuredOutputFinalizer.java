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

import java.util.ArrayList;
import java.util.List;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.util.StringUtils;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.agent.AgentResult;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the terminal assistant message of a run into an {@link AgentResult} by parsing
 * and schema-validating the configured output schema. This is the single delivery
 * boundary shared by every termination path (normal final message, returnDirect
 * synthesis, hook {@code JumpTo.end}): the terminal message itself is carried through
 * unchanged and never regenerated.
 *
 * <p>
 * Prompt-level schema constraints do not count as validation: without this component
 * the output of a run is unverified text. When no output schema is configured, the
 * result is {@link AgentResult.Status#COMPLETED} with no data — configuring a schema
 * signals the validation intent, calling {@code callResult} signals the delivery
 * intent (double opt-in, so the legacy {@code call} behavior never changes).
 * </p>
 *
 * <p>
 * The component never throws: every failure mode (malformed JSON, malformed schema,
 * schema violations) becomes {@link AgentResult.Status#VALIDATION_FAILED} with issue
 * entries that do not echo the full output content.
 * </p>
 */
public final class StructuredOutputFinalizer {

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

	private static final String ROOT_PATH = "$";

	/**
	 * Finalizes the terminal message of a run against the configured output schema.
	 * @param terminal the terminal assistant message; must not be {@code null}
	 * @param outputSchema the configured JSON Schema for the output, or {@code null} or
	 * blank when the run has none
	 * @param usage the token usage recorded for the run, or {@code null} when unknown
	 * @return the delivery result; never {@code null} and never thrown over
	 */
	public AgentResult finalizeOutput(ChatMessage terminal, String outputSchema, Usage usage) {
		if (!StringUtils.hasLength(outputSchema)) {
			return AgentResult.completed(terminal, usage, null);
		}

		Schema schema;
		try {
			schema = SCHEMAS.getSchema(outputSchema);
		}
		catch (RuntimeException ex) {
			return AgentResult.validationFailed(terminal, usage,
					List.of(new AgentResult.OutputValidationIssue(AgentResult.ERROR_CODE_SCHEMA_MALFORMED,
							ROOT_PATH, "Configured output schema is not a valid JSON Schema")));
		}

		JsonNode root;
		try {
			root = MAPPER.readTree(terminal.text());
		}
		catch (RuntimeException ex) {
			return AgentResult.validationFailed(terminal, usage,
					List.of(new AgentResult.OutputValidationIssue(AgentResult.ERROR_CODE_INVALID_JSON, ROOT_PATH,
							"Terminal output is not valid JSON")));
		}

		List<Error> errors = schema.validate(root);
		if (!errors.isEmpty()) {
			List<AgentResult.OutputValidationIssue> issues = new ArrayList<>();
			for (Error error : errors) {
				issues.add(new AgentResult.OutputValidationIssue(AgentResult.ERROR_CODE_SCHEMA_VIOLATION,
						error.getInstanceLocation().toString(), error.getMessage()));
			}
			return AgentResult.validationFailed(terminal, usage, List.copyOf(issues));
		}

		return AgentResult.completed(terminal, usage, root);
	}

}
