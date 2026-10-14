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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTests {

	@Test
	void declarationsDoNotExecuteToolsAndResultsPreserveIdentity() {
		AtomicInteger executions = new AtomicInteger();
		Tool tool = tool((call, context) -> {
			executions.incrementAndGet();
			return CompletableFuture.completedFuture(ToolResult.text(context.attributes().get("value").toString()));
		});
		List<Tool> inputs = new ArrayList<>(List.of(tool));
		ToolRegistry registry = new ToolRegistry(inputs);
		inputs.clear();
		assertEquals(List.of(tool.definition()), registry.definitions());
		assertEquals(tool, registry.find("search").orElseThrow());
		assertTrue(registry.find("missing").isEmpty());
		assertFalse(tool.returnDirect());
		assertEquals(0, executions.get());
		ToolCall call = new ToolCall("id", "search", "{}");
		var message = registry.execute(call, new ToolContext(Map.of("value", "found")))
			.toCompletableFuture()
			.join()
			.toMessage(call);
		assertEquals("id", message.toolCallId());
		assertEquals("search", message.toolName());
		assertEquals("found", message.text());
		assertEquals(false, message.metadata().get("tool.error"));
		assertEquals(1, executions.get());
		assertThrows(UnsupportedOperationException.class, () -> registry.definitions().clear());
	}

	@Test
	void contextAndResultsAreImmutableAndRetainErrorMetadata() {
		Map<String, Object> attributes = new HashMap<>(Map.of("value", "original"));
		ToolContext context = new ToolContext(attributes);
		attributes.clear();
		assertEquals("original", context.attributes().get("value"));
		assertThrows(UnsupportedOperationException.class, () -> context.attributes().clear());
		assertTrue(new ToolContext(null).attributes().isEmpty());
		assertTrue(ToolContext.empty().attributes().isEmpty());
		ToolResult error = ToolResult.error("failure");
		assertTrue(error.error());
		assertEquals(true, error.toMessage(new ToolCall("id", "search", "{}")).metadata().get("tool.error"));
		assertThrows(UnsupportedOperationException.class, () -> error.content().clear());
		assertTrue(new ToolResult(List.of(), false, null).metadata().isEmpty());
	}

	@Test
	void rejectsDuplicateBindingsAndPropagatesFailuresAsStages() {
		Tool normal = tool((call, context) -> CompletableFuture.completedFuture(ToolResult.text("ok")));
		assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(normal, normal)));
		assertThrows(NullPointerException.class, () -> new ToolRegistry(null));
		ToolCall call = new ToolCall("id", "search", "{}");
		assertThrows(CompletionException.class,
				() -> new ToolRegistry(List.of()).execute(call, ToolContext.empty()).toCompletableFuture().join());
		for (Tool tool : List.of(tool((invocation, context) -> {
			throw new IllegalStateException("failure");
		}), tool((invocation, context) -> null), tool((invocation, context) -> CompletableFuture.completedFuture(null)),
				tool((invocation, context) -> CompletableFuture
					.failedFuture(new IllegalStateException("async failure"))))) {
			assertThrows(CompletionException.class,
					() -> new ToolRegistry(List.of(tool)).execute(call, ToolContext.empty())
						.toCompletableFuture()
						.join());
		}
	}

	@Test
	void independentContextsDoNotLeakBetweenConcurrentInvocations() {
		CompletableFuture<ToolResult> first = new CompletableFuture<>();
		CompletableFuture<ToolResult> second = new CompletableFuture<>();
		ToolRegistry registry = new ToolRegistry(
				List.of(tool((call, context) -> "first".equals(context.attributes().get("request")) ? first : second)));
		var firstResult = registry.execute(new ToolCall("one", "search", "{}"),
				new ToolContext(Map.of("request", "first")));
		var secondResult = registry.execute(new ToolCall("two", "search", "{}"),
				new ToolContext(Map.of("request", "second")));
		second.complete(ToolResult.text("second"));
		assertFalse(firstResult.toCompletableFuture().isDone());
		first.complete(ToolResult.text("first"));
		assertEquals("first", firstResult.toCompletableFuture().join().content().get(0).text());
		assertEquals("second", secondResult.toCompletableFuture().join().content().get(0).text());
	}

	@Test
	void cancellationPropagatesInBothDirectionsWithoutAffectingOtherInvocations() {
		CompletableFuture<ToolResult> source = new CompletableFuture<>();
		var registry = new ToolRegistry(List.of(tool((call, context) -> source)));
		var result = registry.execute(new ToolCall("one", "search", "{}"), ToolContext.empty()).toCompletableFuture();
		assertTrue(result.cancel(true));
		assertTrue(source.isCancelled());

		CompletableFuture<ToolResult> nativeCancelled = new CompletableFuture<>();
		var nativeRegistry = new ToolRegistry(List.of(tool((call, context) -> nativeCancelled)));
		var nativeResult = nativeRegistry.execute(new ToolCall("two", "search", "{}"), ToolContext.empty())
			.toCompletableFuture();
		nativeCancelled.cancel(true);
		assertTrue(nativeResult.isCancelled());
	}

	private static Tool tool(BiFunction<ToolCall, ToolContext, CompletionStage<ToolResult>> action) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition("search", "Search", "{\"type\":\"object\"}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return action.apply(call, context);
			}
		};
	}

}
