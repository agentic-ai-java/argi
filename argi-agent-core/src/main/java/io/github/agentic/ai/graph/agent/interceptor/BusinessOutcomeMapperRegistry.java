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
package io.github.agentic.ai.graph.agent.interceptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolResult;

/**
 * Immutable lookup from tool name to the {@link BusinessOutcomeMapper} registered for
 * that tool. Tools without a registration are passed through untouched: their results
 * are never inspected for business failure signals.
 *
 * <p>
 * Registrations come from the application ({@link #builder()}) or from tools that
 * implement {@link BusinessOutcomeAware} ({@link #fromTools(Iterable)}).
 * </p>
 */
public final class BusinessOutcomeMapperRegistry {

	private static final BusinessOutcomeMapperRegistry EMPTY = new BusinessOutcomeMapperRegistry(
			Collections.emptyMap());

	private final Map<String, BusinessOutcomeMapper> mappers;

	private BusinessOutcomeMapperRegistry(Map<String, BusinessOutcomeMapper> mappers) {
		this.mappers = Collections.unmodifiableMap(new HashMap<>(mappers));
	}

	/**
	 * A registry without any registration: every result is passed through unchanged.
	 * @return the empty registry
	 */
	public static BusinessOutcomeMapperRegistry empty() {
		return EMPTY;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Collect the mappers of the given tools. Tools that do not implement
	 * {@link BusinessOutcomeAware} are skipped.
	 * @param tools the registered tools
	 * @return a registry with one entry per outcome-aware tool
	 */
	public static BusinessOutcomeMapperRegistry fromTools(Iterable<? extends Tool> tools) {
		Builder builder = builder();
		for (Tool tool : tools) {
			if (tool instanceof BusinessOutcomeAware aware) {
				builder.declare(tool.definition().name(), aware.outcomeMapper());
			}
		}
		return builder.build();
	}

	/**
	 * The mapper registered for a tool, if any.
	 * @param toolName the tool name
	 * @return the registered mapper, or empty when none was registered
	 */
	public boolean hasMapper(String toolName) {
		return this.mappers.containsKey(toolName);
	}

	/**
	 * Applies the mapper registered for the call's tool, or returns the result
	 * unchanged when nothing is registered for it.
	 * @param call the tool call being finalized
	 * @param result the raw result produced by the tool
	 * @return the mapped outcome, or the original result when no mapper is registered
	 */
	public ToolResult mapOutcome(ToolCall call, ToolResult result) {
		BusinessOutcomeMapper mapper = this.mappers.get(call.name());
		if (mapper == null) {
			return result;
		}
		ToolResult mapped = mapper.mapOutcome(call, result);
		if (mapped == null) {
			throw new IllegalStateException(
					"BusinessOutcomeMapper for tool '" + call.name() + "' returned null");
		}
		return mapped;
	}

	/**
	 * Merge another registry into this one. Registrations of {@code other} win on
	 * conflict.
	 * @param other the registry whose registrations take precedence
	 * @return a new registry containing both sets of registrations
	 */
	public BusinessOutcomeMapperRegistry merge(BusinessOutcomeMapperRegistry other) {
		Map<String, BusinessOutcomeMapper> merged = new HashMap<>(this.mappers);
		merged.putAll(other.mappers);
		return new BusinessOutcomeMapperRegistry(merged);
	}

	public static final class Builder {

		private final Map<String, BusinessOutcomeMapper> mappers = new HashMap<>();

		private Builder() {
		}

		public Builder declare(String toolName, BusinessOutcomeMapper mapper) {
			if (toolName == null || toolName.isBlank()) {
				throw new IllegalArgumentException("toolName must not be blank");
			}
			if (mapper == null) {
				throw new IllegalArgumentException("mapper must not be null");
			}
			this.mappers.put(toolName, mapper);
			return this;
		}

		public BusinessOutcomeMapperRegistry build() {
			return new BusinessOutcomeMapperRegistry(this.mappers);
		}

	}

}
