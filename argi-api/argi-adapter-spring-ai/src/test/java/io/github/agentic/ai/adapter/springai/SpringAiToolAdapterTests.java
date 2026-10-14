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
package io.github.agentic.ai.adapter.springai;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.ToolContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiToolAdapterTests {

	@Test
	void preservesSchemaReturnDirectAndContextWithoutRunningAtRegistration() {
		ToolCallback callback = callback();
		when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(true).build());
		when(callback.call(eq("{}"), any())).thenAnswer(invocation -> {
			var context = invocation.getArgument(1, org.springframework.ai.chat.model.ToolContext.class);
			assertEquals("thread-1", context.getContext().get("thread"));
			return "found";
		});
		var registry = SpringAiToolAdapter.fromCallbacks(List.of(callback));
		var tool = registry.find("search").orElseThrow();
		assertEquals("Search documents", tool.definition().description());
		assertEquals("{\"type\":\"object\"}", tool.definition().inputSchema());
		assertTrue(tool.returnDirect());
		var result = registry
			.execute(new ToolCall("call-1", "search", "{}"), new ToolContext(Map.of("thread", "thread-1")))
			.toCompletableFuture()
			.join();
		assertEquals("found", result.toMessage(new ToolCall("call-1", "search", "{}")).text());
		verify(callback).call(eq("{}"), any());
	}

	@Test
	void synchronousExceptionsBecomeFailedStages() {
		ToolCallback callback = callback();
		var failure = new IllegalStateException("failed");
		when(callback.call(eq("{}"), any())).thenThrow(failure);
		var stage = new SpringAiToolAdapter(callback).execute(new ToolCall("id", "search", "{}"), ToolContext.empty());
		assertSame(failure, assertThrows(CompletionException.class, stage::join).getCause());
	}

	@Test
	void nullNativeResultsFailInsteadOfFabricatingText() {
		var adapter = new SpringAiToolAdapter(callback());
		var stage = adapter.execute(new ToolCall("id", "search", "{}"), ToolContext.empty());
		assertTrue(assertThrows(CompletionException.class, stage::join).getCause() instanceof NullPointerException);
	}

	@Test
	void adaptsAnnotatedMethodsAndEmptyRegistries() {
		var registry = SpringAiToolAdapter.fromMethods(new Methods());
		assertEquals("\"pong\"",
				registry.execute(new ToolCall("id", "ping", "{}"), ToolContext.empty())
					.toCompletableFuture()
					.join()
					.toMessage(new ToolCall("id", "ping", "{}"))
					.text());
		assertTrue(SpringAiToolAdapter.fromCallbacks().definitions().isEmpty());
	}

	@Test
	void rejectsNullInputsAndDuplicateBindings() {
		assertThrows(NullPointerException.class, () -> new SpringAiToolAdapter(null));
		assertThrows(NullPointerException.class, () -> SpringAiToolAdapter.fromCallbacks((ToolCallback[]) null));
		assertThrows(NullPointerException.class, () -> SpringAiToolAdapter.fromCallbacks((List<ToolCallback>) null));
		assertThrows(NullPointerException.class, () -> SpringAiToolAdapter.fromCallbacks(new ToolCallback[] { null }));
		var callback = callback();
		assertThrows(IllegalArgumentException.class, () -> SpringAiToolAdapter.fromCallbacks(callback, callback));
		var adapter = new SpringAiToolAdapter(callback);
		assertThrows(NullPointerException.class, () -> adapter.execute(null, ToolContext.empty()));
		assertThrows(NullPointerException.class, () -> adapter.execute(new ToolCall("id", "search", "{}"), null));
	}

	private static ToolCallback callback() {
		ToolCallback callback = mock(ToolCallback.class);
		when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
			.name("search")
			.description("Search documents")
			.inputSchema("{\"type\":\"object\"}")
			.build());
		when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
		return callback;
	}

	static class Methods {

		@Tool(description = "Respond to a ping")
		String ping() {
			return "pong";
		}

	}

}
