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

import static java.util.Objects.requireNonNull;

/** Shared shape checks for version-pinned definition data. */
final class DefinitionValidation {

	private DefinitionValidation() {
	}

	static void text(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	static void referenceOfKind(DefinitionReference reference, DefinitionKind expected, String name) {
		requireNonNull(reference, name + " cannot be null");
		if (reference.kind() != expected) {
			throw new IllegalArgumentException(
					name + " must be a definition reference of kind " + expected.wireName() + ", was " + reference);
		}
	}

}
