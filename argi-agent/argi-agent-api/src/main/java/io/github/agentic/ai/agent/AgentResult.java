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
package io.github.agentic.ai.agent;

import java.util.List;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;

/**
 * Structured delivery contract for an agent run, produced by the opt-in
 * strict result-delivery entries. The existing Graph {@code call}/{@code
 * invoke}/{@code stream} APIs keep returning raw messages; this type is only produced
 * by the strict-delivery entries, so adoption is fully opt-in.
 *
 * <p>
 * {@link Status} describes the run-level delivery outcome. It is deliberately a
 * different vocabulary from T38's tool-level {@code ToolOutcome}: that vocabulary
 * classifies a single tool call, this one classifies the whole run's final result.
 * </p>
 *
 * <p>
 * An unfinished run is never reported as {@link Status#COMPLETED}: when no terminal
 * assistant message exists (for example after an interruption), the status is
 * {@link Status#INTERRUPTED} and {@link #message()} is {@code null}.
 * </p>
 *
 * <p>
 * {@link #usage()} is the total usage of the whole delivery: the accumulated model
 * turns plus any bounded structured-output repair re-ask, per-field, with a field
 * unknown on either side staying unknown instead of being summed as zero. When a
 * repair re-ask was issued, {@link #repairUsage()} carries the usage that single call
 * reported, so main-call and repair consumption stay distinguishable; an issued
 * repair whose consumption is unknown carries an all-unknown segment rather than a
 * fabricated zero.
 * </p>
 */
public record AgentResult(ChatMessage message, Status status, Usage usage, Usage repairUsage, Object data,
		List<OutputValidationIssue> issues) {

	/** Error code for terminal output that is not valid JSON at all. */
	public static final String ERROR_CODE_INVALID_JSON = "INVALID_JSON";

	/** Error code for a configured output schema that cannot be parsed as a JSON Schema. */
	public static final String ERROR_CODE_SCHEMA_MALFORMED = "SCHEMA_MALFORMED";

	/** Error code for parsed JSON that violates the configured output schema. */
	public static final String ERROR_CODE_SCHEMA_VIOLATION = "SCHEMA_VIOLATION";

	/** Error code for a run that failed before producing a terminal message. */
	public static final String ERROR_CODE_EXECUTION_FAILED = "EXECUTION_FAILED";

	/** Error code for a terminal message that only reports a model failure. */
	public static final String ERROR_CODE_MODEL_FAILED = "MODEL_FAILED";

	/** Error code marking that the single bounded repair attempt still failed validation. */
	public static final String ERROR_CODE_REPAIRED_STILL_INVALID = "REPAIRED_STILL_INVALID";

	/** Error code marking that the bounded repair re-ask could not be performed at all. */
	public static final String ERROR_CODE_REPAIR_CALL_FAILED = "REPAIR_CALL_FAILED";

	/**
	 * Delivery status of the run.
	 */
	public enum Status {

		/** The run finished with a terminal message; validated data when a schema was configured. */
		COMPLETED,

		/** The run finished, but the terminal output failed parsing or schema validation. */
		VALIDATION_FAILED,

		/** The run ended without a terminal message (for example after an interruption). */
		INTERRUPTED,

		/** The run failed with an execution or model error. */
		ERROR

	}

	/**
	 * A single output validation or delivery failure, in the style of the T31 argument
	 * validation failure protocol.
	 * @param errorCode machine-readable code (see the {@code ERROR_CODE_*} constants)
	 * @param fieldPath location the issue refers to (JSON instance path when available)
	 * @param message human-readable description; never echoes the full output content
	 */
	public record OutputValidationIssue(String errorCode, String fieldPath, String message) {

	}

	public AgentResult {
		issues = issues == null ? List.of() : List.copyOf(issues);
	}

	/**
	 * Compatibility shape without the repair usage segment, equivalent to a delivery
	 * where no repair re-ask was issued.
	 */
	public AgentResult(ChatMessage message, Status status, Usage usage, Object data,
			List<OutputValidationIssue> issues) {
		this(message, status, usage, null, data, issues);
	}

	public static AgentResult completed(ChatMessage message, Usage usage, Object data) {
		return new AgentResult(message, Status.COMPLETED, usage, null, data, List.of());
	}

	/** Completion whose total includes an issued repair re-ask, with its reported segment. */
	public static AgentResult completed(ChatMessage message, Usage usage, Usage repairUsage, Object data) {
		return new AgentResult(message, Status.COMPLETED, usage, repairUsage, data, List.of());
	}

	public static AgentResult validationFailed(ChatMessage message, Usage usage, List<OutputValidationIssue> issues) {
		return new AgentResult(message, Status.VALIDATION_FAILED, usage, null, null, issues);
	}

	/** Validation failure whose total includes an issued repair re-ask, with its reported segment. */
	public static AgentResult validationFailed(ChatMessage message, Usage usage, Usage repairUsage,
			List<OutputValidationIssue> issues) {
		return new AgentResult(message, Status.VALIDATION_FAILED, usage, repairUsage, null, issues);
	}

	public static AgentResult interrupted(Usage usage) {
		return new AgentResult(null, Status.INTERRUPTED, usage, null, null, List.of());
	}

	public static AgentResult error(ChatMessage message, Usage usage, List<OutputValidationIssue> issues) {
		return new AgentResult(message, Status.ERROR, usage, null, null, issues);
	}

}
