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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.modelfallback.ModelFallbackInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.adapter.JdkFlowAdapter;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelFallbackInterceptorStreamingTest {

	@Test
	void streamingPrimaryResponseDoesNotCallFallback() {
		CountingModel fallback = new CountingModel("fallback");
		ModelResponse primary = ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
				Flux.just(ChatEvent.response(ModelResponse.of(ChatMessage.assistant("primary")).getChatResponse()))));
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().messages(List.of()).build(),
				request -> primary);

		assertSame(primary, result);
		assertEquals(0, fallback.calls);
	}

	@Test
	void publisherErrorsRemainStreamingErrors() {
		CountingModel fallback = new CountingModel("fallback");
		RuntimeException failure = new RuntimeException("stream failed");
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().messages(List.of()).build(),
				request -> ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(Flux.error(failure))));

		Flux<ChatEvent> stream = JdkFlowAdapter.flowPublisherToFlux(result.getStream());
		assertSame(failure, assertThrows(RuntimeException.class, () -> stream.blockLast(Duration.ofSeconds(2))));
		assertEquals(0, fallback.calls);
	}

	@Test
	void synchronousPrimaryResponseIsPreserved() {
		CountingModel fallback = new CountingModel("fallback");
		ModelResponse primary = ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("primary"));

		assertSame(primary, interceptor(fallback).interceptModel(ModelRequest.builder().messages(List.of()).build(),
				request -> primary));
		assertEquals(0, fallback.calls);
	}

	@Test
	void synchronousPrimaryFailureStillCallsFallback() {
		CountingModel fallback = new CountingModel("fallback");
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().messages(List.of()).build(),
				request -> {
					if (request.getChatClient() == null) {
						throw new RuntimeException("primary failed");
					}
					fallback.calls++;
					return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("fallback"));
				});

		assertEquals("fallback", result.getMessage().text());
		assertEquals(1, fallback.calls);
	}

	@Test
	void agentStreamingKeepsThePrimaryModelOutput() throws Exception {
		CountingModel primary = new CountingModel("primary");
		CountingModel fallback = new CountingModel("fallback");
		ReactAgent agent = ReactAgent.builder()
			.name("streaming-fallback-test")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(primary))
			.interceptors(interceptor(fallback))
			.build();
		NodeOutput output = agent.stream("hello", RunnableConfig.builder().threadId("streaming-fallback").build())
			.last()
			.block(Duration.ofSeconds(2));

		assertNotNull(output);
		List<?> messages = assertInstanceOf(List.class, output.state().value("messages").orElseThrow());
		assertEquals("primary", assertInstanceOf(ChatMessage.class, messages.get(messages.size() - 1)).text());
		assertEquals(1, primary.streams);
		assertEquals(0, primary.calls);
		assertEquals(0, fallback.calls);
	}

	private static ModelFallbackInterceptor interceptor(ChatModel fallback) {
		return ModelFallbackInterceptor.builder()
			.addFallbackClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(fallback))
			.build();
	}

	private static ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static final class CountingModel implements ChatModel {

		private final String text;

		private int calls;

		private int streams;

		private CountingModel(String text) {
			this.text = text;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			calls++;
			return response(text);
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			streams++;
			return Flux.just(response(text));
		}

	}

}
