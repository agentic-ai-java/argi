/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.api.tool;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Immutable tool bindings. Runtime calls and dynamic tools use separate registry
 * instances.
 */
public final class ToolRegistry {

	private final Map<String, Tool> tools;

	private final List<ToolDefinition> definitions;

	public ToolRegistry(Collection<? extends Tool> tools) {
		Objects.requireNonNull(tools, "tools must not be null");
		Map<String, Tool> bindings = new LinkedHashMap<>();
		Map<String, ToolDefinition> declarations = new LinkedHashMap<>();
		for (Tool tool : tools) {
			Objects.requireNonNull(tool, "tools must not contain null");
			ToolDefinition definition = Objects.requireNonNull(tool.definition(), "tool definition must not be null");
			if (bindings.putIfAbsent(definition.name(), tool) != null) {
				throw new IllegalArgumentException("Duplicate tool name: " + definition.name());
			}
			declarations.put(definition.name(), definition);
		}
		this.tools = Collections.unmodifiableMap(bindings);
		this.definitions = List.copyOf(declarations.values());
	}

	public List<ToolDefinition> definitions() {
		return definitions;
	}

	public Optional<Tool> find(String name) {
		return Optional.ofNullable(tools.get(Objects.requireNonNull(name, "name must not be null")));
	}

	/** Unknown tools and synchronous failures are represented as failed stages. */
	public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
		Objects.requireNonNull(call, "call must not be null");
		Objects.requireNonNull(context, "context must not be null");
		Tool tool = tools.get(call.name());
		if (tool == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown tool: " + call.name()));
		}
		try {
			CompletableFuture<ToolResult> source = Objects
				.requireNonNull(tool.execute(call, context), "tool returned no stage")
				.toCompletableFuture();
			CompletableFuture<ToolResult> result = new CompletableFuture<>();
			result.whenComplete((value, error) -> {
				if (result.isCancelled()) {
					source.cancel(true);
				}
			});
			source.whenComplete((value, error) -> {
				if (source.isCancelled()) {
					result.cancel(true);
				}
				else if (error != null) {
					result.completeExceptionally(error);
				}
				else if (value == null) {
					result.completeExceptionally(new NullPointerException("tool returned no result"));
				}
				else {
					result.complete(value);
				}
			});
			return result;
		}
		catch (Exception error) {
			return CompletableFuture.failedFuture(error);
		}
	}

}
