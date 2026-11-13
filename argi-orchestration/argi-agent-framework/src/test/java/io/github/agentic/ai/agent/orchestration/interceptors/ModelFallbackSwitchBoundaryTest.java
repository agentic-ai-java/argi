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
package io.github.agentic.ai.agent.orchestration.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.orchestration.interceptor.modelfallback.ModelFallbackInterceptor;
import io.github.agentic.ai.agent.orchestration.interceptor.modelretry.ModelRetryInterceptor;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Boundary behaviour of {@link ModelFallbackInterceptor}: a cancellation, however it is
 * wrapped, must terminate the sequence instead of dispatching another provider call
 * (issue #210).
 */
class ModelFallbackSwitchBoundaryTest {

	@Test
	void wrappedCancellationNeverAttemptsAnotherClient() {
		StubClient fallback = new StubClient("wrong");
		CancellationException cancelled = new CancellationException("stop");
		List<Object> attempts = new ArrayList<>();
		assertThatThrownBy(() -> interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			throw new CompletionException(cancelled);
		})).isSameAs(cancelled);
		assertThat(attempts).hasSize(1);
		assertThat(fallback.requests).isEmpty();
	}

	@Test
	void executionExceptionWrappedCancellationNeverAttemptsAnotherClient() {
		StubClient fallback = new StubClient("wrong");
		CancellationException cancelled = new CancellationException("stop");
		List<Object> attempts = new ArrayList<>();
		assertThatThrownBy(() -> interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			// ExecutionException is checked, so it rides inside a runtime wrapper: the
			// cause chain still has to be traversed to find the cancellation.
			throw new RuntimeException(new ExecutionException("task aborted", cancelled));
		})).isSameAs(cancelled);
		assertThat(attempts).hasSize(1);
	}

	@Test
	void deeplyWrappedCancellationUnwrapsToTheOriginalCancellation() {
		StubClient fallback = new StubClient("wrong");
		CancellationException cancelled = new CancellationException("stop");
		List<Object> attempts = new ArrayList<>();
		assertThatThrownBy(() -> interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			throw new RuntimeException(new CompletionException(new RuntimeException(cancelled)));
		})).isSameAs(cancelled);
		assertThat(attempts).hasSize(1);
	}

	@Test
	void streamingFailureBeforeFirstEventSwitchesToFallback() {
		StubClient fallback = new StubClient("fallback");
		RuntimeException primaryFailure = new IllegalStateException("primary stream failed");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			if (request.getChatClient() == fallback) {
				return ModelResponse.of(ChatMessage.assistant("fallback"));
			}
			return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(Flux.error(primaryFailure)));
		});

		assertThat(result.isStreaming()).isTrue();
		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> event.type() == ChatEvent.Type.RESPONSE
					&& "fallback".equals(event.response().text()))
			.expectComplete()
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).hasSize(2);
	}

	@Test
	void streamingFailureAfterFirstEventTerminatesWithoutSwitching() {
		StubClient fallback = new StubClient("fallback");
		RuntimeException failure = new IllegalStateException("stream died mid-output");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
					Flux.concat(Flux.just(ChatEvent.textDelta("partial ")), Flux.error(failure))));
		});

		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> "partial ".equals(event.text()))
			.expectErrorMatches(error -> error == failure)
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).as("a stream that already emitted output must not be switched mid-output").hasSize(1);
	}

	@Test
	void streamingCancellationBeforeFirstEventNeverSwitches() {
		StubClient fallback = new StubClient("fallback");
		CancellationException cancelled = new CancellationException("stop");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
					Flux.error(new CompletionException(cancelled))));
		});

		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectErrorMatches(error -> error == cancelled)
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).hasSize(1);
	}

	@Test
	void usageAndToolCallEventsCountAsEmittedOutput() {
		StubClient fallback = new StubClient("fallback");
		RuntimeException failure = new IllegalStateException("died after usage");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
					Flux.concat(Flux.just(ChatEvent.usage(new Usage(1L, 2L, 3L))), Flux.error(failure))));
		});

		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> event.usage() != null)
			.expectErrorMatches(error -> error == failure)
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).as("a usage event is observable output, so no switch may follow").hasSize(1);
	}

	@Test
	void deadRunGuardIsTerminalForBothPaths() {
		StubClient first = new StubClient("first");
		StubClient second = new StubClient("second");
		List<Object> attempts = new ArrayList<>();
		MutableGuard guard = new MutableGuard();

		// Synchronous path: the run dies while the primary attempt is in flight, so no
		// fallback client may be dispatched after that.
		assertThatThrownBy(() -> ModelFallbackInterceptor.builder()
			.addFallbackClient(first)
			.addFallbackClient(second)
			.build()
			.interceptModel(ModelRequest.builder().executionGuard(guard).build(), request -> {
				attempts.add(request.getChatClient());
				guard.die();
				throw new IllegalStateException("primary failed");
			})).isSameAs(guard.stopped());
		assertThat(attempts).hasSize(1);

		// Streaming path: the run is alive at subscription entry and dies before the
		// switch, so the switch itself must refuse to dispatch the next candidate.
		guard.revive();
		attempts.clear();
		ModelResponse streaming = ModelFallbackInterceptor.builder()
			.addFallbackClient(first)
			.addFallbackClient(second)
			.build()
			.interceptModel(ModelRequest.builder().executionGuard(guard).build(), request -> {
				attempts.add(request.getChatClient());
				return ModelResponse.stream(
						JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("boom"))));
			});
		guard.die();
		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(streaming.getStream()))
			.expectErrorMatches(error -> error == guard.stopped())
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).as("the streaming switch must not dispatch a candidate on a dead run").hasSize(1);
	}

	@Test
	void deadGuardAtEntryDispatchesNoProviderCall() {
		StubClient fallback = new StubClient("fallback");
		MutableGuard guard = new MutableGuard();
		guard.die();
		List<Object> attempts = new ArrayList<>();

		assertThatThrownBy(() -> interceptor(fallback).interceptModel(ModelRequest.builder().executionGuard(guard)
			.build(), request -> {
			attempts.add(request.getChatClient());
			return ModelResponse.of(ChatMessage.assistant("never"));
		})).isSameAs(guard.stopped());
		assertThat(attempts).as("a dead run must not pay for a primary call either").isEmpty();
	}

	@Test
	void eachSubscriptionOwnsItsSwitchSequence() {
		StubClient fallback = new StubClient("fallback");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			if (request.getChatClient() == fallback) {
				return ModelResponse.of(ChatMessage.assistant("fallback"));
			}
			return ModelResponse.stream(
					JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("primary died"))));
		});

		Flux<ChatEvent> stream = JdkFlowAdapter.flowPublisherToFlux(result.getStream());
		for (int subscription = 1; subscription <= 2; subscription++) {
			StepVerifier.create(stream)
				.expectNextMatches(event -> event.type() == ChatEvent.Type.RESPONSE
						&& "fallback".equals(event.response().text()))
				.expectComplete()
				.verify(Duration.ofSeconds(2));
		}
		// The primary handler call happens once in interceptModel; a repeated
		// subscription replays the captured publisher and owns fresh switch state, so
		// the second subscription walks the same primary failure and switches again,
		// mirroring the per-subscription attempt budget of the retry interceptor.
		assertThat(attempts).containsExactly(null, fallback, fallback);
	}

	@Test
	void streamingCandidateFailureAdvancesToTheNextCandidate() {
		StubClient second = new StubClient("second");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = ModelFallbackInterceptor.builder()
			.addFallbackClient(new StubClient("unused-by-handler"))
			.addFallbackClient(second)
			.build()
			.interceptModel(ModelRequest.builder().build(), request -> {
				attempts.add(request.getChatClient());
				if (request.getChatClient() == second) {
					return ModelResponse.of(ChatMessage.assistant("second"));
				}
				// Primary and the first fallback candidate both fail their stream
				// before emitting any event.
				return ModelResponse.stream(
						JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("died"))));
			});

		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> event.type() == ChatEvent.Type.RESPONSE
					&& "second".equals(event.response().text()))
			.expectComplete()
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).hasSize(3);
	}

	@Test
	void synchronousFailureDuringAStreamingSwitchAdvancesToTheNextCandidate() {
		StubClient second = new StubClient("second");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = ModelFallbackInterceptor.builder()
			.addFallbackClient(new StubClient("unused-by-handler"))
			.addFallbackClient(second)
			.build()
			.interceptModel(ModelRequest.builder().build(), request -> {
				attempts.add(request.getChatClient());
				if (request.getChatClient() == null) {
					// The primary fails its stream before any event is emitted.
					return ModelResponse.stream(
							JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("died"))));
				}
				if (request.getChatClient() == second) {
					return ModelResponse.of(ChatMessage.assistant("second"));
				}
				// The first fallback candidate fails synchronously: the call itself is
				// constructed (or rejected) without ever producing a stream.
				throw new IllegalStateException("call construction failed");
			});

		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> event.type() == ChatEvent.Type.RESPONSE
					&& "second".equals(event.response().text()))
			.expectComplete()
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).hasSize(3);
	}

	@Test
	void recommendedOrderRetriesEachModelBeforeFailingOver() {
		StubClient fallback = new StubClient("fallback");
		List<String> modelCalls = new ArrayList<>();
		AtomicInteger primaryAttempts = new AtomicInteger();
		ModelFallbackInterceptor fallbackInterceptor = ModelFallbackInterceptor.builder()
			.addFallbackClient(fallback)
			.build();
		ModelRetryInterceptor retryInterceptor = ModelRetryInterceptor.builder()
			.maxAttempts(2)
			.initialDelay(1)
			// The composition is under test, not the default message-based
			// classification of retryable failures.
			.retryableExceptionPredicate(failure -> true)
			.build();
		// The agent applies the interceptor list outermost-first, so registering the
		// fallback before the retry gives "retry each model, then fail over".
		ModelCallHandler model = request -> {
			modelCalls.add(String.valueOf(request.getChatClient()));
			if (request.getChatClient() == fallback) {
				return ModelResponse.of(ChatMessage.assistant("fallback"));
			}
			primaryAttempts.incrementAndGet();
			return ModelResponse.stream(
					JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("primary died"))));
		};
		ModelCallHandler chain = request -> retryInterceptor.interceptModel(request, model);

		ModelResponse result = fallbackInterceptor.interceptModel(ModelRequest.builder().build(), chain);
		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> event.type() == ChatEvent.Type.RESPONSE
					&& "fallback".equals(event.response().text()))
			.expectComplete()
			.verify(Duration.ofSeconds(5));

		assertThat(primaryAttempts).as("the inner retry exhausts the primary before the failover").hasValue(2);
		assertThat(modelCalls).hasSize(3);
	}

	@Test
	void cancellationAfterFirstEventKeepsItsUnwrappedIdentity() {
		StubClient fallback = new StubClient("fallback");
		CancellationException cancelled = new CancellationException("stop");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
					Flux.concat(Flux.just(ChatEvent.textDelta("partial ")),
							Flux.error(new CompletionException(cancelled)))));
		});

		// Before or after the first event, the same wrapped cancellation surfaces as
		// the same bare cancellation, so downstream cancellation handling cannot
		// misclassify it as a model failure by testing the top-level type only.
		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> "partial ".equals(event.text()))
			.expectErrorMatches(error -> error == cancelled)
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).hasSize(1);
	}

	@Test
	void toolCallEventCountsAsEmittedOutput() {
		StubClient fallback = new StubClient("fallback");
		RuntimeException failure = new IllegalStateException("died after a tool call");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = interceptor(fallback).interceptModel(ModelRequest.builder().build(), request -> {
			attempts.add(request.getChatClient());
			return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
					Flux.concat(Flux.just(ChatEvent.toolCall(new ToolCall("call-1", "lookup", "{}"))),
							Flux.error(failure))));
		});

		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> event.type() == ChatEvent.Type.TOOL_CALL)
			.expectErrorMatches(error -> error == failure)
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).as("a delivered tool call is observable output, so no switch may follow").hasSize(1);
	}

	@Test
	void postEventFailureAtAFallbackCandidateDoesNotAdvanceFurther() {
		StubClient second = new StubClient("second");
		RuntimeException failure = new IllegalStateException("fallback stream died mid-output");
		List<Object> attempts = new ArrayList<>();
		ModelResponse result = ModelFallbackInterceptor.builder()
			.addFallbackClient(new StubClient("unused-by-handler"))
			.addFallbackClient(second)
			.build()
			.interceptModel(ModelRequest.builder().build(), request -> {
				attempts.add(request.getChatClient());
				if (request.getChatClient() == null) {
					return ModelResponse.stream(
							JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("died"))));
				}
				// The first fallback candidate emits output and then fails.
				return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
						Flux.concat(Flux.just(ChatEvent.textDelta("fallback partial ")), Flux.error(failure))));
			});

		StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(result.getStream()))
			.expectNextMatches(event -> "fallback partial ".equals(event.text()))
			.expectErrorMatches(error -> error == failure)
			.verify(Duration.ofSeconds(2));
		assertThat(attempts).as("the boundary applies per candidate, not only to the primary").hasSize(2);
	}

	@Test
	void wrappedCancellationAtAFallbackAttemptStopsTheSequence() {
		StubClient first = new StubClient("first");
		StubClient second = new StubClient("second");
		CancellationException cancelled = new CancellationException("stop");
		List<Object> attempts = new ArrayList<>();
		assertThatThrownBy(() -> ModelFallbackInterceptor.builder()
			.addFallbackClient(first)
			.addFallbackClient(second)
			.build()
			.interceptModel(ModelRequest.builder().build(), request -> {
				attempts.add(request.getChatClient());
				if (request.getChatClient() == null) {
					throw new IllegalStateException("primary failed");
				}
				if (request.getChatClient() == first) {
					throw new CompletionException(cancelled);
				}
				return ModelResponse.of(ChatMessage.assistant("second"));
			})).isSameAs(cancelled);
		assertThat(attempts).containsExactly(null, first);
	}

	private static ModelFallbackInterceptor interceptor(ChatClient fallback) {
		return ModelFallbackInterceptor.builder().addFallbackClient(fallback).build();
	}

	/** A run guard the test can kill and revive to place the run's death precisely. */
	private static final class MutableGuard implements ExecutionGuard {

		private final AtomicBoolean active = new AtomicBoolean(true);

		private final CancellationException stopped = new CancellationException("run stopped");

		void die() {
			active.set(false);
		}

		void revive() {
			active.set(true);
		}

		CancellationException stopped() {
			return stopped;
		}

		@Override
		public void assertActive() {
			if (!active.get()) {
				throw stopped;
			}
		}

		@Override
		public AutoCloseable onLoss(Runnable cancellation) {
			return () -> {
			};
		}

	}

	private static final class StubClient implements ChatClient {

		private final String text;

		private final List<ChatRequest> requests = new ArrayList<>();

		private StubClient(String text) {
			this.text = text;
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			requests.add(request);
			return CompletableFuture.completedFuture(new ChatResponse("id", "test-model",
					List.of(ChatMessage.assistant(text)), List.of(), null, new Usage(2L, 3L, 5L), Map.of()));
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
