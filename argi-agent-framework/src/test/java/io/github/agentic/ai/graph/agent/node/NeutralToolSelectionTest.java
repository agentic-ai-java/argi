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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.adapter.springai.SpringAiToolAdapter;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NeutralToolSelectionTest {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void explicitSelectionFiltersNeutralDeclarationsIncludingEmptySelection(boolean empty) throws Exception {
		AtomicReference<ChatRequest> captured = new AtomicReference<>();
		ReactAgent agent = ReactAgent.builder()
			.name("selection")
			.chatClient(client(captured))
			.toolRegistry(new ToolRegistry(List.of(tool("lookup"), tool("clock"))))
			.interceptors(new ModelInterceptor() {
				@Override
				public String getName() {
					return "selection";
				}

				@Override
				public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
					assertThat(request.getTools()).containsExactly("lookup", "clock");
					assertThat(request.getToolDescriptions()).containsEntry("lookup", "Description of lookup");
					return handler
						.call(ModelRequest.builder(request).tools(empty ? List.of() : List.of("clock")).build());
				}
			})
			.build();

		assertThat(agent.call("test").text()).isEqualTo("done");
		assertThat(captured.get().tools()).extracting(ToolDefinition::name)
			.containsExactlyElementsOf(empty ? List.of() : List.of("clock"));
	}

	@Test
	void advertisesAllConfiguredNeutralToolsWithoutAnInterceptor() throws Exception {
		AtomicReference<ChatRequest> captured = new AtomicReference<>();
		ReactAgent.builder()
			.name("selection")
			.chatClient(client(captured))
			.toolRegistry(new ToolRegistry(List.of(tool("lookup"), tool("clock"))))
			.build()
			.call("test");
		assertThat(captured.get().tools()).extracting(ToolDefinition::name).containsExactly("lookup", "clock");
	}

	@Test
	void rejectsDuplicateBindingNames() {
		assertThatThrownBy(() -> new ToolRegistry(List.of(tool("lookup"), tool("lookup"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Duplicate tool name: lookup");
	}

	@Test
	void replacementBindingsRemainIsolatedAcrossConcurrentCallsOnTheSameAgent() throws Exception {
		CountDownLatch modelTurns = new CountDownLatch(2);
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			var result = request.messages().stream().filter(message -> message.role() == ChatRole.TOOL).findFirst();
			if (result.isPresent()) {
				return CompletableFuture.completedFuture(response(result.get().text(), List.of()));
			}
			assertThat(request.tools()).extracting(ToolDefinition::name).containsExactly("lookup");
			modelTurns.countDown();
			assertThat(modelTurns.await(5, TimeUnit.SECONDS)).isTrue();
			return CompletableFuture.completedFuture(response("", List.of(new ToolCall("call", "lookup", "{}"))));
		});
		ReactAgent agent = ReactAgent.builder().name("dynamic").chatClient(client).interceptors(new ModelInterceptor() {
			@Override
			public String getName() {
				return "bindings";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				String binding = (String) request.getContext().get("binding");
				Tool tool = boundTool("lookup", binding);
				return handler.call(ModelRequest.builder(request)
					.toolRegistry(new ToolRegistry(List.of(tool)))
					.tools(List.of("lookup"))
					.build());
			}
		}).build();
		var executor = Executors.newFixedThreadPool(2);
		try {
			var red = executor.submit(() -> agent.call(Map.of("input", "test", "binding", "red")).text());
			var green = executor.submit(() -> agent.call(Map.of("input", "test", "binding", "green")).text());
			assertThat(red.get(10, TimeUnit.SECONDS)).isEqualTo("red");
			assertThat(green.get(10, TimeUnit.SECONDS)).isEqualTo("green");
		}
		finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void anExcludedToolCannotExecuteEvenIfTheModelCallsIt() throws Exception {
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			var result = request.messages().stream().filter(message -> message.role() == ChatRole.TOOL).findFirst();
			assertThat(request.tools()).isEmpty();
			return CompletableFuture.completedFuture(result.isPresent() ? response(result.get().text(), List.of())
					: response("", List.of(new ToolCall("call", "lookup", "{}"))));
		});
		ReactAgent agent = ReactAgent.builder()
			.name("filtered")
			.chatClient(client)
			.toolRegistry(new ToolRegistry(List.of(tool("lookup"))))
			.interceptors(new ModelInterceptor() {
				@Override
				public String getName() {
					return "disable-tools";
				}

				@Override
				public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
					return handler.call(ModelRequest.builder(request).tools(List.of()).build());
				}
			})
			.build();
		assertThat(agent.call("test").text()).isEqualTo("Tool not available: lookup");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void explicitSelectionAlsoFiltersDynamicallyDisclosedCallbacks(boolean empty) throws Exception {
		AtomicInteger executions = new AtomicInteger();
		var callback = FunctionToolCallback.builder("lookup", () -> {
			executions.incrementAndGet();
			return "called";
		}).description("Lookup").build();
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			assertThat(request.tools()).extracting(ToolDefinition::name)
				.containsExactlyElementsOf(empty ? List.of() : List.of("lookup"));
			var result = request.messages().stream().filter(message -> message.role() == ChatRole.TOOL).findFirst();
			return CompletableFuture.completedFuture(result.isPresent() ? response(result.get().text(), List.of())
					: response("", List.of(new ToolCall("call", "lookup", "{}"))));
		});
		ReactAgent agent = ReactAgent.builder()
			.name("dynamic-selection")
			.chatClient(client)
			.interceptors(new ModelInterceptor() {
				@Override
				public String getName() {
					return "disclosure";
				}

				@Override
				public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
					return handler.call(ModelRequest.builder(request)
						.dynamicToolRegistry(new ToolRegistry(List.of(new SpringAiToolAdapter(callback))))
						.tools(empty ? List.of() : List.of("lookup"))
						.build());
				}
			})
			.build();
		assertThat(agent.call("test").text()).isEqualTo(empty ? "Tool not available: lookup" : "\"called\"");
		assertThat(executions).hasValue(empty ? 0 : 1);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void absentSelectionDisclosesStaticAndDynamicToolsAndEmptySelectionDisablesBoth(boolean disabled) throws Exception {
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			assertThat(request.tools()).extracting(ToolDefinition::name)
				.containsExactlyElementsOf(disabled ? List.of() : List.of("lookup", "clock"));
			var results = request.messages().stream().filter(message -> message.role() == ChatRole.TOOL).toList();
			return CompletableFuture.completedFuture(results.isEmpty()
					? response("",
							List.of(new ToolCall("static", "lookup", "{}"), new ToolCall("dynamic", "clock", "{}")))
					: response(
							results.stream().map(ChatMessage::text).collect(java.util.stream.Collectors.joining(",")),
							List.of()));
		});
		ReactAgent agent = ReactAgent.builder()
			.name("dynamic-disclosure")
			.chatClient(client)
			.toolRegistry(new ToolRegistry(List.of(boundTool("lookup", "static-result"))))
			.interceptors(new ModelInterceptor() {
				@Override
				public String getName() {
					return "disclosure";
				}

				@Override
				public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
					assertThat(request.hasToolSelection()).isFalse();
					var builder = ModelRequest.builder(request)
						.dynamicToolRegistry(new ToolRegistry(List.of(boundTool("clock", "dynamic-result"))));
					if (disabled) {
						builder.tools(List.of());
					}
					return handler.call(builder.build());
				}
			})
			.build();
		assertThat(agent.call("test").text()).isEqualTo(
				disabled ? "Tool not available: lookup,Tool not available: clock" : "static-result,dynamic-result");
	}

	private static ChatResponse response(String text, List<ToolCall> calls) {
		return new ChatResponse(null, null, List.of(ChatMessage.assistant(text)), calls, null, null, Map.of());
	}

	private static Tool boundTool(String name, String value) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, "Description of " + name, "{}");
			}

			@Override
			public CompletableFuture<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text(value));
			}
		};
	}

	private static ChatClient client(AtomicReference<ChatRequest> captured) {
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			captured.set(invocation.getArgument(0));
			return CompletableFuture.completedFuture(new ChatResponse(null, null,
					List.of(ChatMessage.assistant("done")), List.of(), null, null, Map.of()));
		});
		return client;
	}

	private static Tool tool(String name) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, "Description of " + name, "{}");
			}

			@Override
			public CompletableFuture<ToolResult> execute(ToolCall call, ToolContext context) {
				throw new AssertionError("This model turn must not invoke tools");
			}
		};
	}

}
