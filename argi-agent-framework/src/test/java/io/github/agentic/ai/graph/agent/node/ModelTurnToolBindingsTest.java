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
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.modelretry.ModelRetryInterceptor;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelTurnToolBindingsTest {

	@Test
	void resumedModelTurnsRetainTheirReplacementToolsAfterAnotherRun() throws Exception {
		var store = new AgentToolBindingStore();
		var llm = model(store, List.of(new ModelInterceptor() {
			@Override
			public String getName() {
				return "replace";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				return handler.call(ModelRequest.builder(request)
					.toolRegistry(registry(request.getMessages().get(0).text()))
					.build());
			}
		}), registry("configured"));
		var firstConfig = config();
		var first = llm.apply(input("first"), firstConfig);
		var second = llm.apply(input("second"), config());
		firstConfig.clearContext();
		var tool = tool(store, registry("configured"));

		assertThat(result(tool.apply(checkpoint(first), firstConfig))).isEqualTo("first");
		assertThat(result(tool.apply(checkpoint(second), config()))).isEqualTo("second");
	}

	@Test
	void sameNamedNodesCannotReplaceEachOthersBindingsInOneConfig() throws Exception {
		var firstStore = new AgentToolBindingStore();
		var secondStore = new AgentToolBindingStore();
		var config = config();
		var first = model(firstStore, List.of(), registry("first")).apply(input("input"), config);
		var second = model(secondStore, List.of(), registry("second")).apply(input("input"), config);

		assertThat(result(tool(firstStore, registry("first")).apply(checkpoint(first), config))).isEqualTo("first");
		assertThat(result(tool(secondStore, registry("second")).apply(checkpoint(second), config))).isEqualTo("second");
	}

	@Test
	void staticToolsCanResumeAfterAnAgentRebuild() throws Exception {
		var first = model(new AgentToolBindingStore(), List.of(), registry("first")).apply(input("input"), config());
		var rebuilt = tool(new AgentToolBindingStore(), registry("rebuilt"));
		assertThat(result(rebuilt.apply(checkpoint(first), config()))).isEqualTo("rebuilt");
	}

	@Test
	void aPersistedEmptySelectionCannotWidenOnRebuild() throws Exception {
		var first = model(new AgentToolBindingStore(), List.of(new ModelInterceptor() {
			@Override
			public String getName() {
				return "disable";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				return handler.call(ModelRequest.builder(request).tools(List.of()).build());
			}
		}), registry("configured")).apply(input("input"), config());
		assertThat(result(tool(new AgentToolBindingStore(), registry("rebuilt")).apply(checkpoint(first), config())))
			.isEqualTo("Tool not available: lookup");
	}

	@Test
	void unavailableDynamicSnapshotsFailBeforeDispatchInsteadOfUsingConfiguredTools() throws Exception {
		var first = model(new AgentToolBindingStore(), List.of(new ModelInterceptor() {
			@Override
			public String getName() {
				return "replace";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				return handler.call(ModelRequest.builder(request).toolRegistry(registry("dynamic")).build());
			}
		}), registry("configured")).apply(input("input"), config());
		var rebuilt = tool(new AgentToolBindingStore(), registry("wrong"));
		assertThatThrownBy(() -> rebuilt.apply(checkpoint(first), config())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("regenerate the model turn");
	}

	@Test
	void rebuiltStaticToolsMustStillMatchPersistedDeclarations() throws Exception {
		var first = model(new AgentToolBindingStore(), List.of(), registry("configured")).apply(input("input"),
				config());
		var rebuilt = tool(new AgentToolBindingStore(), new ToolRegistry(List.of()));
		assertThatThrownBy(() -> rebuilt.apply(checkpoint(first), config())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("no longer match");
	}

	@Test
	void snapshotRetentionMustBeBoundedWithAPositiveCapacity() {
		assertThatThrownBy(() -> new AgentToolBindingStore(0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aTurnWithoutSnapshotCannotReuseAPreviousTurnsSelection() throws Exception {
		var store = new AgentToolBindingStore();
		var config = config();
		var first = model(store, List.of(), registry("first")).apply(input("input"), config);
		var tool = tool(store, registry("configured"));
		assertThat(result(tool.apply(checkpoint(first), config))).isEqualTo("first");

		var unsnapshotted = ChatMessage.assistant("", List.of(new ToolCall("call", "lookup", "{}")));
		assertThat(result(tool.apply(new OverAllState(Map.of("messages", List.of(unsnapshotted))), config)))
			.isEqualTo("configured");
	}

	@Test
	void aTurnWithoutSnapshotCannotExecuteLegacyConfigCallbacks() throws Exception {
		var callback = mock(org.springframework.ai.tool.ToolCallback.class);
		when(callback.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
			.name("lookup")
			.description("Legacy callback")
			.inputSchema("{}")
			.build());
		var config = config();
		config.context().put("_DYNAMIC_TOOL_CALLBACKS_", List.of(callback));
		var invocation = ChatMessage.assistant("", List.of(new ToolCall("call", "lookup", "{}")));
		var node = tool(new AgentToolBindingStore(), new ToolRegistry(List.of()));

		assertThat(result(node.apply(new OverAllState(Map.of("messages", List.of(invocation))), config)))
			.isEqualTo("Tool not available: lookup");
		org.mockito.Mockito.verify(callback, org.mockito.Mockito.never()).call(any(), any());
	}

	@Test
	void aStreamingRetrySnapshotsTheBindingsOfTheSuccessfulAttempt() throws Exception {
		var store = new AgentToolBindingStore();
		var attempts = new AtomicInteger();
		var client = mock(ChatClient.class);
		when(client.capabilities()).thenReturn(new ChatClientCapabilities(true, true, false, false, true, true));
		when(client.stream(any()))
			.thenAnswer(
					ignored -> JdkFlowAdapter.publisherToFlowPublisher(
							attempts.get() == 1 ? Flux.error(new IllegalStateException("connection lost"))
									: Flux.just(ChatEvent.response(new ChatResponse(null, null,
											List.of(ChatMessage.assistant("")),
											List.of(new ToolCall("call", "lookup", "{}")), null, null, Map.of())))));
		var replacement = new ModelInterceptor() {
			@Override
			public String getName() {
				return "replace-per-attempt";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				return handler.call(ModelRequest.builder(request)
					.toolRegistry(registry("attempt-" + attempts.incrementAndGet()))
					.build());
			}
		};
		var node = AgentLlmNode.builder()
			.agentName("retry")
			.chatClient(client)
			.toolBindingStore(store)
			.toolRegistry(registry("configured"))
			.modelInterceptors(List.of(ModelRetryInterceptor.builder().initialDelay(0).build(), replacement))
			.build();
		var config = RunnableConfig.builder().addMetadata("_stream_", true).build();
		var response = (Flux<ChatResponse>) node.apply(input("input"), config).get("messages");
		var output = response.blockLast(java.time.Duration.ofSeconds(2)).messages().get(0);
		config.clearContext();

		assertThat(result(tool(store, registry("configured")).apply(checkpoint(Map.of("messages", output)), config)))
			.isEqualTo("attempt-2");
		assertThat(attempts).hasValue(2);
	}

	@Test
	void eachColdSubscriptionRebuildsItsOwnToolSelection() throws Exception {
		var store = new AgentToolBindingStore();
		var selections = new AtomicInteger();
		var client = streamingClient();
		when(client.stream(any()))
			.thenAnswer(ignored -> JdkFlowAdapter.publisherToFlowPublisher(Flux.just(toolEvent())));
		var node = AgentLlmNode.builder()
			.agentName("cold")
			.chatClient(client)
			.toolBindingStore(store)
			.toolRegistry(registry("configured"))
			.modelInterceptors(List.of(replacement(selections)))
			.build();
		var config = RunnableConfig.builder().addMetadata("_stream_", true).build();
		var stream = (Flux<ChatResponse>) node.apply(input("input"), config).get("messages");
		assertThat(selections).hasValue(0);
		var first = stream.blockLast(java.time.Duration.ofSeconds(2)).messages().get(0);
		var second = stream.blockLast(java.time.Duration.ofSeconds(2)).messages().get(0);
		var tool = tool(store, registry("configured"));

		assertThat(result(tool.apply(checkpoint(Map.of("messages", first)), config()))).isEqualTo("selection-1");
		assertThat(result(tool.apply(checkpoint(Map.of("messages", second)), config()))).isEqualTo("selection-2");
		assertThat(selections).hasValue(2);
	}

	@Test
	void overlappingSubscriptionsCannotOverwriteEachOthersBindings() throws Exception {
		var store = new AgentToolBindingStore();
		var selections = new AtomicInteger();
		var firstTurn = reactor.core.publisher.Sinks.<ChatEvent>one();
		var secondTurn = reactor.core.publisher.Sinks.<ChatEvent>one();
		var client = streamingClient();
		var calls = new AtomicInteger();
		when(client.stream(any())).thenAnswer(ignored -> JdkFlowAdapter
			.publisherToFlowPublisher(calls.incrementAndGet() == 1 ? firstTurn.asMono() : secondTurn.asMono()));
		var node = AgentLlmNode.builder()
			.agentName("overlap")
			.chatClient(client)
			.toolBindingStore(store)
			.toolRegistry(registry("configured"))
			.modelInterceptors(List.of(replacement(selections)))
			.build();
		var stream = (Flux<ChatResponse>) node
			.apply(input("input"), RunnableConfig.builder().addMetadata("_stream_", true).build())
			.get("messages");
		var first = stream.last().toFuture();
		var second = stream.last().toFuture();
		try {
			secondTurn.tryEmitValue(toolEvent());
			firstTurn.tryEmitValue(toolEvent());
			var tool = tool(store, registry("configured"));
			assertThat(result(tool.apply(
					checkpoint(
							Map.of("messages", first.get(2, java.util.concurrent.TimeUnit.SECONDS).messages().get(0))),
					config())))
				.isEqualTo("selection-1");
			assertThat(result(tool.apply(
					checkpoint(
							Map.of("messages", second.get(2, java.util.concurrent.TimeUnit.SECONDS).messages().get(0))),
					config())))
				.isEqualTo("selection-2");
		}
		finally {
			first.cancel(true);
			second.cancel(true);
		}
	}

	private ModelInterceptor replacement(AtomicInteger selections) {
		return new ModelInterceptor() {
			@Override
			public String getName() {
				return "replace-per-subscription";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				return handler.call(ModelRequest.builder(request)
					.toolRegistry(registry("selection-" + selections.incrementAndGet()))
					.build());
			}
		};
	}

	private ChatClient streamingClient() {
		var client = mock(ChatClient.class);
		when(client.capabilities()).thenReturn(new ChatClientCapabilities(true, true, false, false, true, true));
		return client;
	}

	private ChatEvent toolEvent() {
		return ChatEvent.response(new ChatResponse(null, null, List.of(ChatMessage.assistant("")),
				List.of(new ToolCall("call", "lookup", "{}")), null, null, Map.of()));
	}

	private AgentLlmNode model(AgentToolBindingStore store, List<ModelInterceptor> interceptors,
			ToolRegistry registry) {
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenReturn(new ChatResponse(null, null, List.of(ChatMessage.assistant("")),
				List.of(new ToolCall("call", "lookup", "{}")), null, null, Map.of()));
		return AgentLlmNode.builder()
			.agentName("same-name")
			.chatClient(client)
			.toolBindingStore(store)
			.toolRegistry(registry)
			.modelInterceptors(interceptors)
			.build();
	}

	private AgentToolNode tool(AgentToolBindingStore store, ToolRegistry registry) {
		return AgentToolNode.builder()
			.agentName("same-name")
			.toolBindingStore(store)
			.toolRegistry(registry)
			.throwOnToolError(false)
			.build();
	}

	private ToolRegistry registry(String result) {
		return new ToolRegistry(List.of(new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", null, "{}");
			}

			@Override
			public CompletableFuture<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text(result));
			}
		}));
	}

	private OverAllState checkpoint(Map<String, Object> modelOutput) throws Exception {
		var serializer = new ArgiJacksonStateSerializer(OverAllState::new);
		return serializer.cloneObject(new OverAllState(Map.of("messages", List.of(modelOutput.get("messages")))));
	}

	private String result(Map<String, Object> output) {
		return ((ChatMessage) ((List<?>) output.get("messages")).get(0)).text();
	}

	private OverAllState input(String text) {
		return new OverAllState(Map.of("messages", new java.util.ArrayList<>(List.of(ChatMessage.user(text)))));
	}

	private RunnableConfig config() {
		return RunnableConfig.builder().addMetadata("_stream_", false).build();
	}

}
