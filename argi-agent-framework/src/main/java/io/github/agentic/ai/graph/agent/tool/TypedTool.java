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
package io.github.agentic.ai.graph.agent.tool;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.agent.MessageToolCallResultConverter;
import io.github.agentic.ai.graph.agent.internal.JsonCodec;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Typed JSON tool binding. Blocking functions are scheduled by the bounded tool runtime.
 */
public final class TypedTool<T, R> implements Tool {

	private final ToolDefinition definition;

	private final Class<T> inputType;

	private final BiFunction<T, ToolContext, R> function;

	private final Function<R, ToolResult> converter;

	private final boolean returnDirect;

	private TypedTool(Builder<T, R> builder) {
		this.inputType = Objects.requireNonNull(builder.inputType, "inputType must not be null");
		this.definition = new ToolDefinition(builder.name, builder.description, ToolSchema.forType(inputType));
		this.function = builder.function;
		this.converter = builder.converter;
		this.returnDirect = builder.returnDirect;
	}

	public static <T, R> Builder<T, R> builder(String name, BiFunction<T, ToolContext, R> function) {
		return new Builder<>(name, function);
	}

	public static <T, R> Builder<T, R> builder(String name, Function<T, R> function) {
		Objects.requireNonNull(function, "function must not be null");
		return new Builder<>(name, (input, context) -> function.apply(input));
	}

	@Override
	public ToolDefinition definition() {
		return definition;
	}

	@Override
	public boolean returnDirect() {
		return returnDirect;
	}

	@Override
	public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
		try {
			Objects.requireNonNull(context, "context must not be null");
			if (!definition.name().equals(call.name())) {
				throw new IllegalArgumentException("Tool call does not match " + definition.name());
			}
			T input = new JsonCodec().fromJson(call.arguments(), inputType);
			return CompletableFuture.completedFuture(Objects
				.requireNonNull(converter.apply(function.apply(input, context)), "Tool converter returned null"));
		}
		catch (Exception exception) {
			return CompletableFuture.failedFuture(exception);
		}
	}

	public static final class Builder<T, R> {

		private final String name;

		private final BiFunction<T, ToolContext, R> function;

		private String description;

		private Class<T> inputType;

		private boolean returnDirect;

		private Function<R, ToolResult> converter = result -> new MessageToolCallResultConverter().convert(result,
				null);

		private Builder(String name, BiFunction<T, ToolContext, R> function) {
			this.name = Objects.requireNonNull(name, "name must not be null");
			this.function = Objects.requireNonNull(function, "function must not be null");
		}

		public Builder<T, R> description(String description) {
			this.description = description;
			return this;
		}

		public Builder<T, R> inputType(Class<T> inputType) {
			this.inputType = inputType;
			return this;
		}

		public Builder<T, R> returnDirect(boolean returnDirect) {
			this.returnDirect = returnDirect;
			return this;
		}

		public Builder<T, R> resultConverter(Function<R, ToolResult> converter) {
			this.converter = Objects.requireNonNull(converter, "converter must not be null");
			return this;
		}

		public TypedTool<T, R> build() {
			return new TypedTool<>(this);
		}

	}

}
