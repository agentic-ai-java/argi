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

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.modelretry.ModelRetryInterceptor;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRetryCancellationTest {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cancellationIsNeverRetriedOrWrapped(boolean streaming) {
		var cancellation = new CancellationException("cancelled");
		var attempts = new AtomicInteger();
		ModelCallHandler handler = request -> {
			attempts.incrementAndGet();
			if (streaming) {
				return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(Flux.error(cancellation)));
			}
			throw cancellation;
		};
		if (streaming) {
			StepVerifier.create(stream(retry(0).interceptModel(request(), handler)))
				.expectErrorMatches(error -> error == cancellation)
				.verify(Duration.ofSeconds(2));
		}
		else {
			assertThatThrownBy(() -> retry(0).interceptModel(request(), handler)).isSameAs(cancellation);
		}
		assertThat(attempts).hasValue(1);
	}

	@Test
	void anAlreadyInterruptedThreadCannotStartAModelCall() {
		var attempts = new AtomicInteger();
		try {
			Thread.currentThread().interrupt();
			assertThatThrownBy(() -> retry(0).interceptModel(request(), ignored -> {
				attempts.incrementAndGet();
				return ModelResponse.of(ChatMessage.assistant("wrong"));
			})).isInstanceOf(CancellationException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(attempts).hasValue(0);
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void interruptionDuringBackoffStopsRetriesAndPreservesCauseAndFlag() throws Exception {
		var called = new CountDownLatch(1);
		var attempts = new AtomicInteger();
		var interrupted = new AtomicBoolean();
		var completion = new CompletableFuture<Throwable>();
		var worker = new Thread(() -> {
			try {
				retry(30000).interceptModel(request(), ignored -> {
					attempts.incrementAndGet();
					called.countDown();
					throw new IllegalStateException("connection lost");
				});
				completion.complete(new AssertionError("Retry should have been cancelled"));
			}
			catch (Throwable failure) {
				interrupted.set(Thread.currentThread().isInterrupted());
				completion.complete(failure);
			}
		});
		worker.start();
		try {
			assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (worker.getState() != Thread.State.TIMED_WAITING && worker.isAlive()
					&& System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			assertThat(worker.getState()).isEqualTo(Thread.State.TIMED_WAITING);
			worker.interrupt();
			assertThat(completion.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class)
				.hasCauseInstanceOf(InterruptedException.class);
			assertThat(attempts).hasValue(1);
			assertThat(interrupted).isTrue();
		}
		finally {
			worker.interrupt();
			worker.join(2000);
			assertThat(worker.isAlive()).isFalse();
		}
	}

	@Test
	void cancellingAStreamingBackoffDoesNotDispatchAnotherAttempt() {
		var attempts = new AtomicInteger();
		StepVerifier.withVirtualTime(() -> stream(retry(30000).interceptModel(request(), ignored -> {
			attempts.incrementAndGet();
			return ModelResponse.stream(
					JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("connection lost"))));
		}))).thenAwait(Duration.ofMillis(100)).thenCancel().verify(Duration.ofSeconds(2));
		assertThat(attempts).hasValue(1);
	}

	@Test
	void partialOutputFromOneSubscriptionCannotDisableRetryForAnother() {
		var subscriptions = new AtomicInteger();
		var attempts = new AtomicInteger();
		var initial = Flux.defer(() -> subscriptions.incrementAndGet() == 1
				? Flux.concat(Flux.just(ChatEvent.textDelta("partial")),
						Flux.error(new IllegalStateException("connection lost")))
				: Flux.<ChatEvent>error(new IllegalStateException("connection lost")));
		var response = retry(0).interceptModel(request(),
				ignored -> attempts.incrementAndGet() == 1
						? ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(initial))
						: ModelResponse.of(ChatMessage.assistant("recovered")));

		StepVerifier.create(stream(response))
			.expectNext(ChatEvent.textDelta("partial"))
			.expectErrorMessage("connection lost")
			.verify(Duration.ofSeconds(2));
		StepVerifier.create(stream(response))
			.assertNext(event -> assertThat(event.response().text()).isEqualTo("recovered"))
			.verifyComplete();
		assertThat(attempts).hasValue(2);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void assistantExceptionTextCannotTriggerRetry(boolean afterStreamFailure) {
		var attempts = new AtomicInteger();
		var text = "Exception: connection timeout is an example error message";
		var response = retry(0).interceptModel(request(), ignored -> {
			if (attempts.incrementAndGet() == 1 && afterStreamFailure) {
				return ModelResponse.stream(JdkFlowAdapter
					.publisherToFlowPublisher(Flux.error(new IllegalStateException("connection lost"))));
			}
			return ModelResponse.of(ChatMessage.assistant(text));
		});

		if (afterStreamFailure) {
			StepVerifier.create(stream(response))
				.assertNext(event -> assertThat(event.response().text()).isEqualTo(text))
				.verifyComplete();
		}
		else {
			assertThat(response.getMessage().text()).isEqualTo(text);
		}
		assertThat(attempts).hasValue(afterStreamFailure ? 2 : 1);
	}

	@Test
	void builderRejectsInvalidRetryPolicy() {
		assertThatThrownBy(() -> ModelRetryInterceptor.builder().backoffMultiplier(Double.NaN))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ModelRetryInterceptor.builder().backoffMultiplier(Double.POSITIVE_INFINITY))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ModelRetryInterceptor.builder().retryableExceptionPredicate(null))
			.isInstanceOf(NullPointerException.class);
	}

	private ModelRetryInterceptor retry(long delay) {
		return ModelRetryInterceptor.builder().initialDelay(delay).retryableExceptionPredicate(ignored -> true).build();
	}

	private ModelRequest request() {
		return ModelRequest.builder().build();
	}

	private Flux<ChatEvent> stream(ModelResponse response) {
		return JdkFlowAdapter.flowPublisherToFlux(response.getStream());
	}

}
