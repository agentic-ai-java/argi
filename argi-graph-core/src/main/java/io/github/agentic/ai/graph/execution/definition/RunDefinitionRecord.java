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
 * The graph reference is derived from the run's own
 * {@link RunSpec#graphId()}/{@link RunSpec#graphVersion()} by {@link #forRun}, so the
 * record can never disagree with the admitted run. The remaining references name the tool
 * catalog/schema, the data codec and the application definition version. All four are
 * data identifiers resolved at the application boundary, never serialized code.
 *
 * <p>
 * Storing this record is opt-in: {@code RunSpec}, {@code RunStore} and checkpoint formats
 * are unchanged, and a caller that never records definitions keeps its current behaviour.
 */
public record RunDefinitionRecord(int schemaVersion, DefinitionReference graph, DefinitionReference toolCatalog,
		DefinitionReference codec, DefinitionReference application) {

	/** Envelope version of this record. Payload versions evolve separately. */
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public RunDefinitionRecord {
		if (schemaVersion <= 0) {
			throw new IllegalArgumentException("schemaVersion must be positive");
		}
		DefinitionValidation.referenceOfKind(graph, DefinitionKind.GRAPH, "graph");
		DefinitionValidation.referenceOfKind(toolCatalog, DefinitionKind.TOOL_CATALOG, "toolCatalog");
		DefinitionValidation.referenceOfKind(codec, DefinitionKind.CODEC, "codec");
		DefinitionValidation.referenceOfKind(application, DefinitionKind.APPLICATION, "application");
	}

	/**
	 * Build the record for an admitted run, taking the graph reference from the run itself.
	 */
	public static RunDefinitionRecord forRun(RunSpec run, DefinitionReference toolCatalog, DefinitionReference codec,
			DefinitionReference application) {
		RunSpec spec = requireNonNull(run, "run cannot be null");
		return new RunDefinitionRecord(CURRENT_SCHEMA_VERSION,
				DefinitionReference.of(DefinitionKind.GRAPH, spec.graphId(), spec.graphVersion()), toolCatalog, codec,
				application);
	}

	public static RunDefinitionRecord of(DefinitionReference graph, DefinitionReference toolCatalog,
			DefinitionReference codec, DefinitionReference application) {
		return new RunDefinitionRecord(CURRENT_SCHEMA_VERSION, graph, toolCatalog, codec, application);
	}

	/** All four references, each with its recorded version. */
	public List<DefinitionReference> references() {
		return List.of(this.graph, this.toolCatalog, this.codec, this.application);
	}

	public DefinitionReference reference(DefinitionKind kind) {
		requireNonNull(kind, "kind cannot be null");
		return switch (kind) {
			case GRAPH -> this.graph;
			case TOOL_CATALOG -> this.toolCatalog;
			case CODEC -> this.codec;
			case APPLICATION -> this.application;
		};
	}

}
