/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.tool.patch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Declares the state keys a single tool may write through its context patch, and the
 * value types those keys accept.
 *
 * <p>
 * A spec without declared writable keys allows every non-reserved key (see
 * {@link ToolContextPatchValidator#isReservedKey(String)}); declaring any writable key
 * restricts the tool to exactly that set. Type constraints are independent of the
 * writable set: a value written to a key with a declared type must be assignable to it
 * (subclasses are accepted).
 * </p>
 *
 * <p>
 * Specs are attached per tool name through a {@link ToolContextPatchPolicy}, which is
 * opt-in on the agent builder. Without a policy the historical merge behavior is
 * unchanged.
 * </p>
 *
 * @see ToolContextPatchPolicy
 * @see ToolContextPatchValidator
 */
public final class ToolContextPatchSpec {

	private final Set<String> writableKeys;

	private final Map<String, Class<?>> keyTypes;

	private ToolContextPatchSpec(Builder builder) {
		this.writableKeys = Collections.unmodifiableSet(new HashSet<>(builder.writableKeys));
		this.keyTypes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.keyTypes));
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Whether the tool may write the given key at all. Reserved keys are never writable,
	 * regardless of this answer; the validator enforces that separately.
	 * @param key the state key the tool attempts to write
	 * @return {@code true} when the key is allowed (or no writable set was declared)
	 */
	public boolean isWritable(String key) {
		return writableKeys.isEmpty() || writableKeys.contains(key);
	}

	/**
	 * The declared value type for the given key.
	 * @param key the state key the tool attempts to write
	 * @return the declared type, or empty when the key's values are unconstrained
	 */
	public Optional<Class<?>> declaredType(String key) {
		return Optional.ofNullable(keyTypes.get(key));
	}

	@Override
	public String toString() {
		return "ToolContextPatchSpec[writableKeys=" + writableKeys + ", keyTypes=" + keyTypes.keySet() + "]";
	}

	public static final class Builder {

		private final Set<String> writableKeys = new HashSet<>();

		private final Map<String, Class<?>> keyTypes = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * Declares one writable key. Declaring any writable key restricts the tool to the
		 * declared set; the reserved namespace is excluded either way.
		 * @param key the state key this tool may write
		 * @return this builder
		 */
		public Builder writableKey(String key) {
			if (key == null || key.isBlank()) {
				throw new IllegalArgumentException("writable key must not be blank");
			}
			this.writableKeys.add(key);
			return this;
		}

		/**
		 * Declares the value type accepted for a key. Values written to the key must be
		 * assignable to the declared type; subclasses are accepted.
		 * @param key the state key the constraint applies to
		 * @param type the accepted value type
		 * @return this builder
		 */
		public Builder keyType(String key, Class<?> type) {
			if (key == null || key.isBlank()) {
				throw new IllegalArgumentException("key must not be blank");
			}
			this.keyTypes.put(key, Objects.requireNonNull(type, "type must not be null"));
			return this;
		}

		public ToolContextPatchSpec build() {
			return new ToolContextPatchSpec(this);
		}

	}

}
