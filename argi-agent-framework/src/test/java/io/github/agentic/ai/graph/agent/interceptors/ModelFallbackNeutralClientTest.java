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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.interceptor.InterceptorChain;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.StreamingModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.modelfallback.ModelFallbackInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelFallbackNeutralClientTest {

	@Test
	void agentKeepsRuntimeStateInInterceptorContextAndOnlyForwardsExplicitAttributes() throws Exception {
		StubClient client = new StubClient("done", null);
		var agent = ReactAgent.builder()
			.name("context-isolation")
			.chatClient(client)
			.interceptors(new ModelInterceptor() {
				@Override
				public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
					assertThat(request.getContext()).containsKeys("messages", "input");
					assertThat(request.getAttributes()).isEmpty();
					return handler
						.call(ModelRequest.builder(request).attributes(Map.of("correlation", "call-1")).build());
				}

				@Override
				public String getName() {
					return "context-isolation";
				}
			})
			.build();
		assertThat(agent.call("question").text()).isEqualTo("done");
		assertThat(client.requests).hasSize(1);
		assertThat(client.requests.get(0).attributes()).containsExactlyEntriesOf(Map.of("correlation", "call-1"));
	}

	@Test
	void successfulExceptionTextDoesNotTriggerFallback() throws Exception {
		StubClient primary = new StubClient("Exception: is part of the answer", null);
		StubClient fallback = new StubClient("wrong", null);
		var result = agent(primary, fallback).call("test");
		assertThat(result.text()).isEqualTo("Exception: is part of the answer");
		assertThat(primary.requests).hasSize(1);
		assertThat(fallback.requests).isEmpty();
	}

	@Test
	void agentFallbackReceivesTheSameSystemHistoryAndGenerationOptions() throws Exception {
		StubClient primary = new StubClient(null, new IllegalStateException("Unavailable"));
		StubClient fallback = new StubClient("Recovered", null);
		GenerationOptions options = GenerationOptions.builder().temperature(0.2).maxTokens(150).build();
		var result = ReactAgent.builder()
			.name("neutral-fallback")
			.chatClient(primary)
			.systemPrompt("Keep the rules")
			.generationOptions(options)
			.interceptors(interceptor(fallback))
			.build()
			.call("Keep the question");
		assertThat(result.text()).isEqualTo("Recovered");
		assertThat(primary.requests).hasSize(1);
		assertThat(fallback.requests).hasSize(1);
		assertThat(fallback.requests.get(0)).isEqualTo(primary.requests.get(0));
		assertThat(fallback.requests.get(0).options()).isEqualTo(options);
		assertThat(fallback.requests.get(0).messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo(ChatRole.SYSTEM);
			assertThat(message.text()).isEqualTo("Keep the rules");
		});
	}

	@Test
	void fallbackCopyRetainsToolsContextAndSystemAndDoesNotMutateOriginal() {
		StubClient fallback = new StubClient("Recovered", null);
		Tool tool = tool("lookup");
		Tool dynamic = tool("dynamic");
		GenerationOptions options = GenerationOptions.builder().temperature(0.3).build();
		ModelRequest original = ModelRequest.builder()
			.systemMessage(ChatMessage.system("rules"))
			.messages(new ArrayList<>(List.of(ChatMessage.user("question"))))
			.options(options)
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.tools(List.of("lookup"))
			.dynamicToolRegistry(new ToolRegistry(List.of(dynamic)))
			.toolDescriptions(Map.of("lookup", "Looks up a value"))
			.context(Map.of("thread", "t-1"))
			.attributes(Map.of("correlation", "call-1"))
			.build();
		ModelResponse expected = ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Recovered"));
		ModelResponse result = interceptor(fallback).interceptModel(original, request -> {
			if (request.getChatClient() == null) {
				throw new IllegalStateException("Unavailable");
			}
			assertThat(request.getChatClient()).isSameAs(fallback);
			assertThat(request.getSystemMessage()).isSameAs(original.getSystemMessage());
			assertThat(request.getMessages()).isEqualTo(original.getMessages());
			assertThat(request.getOptions()).isSameAs(options);
			assertThat(request.getToolRegistry().find("lookup")).containsSame(tool);
			assertThat(request.getDynamicToolRegistry().find("dynamic")).containsSame(dynamic);
			assertThat(request.getTools()).containsExactly("lookup");
			assertThat(request.getToolDescriptions()).containsEntry("lookup", "Looks up a value");
			assertThat(request.getContext()).containsEntry("thread", "t-1");
			assertThat(request.getAttributes()).containsExactlyEntriesOf(Map.of("correlation", "call-1"));
			assertThatThrownBy(() -> request.getToolRegistry().definitions().clear())
				.isInstanceOf(UnsupportedOperationException.class);
			request.getContext().clear();
			return expected;
		});
		assertThat(result).isSameAs(expected);
		assertThat(original.getChatClient()).isNull();
		assertThat(original.getToolRegistry().find("lookup")).containsSame(tool);
		assertThat(original.getContext()).containsEntry("thread", "t-1");
	}

	@Test
	void fallbackExhaustionPreservesOrderAndFinalCause() {
		StubClient first = new StubClient(null, null);
		StubClient second = new StubClient(null, null);
		List<ChatClient> attempts = new ArrayList<>();
		RuntimeException last = new IllegalStateException("Last failure");
		var interceptor = ModelFallbackInterceptor.builder().fallbackClients(List.of(first, second)).build();
		assertThatThrownBy(() -> interceptor.interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			throw request.getChatClient() == second ? last : new IllegalStateException("Unavailable");
		})).hasMessage("All clients failed after 3 attempts").hasCause(last);
		assertThat(attempts).containsExactly(null, first, second);
		assertThatThrownBy(() -> ModelFallbackInterceptor.builder().build())
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ModelFallbackInterceptor.builder().addFallbackClient(null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void cancellationAndInterruptionNeverAttemptAnotherClient() {
		StubClient fallback = new StubClient("wrong", null);
		CancellationException cancelled = new CancellationException("Cancelled");
		assertThatThrownBy(() -> interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			throw cancelled;
		})).isSameAs(cancelled);
		try {
			assertThatThrownBy(() -> interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted");
			})).isInstanceOf(CancellationException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
		assertThat(fallback.requests).isEmpty();
	}

	@Test
	void subscriptionCopiesRetainClientOverrideAndIsolateRegistryReplacement() {
		StubClient fallback = new StubClient("Recovered", null);
		Tool tool = tool("lookup");
		ModelRequest original = ModelRequest.builder()
			.chatClient(fallback)
			.messages(List.of(ChatMessage.user("test")))
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.build();
		var wrapped = InterceptorChain.applyStreamingInterceptors(List.of(new StreamingModelInterceptor() {
			@Override
			public ModelRequest beforeStreamCall(ModelRequest request) {
				assertThat(request.getChatClient()).isSameAs(fallback);
				assertThat(request.getToolRegistry().find("lookup")).containsSame(tool);
				return ModelRequest.builder(request).toolRegistry(new ToolRegistry(List.of())).build();
			}
		}), reactor.adapter.JdkFlowAdapter.publisherToFlowPublisher(
				Flux.just(ChatEvent.response(ModelResponse.of(ChatMessage.assistant("done")).getChatResponse()))),
				original);
		reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(wrapped).blockLast();
		reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(wrapped).blockLast();
		assertThat(original.getToolRegistry().find("lookup")).containsSame(tool);
	}

	private static Tool tool(String name) {
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition(name, "Description of " + name, "{}"));
		return tool;
	}

	private static ReactAgent agent(ChatClient primary, ChatClient fallback) {
		return ReactAgent.builder()
			.name("neutral-fallback")
			.chatClient(primary)
			.interceptors(interceptor(fallback))
			.build();
	}

	private static ModelFallbackInterceptor interceptor(ChatClient fallback) {
		return ModelFallbackInterceptor.builder().addFallbackClient(fallback).build();
	}

	private static final class StubClient implements ChatClient {

		private final String text;

		private final RuntimeException failure;

		private final List<ChatRequest> requests = new ArrayList<>();

		private StubClient(String text, RuntimeException failure) {
			this.text = text;
			this.failure = failure;
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			requests.add(request);
			return failure == null
					? CompletableFuture.completedFuture(new ChatResponse("id", "test-model",
							List.of(ChatMessage.assistant(text)), List.of(), null, new Usage(2L, 3L, 5L), Map.of()))
					: CompletableFuture.failedFuture(failure);
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException("Streaming is unavailable");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
