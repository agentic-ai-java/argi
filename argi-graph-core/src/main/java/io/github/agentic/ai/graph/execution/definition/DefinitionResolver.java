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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Application-owned lookup from a recorded {@link DefinitionReference} to the definition
 * code that reference pins.
 *
 * <p>
 * Resolution must be a pure function of the reference and the application's registered
 * definitions. It must not consult the admitting JVM instance, a Supplier, a Future, the
 * current "latest" version of the definition or any ambient context, because a recovering
 * worker has none of those. Implementations return an empty result for an unknown
 * reference instead of falling back to a different version.
 */
@FunctionalInterface
public interface DefinitionResolver {

	/**
	 * Resolve exactly the pinned reference, or return empty when this application has no
	 * such definition. Never substitute a different version.
	 */
	Optional<Object> resolve(DefinitionReference reference);

	default Optional<Object> resolve(DefinitionKind kind, String id, String version) {
		return resolve(DefinitionReference.of(kind, id, version));
	}

	/** Resolve or fail with a structured {@link DefinitionResolutionException}. */
	default Object resolveRequired(DefinitionReference reference) {
		DefinitionReference target = requireNonNull(reference, "reference cannot be null");
		return requireNonNull(resolve(target), "resolver result cannot be null")
			.orElseThrow(() -> DefinitionResolutionException.notRegistered(target));
	}

	/** Resolve, then require the registered definition to be assignable to {@code type}. */
	default <T> T resolveRequired(DefinitionReference reference, Class<T> type) {
		DefinitionReference target = requireNonNull(reference, "reference cannot be null");
		Class<T> expected = requireNonNull(type, "type cannot be null");
		Object resolved = resolveRequired(target);
		if (!expected.isInstance(resolved)) {
			throw DefinitionResolutionException.typeMismatch(target, expected);
		}
		return expected.cast(resolved);
	}

}
