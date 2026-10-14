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

import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;

import io.github.agentic.ai.graph.agent.interceptor.modelretry.ModelRetryInterceptor;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRetryInterceptorTest {

	@Test
	void testSuccessOnFirstAttempt() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(100).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			attemptCount.incrementAndGet();
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Success"));
		};

		ModelRequest request = ModelRequest.builder().build();
		ModelResponse response = interceptor.interceptModel(request, handler);

		assertEquals(1, attemptCount.get(), "应该只调用一次");
		assertEquals("Success", response.getMessage().text());
	}

	@Test
	void testSuccessOnSecondAttempt() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder()
			.maxAttempts(3)
			.initialDelay(100)
			.backoffMultiplier(1.5)
			.build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			int count = attemptCount.incrementAndGet();
			if (count == 1) {
				throw new RuntimeException("I/O error on POST request");
			}
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Success on retry"));
		};

		ModelRequest request = ModelRequest.builder().build();
		ModelResponse response = interceptor.interceptModel(request, handler);

		assertEquals(2, attemptCount.get(), "应该调用两次（首次失败，第二次成功）");
		assertEquals("Success on retry", response.getMessage().text());
	}

	@Test
	void testMaxAttemptsReached() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(50).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			attemptCount.incrementAndGet();
			throw new RuntimeException("I/O error: connection timeout");
		};

		ModelRequest request = ModelRequest.builder().build();

		RuntimeException exception = assertThrows(RuntimeException.class, () -> {
			interceptor.interceptModel(request, handler);
		});

		assertEquals(3, attemptCount.get(), "应该尝试3次");
		assertTrue(exception.getMessage().contains("maximum number of retries reached"));
	}

	@Test
	void testNonRetryableException() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(50).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			attemptCount.incrementAndGet();
			throw new RuntimeException("Authentication failed");
		};

		ModelRequest request = ModelRequest.builder().build();

		RuntimeException exception = assertThrows(RuntimeException.class, () -> {
			interceptor.interceptModel(request, handler);
		});

		assertEquals(1, attemptCount.get(), "不可重试的异常应该只尝试一次");
		assertTrue(exception.getMessage().contains("non-retryable exception"));
	}

	@Test
	void testRepeatedConnectionFailuresAreRetried() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(50).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			int count = attemptCount.incrementAndGet();
			if (count < 3) {
				throw new IllegalStateException("I/O error: Remote host terminated the handshake");
			}
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Success after retry"));
		};

		ModelRequest request = ModelRequest.builder().build();
		ModelResponse response = interceptor.interceptModel(request, handler);

		assertEquals(3, attemptCount.get(), "应该重试直到成功");
		assertEquals("Success after retry", response.getMessage().text());
	}

	@Test
	void testStreamingSuccessOnSecondAttempt() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			int count = attemptCount.incrementAndGet();
			if (count == 1) {
				return streaming(Flux.error(new RuntimeException("I/O error on streaming response")));
			}
			return streaming(Flux.just(chatResponse("Success after streaming retry")));
		};

		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(), handler);

		assertTrue(response.isStreaming());
		Flux<ChatEvent> responseFlux = JdkFlowAdapter.flowPublisherToFlux(response.getStream());
		List<ChatEvent> responses = responseFlux.collectList().block(Duration.ofSeconds(2));

		assertEquals(2, attemptCount.get(), "应该重试流式模型调用");
		assertNotNull(responses);
		assertEquals("Success after streaming retry", responses.get(0).response().text());
	}

	@Test
	void testStreamingStartupExceptionRetry() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			int count = attemptCount.incrementAndGet();
			if (count == 1) {
				throw new IllegalStateException("I/O error before streaming starts");
			}
			return streaming(Flux.just(chatResponse("Streaming recovered")));
		};

		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(), handler);

		assertTrue(response.isStreaming());
		Flux<ChatEvent> responseFlux = JdkFlowAdapter.flowPublisherToFlux(response.getStream());
		List<ChatEvent> responses = responseFlux.collectList().block(Duration.ofSeconds(2));

		assertEquals(2, attemptCount.get(), "应该重试流式启动阶段异常");
		assertNotNull(responses);
		assertEquals("Streaming recovered", responses.get(0).response().text());
	}

	@Test
	void testStreamingRetryHandlesStartupFailureOnNextAttempt() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			int count = attemptCount.incrementAndGet();
			if (count == 1) {
				return streaming(Flux.error(new RuntimeException("I/O error on streaming response")));
			}
			if (count == 2) {
				throw new IllegalStateException("connection reset before streaming starts");
			}
			return streaming(Flux.just(chatResponse("Recovered after connection reset")));
		};

		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(), handler);

		assertTrue(response.isStreaming());
		Flux<ChatEvent> responseFlux = JdkFlowAdapter.flowPublisherToFlux(response.getStream());
		List<ChatEvent> responses = responseFlux.collectList().block(Duration.ofSeconds(2));

		assertEquals(3, attemptCount.get(), "Startup failures during a streaming retry must use the same retry policy");
		assertNotNull(responses);
		assertEquals("Recovered after connection reset", responses.get(0).response().text());
	}

	@Test
	void testStreamingNonRetryableException() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			attemptCount.incrementAndGet();
			return streaming(Flux.error(new RuntimeException("Authentication failed")));
		};

		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(), handler);

		assertTrue(response.isStreaming());
		Flux<ChatEvent> responseFlux = JdkFlowAdapter.flowPublisherToFlux(response.getStream());

		RuntimeException exception = assertThrows(RuntimeException.class,
				() -> responseFlux.collectList().block(Duration.ofSeconds(2)));
		assertEquals(1, attemptCount.get(), "不可重试的流式异常应该只尝试一次");
		assertTrue(exception.getMessage().contains("non-retryable exception"));
	}

	@Test
	void testStreamingErrorAfterFirstChunkIsNotRetried() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			attemptCount.incrementAndGet();
			return streaming(Flux.just(chatResponse("partial response"))
				.concatWith(Flux.error(new RuntimeException("I/O error after streaming started"))));
		};

		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(), handler);

		assertTrue(response.isStreaming());
		Flux<ChatEvent> responseFlux = JdkFlowAdapter.flowPublisherToFlux(response.getStream());
		List<ChatEvent> emittedResponses = new ArrayList<>();

		RuntimeException exception = assertThrows(RuntimeException.class,
				() -> responseFlux.doOnNext(emittedResponses::add).collectList().block(Duration.ofSeconds(2)));
		assertEquals(1, attemptCount.get(), "已经输出部分流式结果后不应该重试，避免重复输出");
		assertEquals(1, emittedResponses.size());
		assertEquals("partial response", emittedResponses.get(0).response().text());
		assertTrue(exception.getMessage().contains("I/O error after streaming started"));
	}

	@Test
	void testExponentialBackoff() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder()
			.maxAttempts(3)
			.initialDelay(0)
			.maxDelay(500)
			.backoffMultiplier(2.0)
			.build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			int count = attemptCount.incrementAndGet();
			if (count < 3) {
				throw new RuntimeException("Connection timeout");
			}
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Success"));
		};

		ModelRequest request = ModelRequest.builder().build();
		ModelResponse response = interceptor.interceptModel(request, handler);

		assertEquals(3, attemptCount.get());
		assertEquals("Success", response.getMessage().text());
	}

	@Test
	void testCustomRetryablePredicate() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder()
			.maxAttempts(3)
			.initialDelay(50)
			.retryableExceptionPredicate(e -> e.getMessage().contains("custom-retry"))
			.build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		// Test custom retryable exceptions
		ModelCallHandler handler1 = req -> {
			attemptCount.incrementAndGet();
			throw new RuntimeException("custom-retry error");
		};

		ModelRequest request = ModelRequest.builder().build();

		assertThrows(RuntimeException.class, () -> {
			interceptor.interceptModel(request, handler1);
		});
		assertEquals(3, attemptCount.get(), "自定义可重试异常应该重试");

		// Test cannot be retried exception
		attemptCount.set(0);
		ModelCallHandler handler2 = req -> {
			attemptCount.incrementAndGet();
			throw new RuntimeException("other error");
		};

		assertThrows(RuntimeException.class, () -> {
			interceptor.interceptModel(request, handler2);
		});
		assertEquals(1, attemptCount.get(), "非自定义可重试异常应该不重试");
	}

	@Test
	void testZeroDelay() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();

		AtomicInteger attemptCount = new AtomicInteger(0);

		ModelCallHandler handler = request -> {
			int count = attemptCount.incrementAndGet();
			if (count < 3) {
				throw new RuntimeException("I/O error");
			}
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Success"));
		};

		ModelRequest request = ModelRequest.builder().build();
		interceptor.interceptModel(request, handler);

		assertEquals(3, attemptCount.get());
	}

	@Test
	void testBuilderValidation() {
		assertThrows(IllegalArgumentException.class, () -> {
			ModelRetryInterceptor.builder().maxAttempts(0).build();
		}, "maxAttempts 必须 >= 1");

		assertThrows(IllegalArgumentException.class, () -> {
			ModelRetryInterceptor.builder().initialDelay(-1).build();
		}, "initialDelay 必须 >= 0");

		assertThrows(IllegalArgumentException.class, () -> {
			ModelRetryInterceptor.builder().maxDelay(-1).build();
		}, "maxDelay 必须 >= 0");

		assertThrows(IllegalArgumentException.class, () -> {
			ModelRetryInterceptor.builder().backoffMultiplier(0.5).build();
		}, "backoffMultiplier 必须 >= 1.0");
	}

	private static ModelResponse streaming(Flux<ChatEvent> events) {
		return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(events));
	}

	private ChatEvent chatResponse(String text) {
		return ChatEvent.response(ModelResponse.of(ChatMessage.assistant(text)).getChatResponse());
	}

}
