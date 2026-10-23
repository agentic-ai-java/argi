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

import io.github.agentic.ai.graph.execution.run.RunSpec;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The version-pinned definitions a run was admitted with.
 *
 * <p>
 * The execution reference is derived from the run's own {@link RunSpec#definition()} by
 * {@link #forRun}, so the record can never disagree with the admitted run. The remaining
 * references name the tool catalog/schema, the data codec and the application definition
 * version. All four are data identifiers resolved at the application boundary, never
 * serialized code.
 *
 * <p>
 * Storing this record is opt-in. Legacy graph factories retain version-1 data, and
 * checkpoint formats are unchanged. A caller that never resolves definitions needs no
 * registry.
 */
public record RunDefinitionRecord(int schemaVersion, DefinitionReference definition, DefinitionReference toolCatalog,
		DefinitionReference codec, DefinitionReference application) {

	/** Envelope version of this record. Payload versions evolve separately. */
	public static final int CURRENT_SCHEMA_VERSION = 2;

	public RunDefinitionRecord {
		if (schemaVersion <= 0) {
			throw new IllegalArgumentException("schemaVersion must be positive");
		}
		requireNonNull(definition, "definition cannot be null");
		if (!definition.kind().executable() || (schemaVersion == 1 && definition.kind() != DefinitionKind.GRAPH)) {
			throw new IllegalArgumentException("Execution definition kind is not supported by this schema");
		}
		DefinitionValidation.referenceOfKind(toolCatalog, DefinitionKind.TOOL_CATALOG, "toolCatalog");
		DefinitionValidation.referenceOfKind(codec, DefinitionKind.CODEC, "codec");
		DefinitionValidation.referenceOfKind(application, DefinitionKind.APPLICATION, "application");
	}

	/**
	 * Build the record for an admitted run, preserving its schema and exact execution
	 * target.
	 */
	public static RunDefinitionRecord forRun(RunSpec run, DefinitionReference toolCatalog, DefinitionReference codec,
			DefinitionReference application) {
		RunSpec spec = requireNonNull(run, "run cannot be null");
		return new RunDefinitionRecord(spec.schemaVersion(), spec.definition(), toolCatalog, codec, application);
	}

	public static RunDefinitionRecord of(DefinitionReference definition, DefinitionReference toolCatalog,
			DefinitionReference codec, DefinitionReference application) {
		requireNonNull(definition, "definition cannot be null");
		return new RunDefinitionRecord(definition.kind() == DefinitionKind.GRAPH ? 1 : CURRENT_SCHEMA_VERSION,
				definition, toolCatalog, codec, application);
	}

	/** Legacy graph access must not reinterpret a different engine's target. */
	public DefinitionReference graph() {
		if (definition.kind() != DefinitionKind.GRAPH) {
			throw new IllegalStateException("Definition record does not target a graph");
		}
		return definition;
	}

	/** All four references, each with its recorded version. */
	public List<DefinitionReference> references() {
		return List.of(this.definition, this.toolCatalog, this.codec, this.application);
	}

	public DefinitionReference reference(DefinitionKind kind) {
		requireNonNull(kind, "kind cannot be null");
		return references().stream()
			.filter(reference -> reference.kind() == kind)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Definition kind is not recorded: " + kind.wireName()));
	}

}
