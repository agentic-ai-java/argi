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

import java.util.Comparator;

import static java.util.Objects.requireNonNull;

/**
 * A version-pinned definition reference: {@code (kind, id, version)}.
 *
 * <p>
 * The three fields are data, not code. A reference never carries a class name, a
 * Supplier, a Future or any other handle to the admitting JVM instance.
 */
public record DefinitionReference(DefinitionKind kind, String id, String version) {

	/** Deterministic ordering used by registries and diagnostics. */
	public static final Comparator<DefinitionReference> ORDER = Comparator
		.comparingInt((DefinitionReference reference) -> reference.kind().ordinal())
		.thenComparing(DefinitionReference::id)
		.thenComparing(DefinitionReference::version);

	public DefinitionReference {
		requireNonNull(kind, "kind cannot be null");
		DefinitionValidation.text(id, "id");
		DefinitionValidation.text(version, "version");
	}

	public static DefinitionReference of(DefinitionKind kind, String id, String version) {
		return new DefinitionReference(kind, id, version);
	}

	/** Compact form used in messages and structured error details. */
	public String display() {
		return this.kind.wireName() + "[" + this.id + "@" + this.version + "]";
	}

	@Override
	public String toString() {
		return display();
	}

}
