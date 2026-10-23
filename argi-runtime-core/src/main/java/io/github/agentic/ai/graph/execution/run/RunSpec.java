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

import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.TrustedExecutionContextReference;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionReference;

import static java.util.Objects.requireNonNull;

/**
 * Recoverable run admission data. Definition references select application-registered
 * code, never serialized executable code. Deadline and budget live in the versioned
 * context.
 */
public record RunSpec(int schemaVersion, RunNamespace namespace, String runId, String submissionKey, String inputHash,
		DefinitionReference definition, RunPayload input, TrustedExecutionContextReference context) {

	public static final int CURRENT_SCHEMA_VERSION = 2;

	public RunSpec {
		if (schemaVersion != 1 && schemaVersion != CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported run schema version: " + schemaVersion);
		}
		requireNonNull(namespace, "namespace cannot be null");
		RunValidation.text(runId, "runId");
		RunValidation.text(submissionKey, "submissionKey");
		requireNonNull(definition, "definition cannot be null");
		if (!definition.kind().executable() || (schemaVersion == 1 && definition.kind() != DefinitionKind.GRAPH)) {
			throw new IllegalArgumentException("Run definition kind is not supported by this schema");
		}
		requireNonNull(input, "input cannot be null");
		RunValidation.context(context);
		if (!input.inputHash().equals(inputHash)) {
			throw new IllegalArgumentException("inputHash does not match the input");
		}
		if (!namespace.tenant().equals(context.tenant()) || !runId.equals(context.runId())) {
			throw new IllegalArgumentException("run identity does not match the context");
		}
	}

	public static RunSpec from(TrustedExecutionContext context, String applicationId, String sessionId,
			String submissionKey, String graphId, String graphVersion, RunPayload input) {
		return new RunSpec(1, RunNamespace.from(context, applicationId, sessionId), context.runId(), submissionKey,
				input.inputHash(), graphId, graphVersion, input, context.toReference());
	}

	/** Legacy graph admission preserves the version-1 data contract. */
	public RunSpec(int schemaVersion, RunNamespace namespace, String runId, String submissionKey, String inputHash,
			String graphId, String graphVersion, RunPayload input, TrustedExecutionContextReference context) {
		this(legacyVersion(schemaVersion), namespace, runId, submissionKey, inputHash,
				DefinitionReference.of(DefinitionKind.GRAPH, graphId, graphVersion), input, context);
	}

	/**
	 * Admit an engine-neutral, version-pinned execution target using schema version 2.
	 */
	public static RunSpec from(TrustedExecutionContext context, String applicationId, String sessionId,
			String submissionKey, DefinitionReference definition, RunPayload input) {
		return new RunSpec(CURRENT_SCHEMA_VERSION, RunNamespace.from(context, applicationId, sessionId),
				context.runId(), submissionKey, input.inputHash(), definition, input, context.toReference());
	}

	/** Graph compatibility accessor; never mislabels an agent or workflow as a graph. */
	public String graphId() {
		requireGraph();
		return definition.id();
	}

	/** Graph compatibility accessor; new engines use {@link #definition()}. */
	public String graphVersion() {
		requireGraph();
		return definition.version();
	}

	private void requireGraph() {
		if (definition.kind() != DefinitionKind.GRAPH) {
			throw new IllegalStateException("Run targets " + definition.kind().wireName() + ", not a graph");
		}
	}

	private static int legacyVersion(int version) {
		RunValidation.version(version);
		return version;
	}

	public Long deadlineEpochMillis() {
		return context.deadlineEpochMillis();
	}

	public ExecutionBudget budget() {
		return new ExecutionBudget(context.budgetLimits());
	}
}
