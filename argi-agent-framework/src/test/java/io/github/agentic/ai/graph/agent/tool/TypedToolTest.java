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
package io.github.agentic.ai.graph.agent.tool;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypedToolTest {

	record Input(String query) {
	}

	private final ToolCall call = new ToolCall("one", "lookup", "{\"query\":\"value\"}");

	private final ToolContext context = new ToolContext(Map.of("tenant", "one"));

	@Test
	void bindsTypedInputAndDefinition() {
		var tool = TypedTool.builder("lookup", (Input input) -> input.query())
			.inputType(Input.class)
			.description("Lookup")
			.returnDirect(true)
			.build();
		assertThat(tool.definition().name()).isEqualTo("lookup");
		assertThat(tool.definition().description()).isEqualTo("Lookup");
		assertThat(tool.definition().inputSchema()).contains("query", "string");
		assertThat(tool.returnDirect()).isTrue();
		assertThat(tool.execute(call, context).toCompletableFuture().join().toMessage(call).text()).isEqualTo("value");
	}

	@Test
	void passesInvocationContextToFunction() {
		var tool = TypedTool.builder("lookup", (Input input, ToolContext actual) -> {
			assertThat(actual).isSameAs(context);
			return input.query();
		}).inputType(Input.class).build();
		assertThat(tool.execute(call, context).toCompletableFuture().join().toMessage(call).text()).isEqualTo("value");
	}

	@Test
	void preservesCustomResultIdentity() {
		ToolResult expected = ToolResult.error("domain error");
		var tool = TypedTool.builder("lookup", (Input input) -> input.query())
			.inputType(Input.class)
			.resultConverter(value -> expected)
			.build();
		assertThat(tool.execute(call, context).toCompletableFuture().join()).isSameAs(expected);
	}

	@Test
	void rejectsMalformedJsonBeforeExecutingFunction() {
		AtomicBoolean executed = new AtomicBoolean();
		var tool = TypedTool.builder("lookup", (Input input) -> {
			executed.set(true);
			return input.query();
		}).inputType(Input.class).build();
		assertThatThrownBy(() -> tool.execute(new ToolCall("one", "lookup", "{"), context).toCompletableFuture().join())
			.hasCauseInstanceOf(IllegalArgumentException.class);
		assertThat(executed).isFalse();
	}

	@Test
	void rejectsCallsForOtherTools() {
		var tool = TypedTool.builder("lookup", (Input input) -> input.query()).inputType(Input.class).build();
		assertThatThrownBy(() -> tool.execute(new ToolCall("one", "other", "{}"), context).toCompletableFuture().join())
			.hasCauseInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void functionErrorsAreFailedStages() {
		IllegalStateException failure = new IllegalStateException("backend unavailable");
		var tool = TypedTool.builder("lookup", (Input input) -> {
			throw failure;
		}).inputType(Input.class).build();
		assertThatThrownBy(() -> tool.execute(call, context).toCompletableFuture().join()).hasCause(failure);
	}

	@Test
	void nullConverterResultsAreFailedStages() {
		var tool = TypedTool.builder("lookup", (Input input) -> input.query())
			.inputType(Input.class)
			.resultConverter(value -> null)
			.build();
		assertThatThrownBy(() -> tool.execute(call, context).toCompletableFuture().join())
			.hasCauseInstanceOf(NullPointerException.class);
	}

	@Test
	void nullFunctionsAreRejectedEagerly() {
		assertThatThrownBy(() -> TypedTool.builder("lookup", (Function<Input, String>) null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> TypedTool.builder("lookup", (BiFunction<Input, ToolContext, String>) null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void rejectsMissingBuilderRequirements() {
		assertThatThrownBy(() -> TypedTool.builder(null, (Input input) -> input.query()))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> TypedTool.builder("lookup", (Input input) -> input.query()).build())
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> TypedTool.builder("lookup", (Input input) -> input.query()).resultConverter(null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void nullInvocationArgumentsAreFailedStages() {
		var tool = TypedTool.builder("lookup", (Input input) -> input.query()).inputType(Input.class).build();
		assertThatThrownBy(() -> tool.execute(null, context).toCompletableFuture().join())
			.hasCauseInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> tool.execute(call, null).toCompletableFuture().join())
			.hasCauseInstanceOf(NullPointerException.class);
	}

}
