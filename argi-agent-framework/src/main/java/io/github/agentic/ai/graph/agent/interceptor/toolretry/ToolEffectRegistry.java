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
package io.github.agentic.ai.graph.agent.interceptor.toolretry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.github.agentic.ai.api.tool.Tool;

/**
 * Immutable lookup from tool name to declared {@link ToolEffect}. Tools without a
 * declaration resolve to {@link ToolEffect#UNSAFE}.
 *
 * Declarations come from the application ({@link #builder()}) or from tool callbacks that
 * implement {@link ToolEffectAware} ({@link #fromTools(Iterable)}); they are never
 * derived from tool call arguments.
 *
 * Example: ToolEffectRegistry effects = ToolEffectRegistry.builder() .readOnly("search",
 * "get_order") .idempotent("upsert_customer") .reconcilable("create_payment") .build();
 */
public final class ToolEffectRegistry {

	private static final ToolEffectRegistry EMPTY = new ToolEffectRegistry(Collections.emptyMap());

	private final Map<String, ToolEffect> effects;

	private ToolEffectRegistry(Map<String, ToolEffect> effects) {
		this.effects = Collections.unmodifiableMap(new HashMap<>(effects));
	}

	/**
	 * A registry without any declaration: every tool is {@link ToolEffect#UNSAFE}.
	 * @return the empty registry
	 */
	public static ToolEffectRegistry empty() {
		return EMPTY;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Collect the declarations of the given tool callbacks. Callbacks that do not
	 * implement {@link ToolEffectAware} are left undeclared and therefore resolve to
	 * {@link ToolEffect#UNSAFE}.
	 * @param tools the registered tool callbacks
	 * @return a registry with one entry per effect-aware callback
	 */
	public static ToolEffectRegistry fromTools(Iterable<? extends Tool> tools) {
		Builder builder = builder();
		for (Tool tool : tools) {
			if (tool instanceof ToolEffectAware aware) {
				builder.declare(tool.definition().name(), aware.toolEffect());
			}
		}
		return builder.build();
	}

	/**
	 * Resolve the effect of a tool.
	 * @param toolName the tool name
	 * @return the declared effect, or {@link ToolEffect#UNSAFE} when nothing was declared
	 */
	public ToolEffect effectOf(String toolName) {
		return declaredEffectOf(toolName).orElse(ToolEffect.UNSAFE);
	}

	/**
	 * The explicit declaration for a tool, if any.
	 * @param toolName the tool name
	 * @return the declared effect, or empty when the tool is undeclared
	 */
	public Optional<ToolEffect> declaredEffectOf(String toolName) {
		return Optional.ofNullable(this.effects.get(toolName));
	}

	/**
	 * Merge another registry into this one. Declarations of {@code other} win on
	 * conflict.
	 * @param other the registry whose declarations take precedence
	 * @return a new registry containing both sets of declarations
	 */
	public ToolEffectRegistry merge(ToolEffectRegistry other) {
		Map<String, ToolEffect> merged = new HashMap<>(this.effects);
		merged.putAll(other.effects);
		return new ToolEffectRegistry(merged);
	}

	public static final class Builder {

		private final Map<String, ToolEffect> effects = new HashMap<>();

		private Builder() {
		}

		public Builder declare(String toolName, ToolEffect effect) {
			if (toolName == null || toolName.isBlank()) {
				throw new IllegalArgumentException("toolName must not be blank");
			}
			if (effect == null) {
				throw new IllegalArgumentException("effect must not be null");
			}
			this.effects.put(toolName, effect);
			return this;
		}

		public Builder readOnly(String... toolNames) {
			return declareAll(ToolEffect.READ_ONLY, toolNames);
		}

		public Builder idempotent(String... toolNames) {
			return declareAll(ToolEffect.IDEMPOTENT, toolNames);
		}

		public Builder reconcilable(String... toolNames) {
			return declareAll(ToolEffect.RECONCILABLE, toolNames);
		}

		public Builder unsafe(String... toolNames) {
			return declareAll(ToolEffect.UNSAFE, toolNames);
		}

		private Builder declareAll(ToolEffect effect, String... toolNames) {
			for (String toolName : toolNames) {
				declare(toolName, effect);
			}
			return this;
		}

		public ToolEffectRegistry build() {
			return new ToolEffectRegistry(this.effects);
		}

	}

}
