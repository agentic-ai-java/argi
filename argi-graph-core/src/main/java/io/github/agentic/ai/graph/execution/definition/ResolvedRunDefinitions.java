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

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * The recorded definitions resolved to the exact versions they were pinned to.
 *
 * <p>
 * A worker obtains this value from {@link RunDefinitionAdmission#resolve} before it
 * dispatches anything. Every kind is present, so a caller can never observe a partially
 * resolved set or silently fall back to a newer registration.
 */
public record ResolvedRunDefinitions(RunDefinitionRecord record, Map<DefinitionKind, Object> definitions) {

	public ResolvedRunDefinitions {
		requireNonNull(record, "record cannot be null");
		EnumMap<DefinitionKind, Object> resolved = new EnumMap<>(DefinitionKind.class);
		for (Map.Entry<DefinitionKind, Object> entry : requireNonNull(definitions, "definitions cannot be null")
			.entrySet()) {
			DefinitionKind kind = requireNonNull(entry.getKey(), "definition kind cannot be null");
			resolved.put(kind, requireNonNull(entry.getValue(), "definition cannot be null"));
		}
		for (DefinitionKind kind : DefinitionKind.values()) {
			if (!resolved.containsKey(kind)) {
				throw new IllegalArgumentException("Missing resolved definition: " + kind.wireName());
			}
		}
		definitions = Collections.unmodifiableMap(resolved);
	}

	public Object definition(DefinitionKind kind) {
		DefinitionKind target = requireNonNull(kind, "kind cannot be null");
		Object definition = this.definitions.get(target);
		if (definition == null) {
			throw DefinitionResolutionException.notRegistered(this.record.reference(target));
		}
		return definition;
	}

	/** Typed access with a structured {@link DefinitionFailureCode#TYPE_MISMATCH} failure. */
	public <T> T definition(DefinitionKind kind, Class<T> type) {
		DefinitionKind target = requireNonNull(kind, "kind cannot be null");
		Class<T> expected = requireNonNull(type, "type cannot be null");
		Object definition = definition(target);
		if (!expected.isInstance(definition)) {
			throw DefinitionResolutionException.typeMismatch(this.record.reference(target), expected);
		}
		return expected.cast(definition);
	}

	public Object graph() {
		return definition(DefinitionKind.GRAPH);
	}

	public Object toolCatalog() {
		return definition(DefinitionKind.TOOL_CATALOG);
	}

	public Object codec() {
		return definition(DefinitionKind.CODEC);
	}

	public Object application() {
		return definition(DefinitionKind.APPLICATION);
	}

	/** The pinned graph reference, for audit and for comparing against the run spec. */
	public DefinitionReference graphReference() {
		return this.record.graph();
	}

	/** The pinned data codec reference this worker verified. */
	public DefinitionReference codecReference() {
		return this.record.codec();
	}

}
