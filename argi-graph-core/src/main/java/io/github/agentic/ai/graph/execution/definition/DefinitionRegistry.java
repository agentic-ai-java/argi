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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

/**
 * Immutable in-process {@link DefinitionResolver} that holds every retained definition
 * version.
 *
 * <p>
 * An application registers each version it wants to stay recoverable, for example
 * {@code graph[checkout@1]} and {@code graph[checkout@2]}. Registering the same reference
 * twice is a programming error, so an accidental overwrite can never silently change what
 * an existing run resolves. The registry is one possible resolver; applications that
 * already have a catalog can implement {@link DefinitionResolver} directly and do not
 * need this type.
 *
 * <p>
 * Retention is an application concern: a version may only be removed once no
 * non-terminal run still pins it.
 */
public final class DefinitionRegistry implements DefinitionResolver {

	private final Map<DefinitionReference, Object> definitions;

	private final Set<DefinitionReference> references;

	private DefinitionRegistry(Map<DefinitionReference, Object> definitions) {
		this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
		Set<DefinitionReference> ordered = new TreeSet<>(DefinitionReference.ORDER);
		ordered.addAll(definitions.keySet());
		this.references = Collections.unmodifiableSet(ordered);
	}

	public static DefinitionRegistry empty() {
		return new DefinitionRegistry(Map.of());
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Optional<Object> resolve(DefinitionReference reference) {
		return Optional.ofNullable(this.definitions.get(requireNonNull(reference, "reference cannot be null")));
	}

	public boolean contains(DefinitionReference reference) {
		return this.definitions.containsKey(requireNonNull(reference, "reference cannot be null"));
	}

	/** Every retained reference, in deterministic order, for retention diagnostics. */
	public Set<DefinitionReference> references() {
		return this.references;
	}

	public int size() {
		return this.definitions.size();
	}

	public static final class Builder {

		private final Map<DefinitionReference, Object> definitions = new LinkedHashMap<>();

		private Builder() {
		}

		/** Register a definition for a pinned reference; duplicate references are rejected. */
		public Builder register(DefinitionReference reference, Object definition) {
			DefinitionReference target = requireNonNull(reference, "reference cannot be null");
			Object value = requireNonNull(definition, "definition cannot be null");
			if (this.definitions.putIfAbsent(target, value) != null) {
				throw new IllegalArgumentException("Definition already registered: " + target.display());
			}
			return this;
		}

		public DefinitionRegistry build() {
			return new DefinitionRegistry(this.definitions);
		}

	}

}
