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
package io.github.agentic.ai.graph.agent.node;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.interceptor.modelretry.ModelRetryInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import io.github.agentic.ai.api.chat.ChatMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class AgentLlmNodeErrorHandlingTest {

	@ParameterizedTest
	@NullSource
	@ValueSource(booleans = false)
	void synchronousErrorsRemainMessagesByDefault(Boolean throwOnModelError) throws Exception {
		ChatModel model = mockModel();
		when(model.call(any(Prompt.class))).thenThrow(failure());
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), throwOnModelError).build();
		ChatMessage message = assertInstanceOf(ChatMessage.class, apply(node, false));

		assertEquals("Exception: model unavailable", message.text());
		verify(model, times(1)).call(any(Prompt.class));
	}

	@Test
	void synchronousErrorsRetainTheirTypeAndCauseWhenEnabled() {
		ChatModel model = mockModel();
		ResourceAccessException failure = failure();
		when(model.call(any(Prompt.class))).thenThrow(failure);
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), true).build();

		ResourceAccessException thrown = assertThrows(ResourceAccessException.class, () -> apply(node, false));

		assertSame(failure, thrown);
		assertSame(failure.getCause(), thrown.getCause());
		verify(model, times(1)).call(any(Prompt.class));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(booleans = false)
	void adaptedStreamingSetupErrorsAreDeferredUntilSubscription(Boolean throwOnModelError) throws Exception {
		ChatClient client = mock(ChatClient.class);
		ResourceAccessException failure = failure();
		when(client.prompt()).thenThrow(failure);
		AgentLlmNode node = nodeBuilder(client, throwOnModelError).build();

		Flux<?> output = assertInstanceOf(Flux.class, apply(node, true));
		assertSame(failure, assertThrows(ResourceAccessException.class, () -> output.blockLast(Duration.ofSeconds(2))));
	}

	@Test
	void adaptedStreamingSetupErrorsPropagateWhenEnabled() throws Exception {
		ChatClient client = mock(ChatClient.class);
		ResourceAccessException failure = failure();
		when(client.prompt()).thenThrow(failure);
		AgentLlmNode node = nodeBuilder(client, true).build();

		Flux<?> output = assertInstanceOf(Flux.class, apply(node, true));
		assertSame(failure, assertThrows(ResourceAccessException.class, () -> output.blockLast(Duration.ofSeconds(2))));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(booleans = { false, true })
	void streamingErrorSignalsStillPropagateAfterPartialOutput(Boolean throwOnModelError) throws Exception {
		ChatModel model = mockModel();
		ResourceAccessException failure = failure();
		when(model.stream(any(Prompt.class)))
			.thenReturn(Flux.concat(Flux.just(response("partial")), Flux.error(failure)));
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), throwOnModelError).build();
		List<String> received = new ArrayList<>();

		Flux<?> output = assertInstanceOf(Flux.class, apply(node, true));
		ResourceAccessException thrown = assertThrows(ResourceAccessException.class,
				() -> output.cast(io.github.agentic.ai.api.chat.ChatResponse.class)
					.doOnNext(chunk -> received.add(chunk.messages().get(0).text()))
					.blockLast(Duration.ofSeconds(2)));

		assertSame(failure, thrown);
		assertEquals(List.of("partial"), received);
		verify(model, times(1)).stream(any(Prompt.class));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(booleans = { false, true })
	void streamingErrorSignalsStillPropagateBeforeAnyOutput(Boolean throwOnModelError) throws Exception {
		ChatModel model = mockModel();
		ResourceAccessException failure = failure();
		when(model.stream(any(Prompt.class))).thenReturn(Flux.error(failure));
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), throwOnModelError).build();

		Flux<?> output = assertInstanceOf(Flux.class, apply(node, true));

		assertSame(failure, assertThrows(ResourceAccessException.class, () -> output.blockLast(Duration.ofSeconds(2))));
		verify(model, times(1)).stream(any(Prompt.class));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void successfulStreamingResponsesRemainUnchanged(boolean throwOnModelError) throws Exception {
		ChatModel model = mockModel();
		when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("success")));
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), throwOnModelError).build();

		Flux<?> output = assertInstanceOf(Flux.class, apply(node, true));
		List<io.github.agentic.ai.api.chat.ChatResponse> responses = output
			.cast(io.github.agentic.ai.api.chat.ChatResponse.class)
			.collectList()
			.block(Duration.ofSeconds(2));

		assertEquals(2, responses.size());
		assertEquals("success", responses.get(0).messages().get(0).text());
		assertEquals("success", responses.get(1).messages().get(0).text());
		assertEquals(true, responses.get(1).metadata().get("argiFinalResponse"));
		assertEquals("", responses.get(1).metadata().get("argiTextDelta"));
		verify(model, times(1)).stream(any(Prompt.class));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void successfulTextIsNotClassifiedAsAnError(boolean throwOnModelError) throws Exception {
		ChatModel model = mockModel();
		when(model.call(any(Prompt.class))).thenReturn(response("Exception: is a prefix, not a failure"));
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), throwOnModelError).build();

		ChatMessage message = assertInstanceOf(ChatMessage.class, apply(node, false));

		assertEquals("Exception: is a prefix, not a failure", message.text());
	}

	@Test
	void retryInterceptorReceivesOriginalException() throws Exception {
		ChatModel model = mockModel();
		ResourceAccessException failure = failure();
		when(model.call(any(Prompt.class))).thenThrow(failure).thenReturn(response("recovered"));
		List<Exception> classified = new ArrayList<>();
		ModelRetryInterceptor retry = ModelRetryInterceptor.builder()
			.maxAttempts(2)
			.initialDelay(0)
			.retryableExceptionPredicate(error -> {
				classified.add(error);
				return error instanceof ResourceAccessException;
			})
			.build();
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), true).modelInterceptors(List.of(retry)).build();

		ChatMessage message = assertInstanceOf(ChatMessage.class, apply(node, false));

		assertEquals("recovered", message.text());
		assertEquals(1, classified.size());
		assertSame(failure, classified.get(0));
		verify(model, times(2)).call(any(Prompt.class));
	}

	@Test
	void retryExhaustionPreservesOriginalCause() {
		ChatModel model = mockModel();
		ResourceAccessException failure = failure();
		when(model.call(any(Prompt.class))).thenThrow(failure);
		AgentLlmNode node = nodeBuilder(ChatClient.create(model), true)
			.modelInterceptors(List.of(ModelRetryInterceptor.builder().maxAttempts(1).build()))
			.build();

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> apply(node, false));

		assertSame(failure, thrown.getCause());
		verify(model, times(1)).call(any(Prompt.class));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(booleans = { false, true })
	void publicAgentBuilderWiresErrorPolicy(Boolean throwOnModelError) throws Exception {
		ChatModel model = mockModel();
		ResourceAccessException failure = failure();
		when(model.call(any(Prompt.class))).thenThrow(failure);
		var builder = ReactAgent.builder()
			.name("error-policy-test")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model));
		if (throwOnModelError != null) {
			builder.throwOnModelError(throwOnModelError);
		}
		ReactAgent agent = builder.build();

		if (Boolean.TRUE.equals(throwOnModelError)) {
			Exception thrown = assertThrows(Exception.class, () -> agent.call("test"));
			Throwable cause = thrown;
			while (cause != null && cause != failure) {
				cause = cause.getCause();
			}
			assertSame(failure, cause, "Graph execution must retain the model exception in its cause chain");
		}
		else {
			assertEquals("Exception: model unavailable", agent.call("test").text());
		}
		verify(model, times(1)).call(any(Prompt.class));
	}

	private static ChatModel mockModel() {
		ChatModel model = mock(ChatModel.class);
		when(model.getOptions()).thenReturn(ChatOptions.builder().build());
		return model;
	}

	private static AgentLlmNode.Builder nodeBuilder(ChatClient client, Boolean throwOnModelError) {
		AgentLlmNode.Builder builder = AgentLlmNode.builder()
			.agentName("test")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(client));
		if (throwOnModelError != null) {
			builder.throwOnModelError(throwOnModelError);
		}
		return builder;
	}

	private static Object apply(AgentLlmNode node, boolean stream) throws Exception {
		OverAllState state = new OverAllState(Map.of("messages", new ArrayList<>(List.of(ChatMessage.user("test")))));
		RunnableConfig config = RunnableConfig.builder().addMetadata("_stream_", stream).build();
		return node.apply(state, config).get("messages");
	}

	private static ResourceAccessException failure() {
		return new ResourceAccessException("model unavailable", new SocketTimeoutException("read deadline"));
	}

	private static ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

}
