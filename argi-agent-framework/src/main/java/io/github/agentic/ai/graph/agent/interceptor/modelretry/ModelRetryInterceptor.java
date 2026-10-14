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
package io.github.agentic.ai.graph.agent.interceptor.modelretry;

import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;

import io.github.agentic.ai.api.chat.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import reactor.core.publisher.Flux;
import reactor.adapter.JdkFlowAdapter;

/**
 * The model calls a retry interceptor to handle retryable exceptions such as network
 * errors. When a model call fails, it will be retried according to the configured retry
 * policy until it succeeds or the maximum number of retries is reached. Example: <pre>
 * ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder()
 *     .maxAttempts(3)
 *     .initialDelay(1000)
 *     .maxDelay(10000)
 *     .backoffMultiplier(2.0)
 *     .build();
 * </pre>
 */
public class ModelRetryInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ModelRetryInterceptor.class);

	private final int maxAttempts;

	private final long initialDelay;

	private final long maxDelay;

	private final double backoffMultiplier;

	private final Predicate<Exception> retryableExceptionPredicate;

	private ModelRetryInterceptor(Builder builder) {
		this.maxAttempts = builder.maxAttempts;
		this.initialDelay = builder.initialDelay;
		this.maxDelay = builder.maxDelay;
		this.backoffMultiplier = builder.backoffMultiplier;
		this.retryableExceptionPredicate = builder.retryableExceptionPredicate;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		Exception lastException = null;
		long currentDelay = initialDelay;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				checkInterrupted();
				if (attempt > 1) {
					log.info("Retry model call, on the {}th attempt (out of {} attempts).", attempt, maxAttempts);
				}

				ModelResponse modelResponse = handler.call(request);
				checkInterrupted();
				if (modelResponse.isStreaming()) {
					return ModelResponse
						.stream(JdkFlowAdapter.publisherToFlowPublisher(withStreamingRetry(request, handler,
								JdkFlowAdapter.flowPublisherToFlux(modelResponse.getStream()), attempt, currentDelay)));
				}
				// Assistant content is data, never an out-of-band error signal.
				if (attempt > 1) {
					log.info("The model call succeeded after the {}th attempt.", attempt);
				}
				return modelResponse;

			}
			catch (Exception e) {
				CancellationException cancellation = cancellation(e);
				if (cancellation != null) {
					throw cancellation;
				}
				lastException = e;
				log.warn("Model call failed (attempted {}/{}): {}", attempt, maxAttempts, e.getMessage());

				if (attempt >= maxAttempts) {
					log.error("The maximum number of retries has been reached {}, and the model call has failed.",
							maxAttempts);
					throw new RuntimeException("Model call failed, maximum number of retries reached.", lastException);
				}

				// Determine if an exception can be retried.
				if (!retryableExceptionPredicate.test(e)) {
					log.warn("Exceptions cannot be retried and are thrown immediately: {}", e.getMessage());
					throw new RuntimeException("Model call failed (non-retryable exception)", e);
				}

				// Wait and try again
				pause(currentDelay);

				// Calculate the next delay time (exponential backoff)
				currentDelay = RetryDelayCalculator.nextDelay(currentDelay, backoffMultiplier, maxDelay);
			}
		}

		// All retries failed.
		throw new RuntimeException("Model call failed, maximum number of retries reached. " + maxAttempts,
				lastException);
	}

	private Flux<ChatEvent> withStreamingRetry(ModelRequest request, ModelCallHandler handler,
			Flux<ChatEvent> responseFlux, int attempt, long currentDelay) {
		return Flux.defer(() -> {
			CancellationException cancellation = cancellation(null);
			if (cancellation != null) {
				return Flux.error(cancellation);
			}
			AtomicBoolean chunkEmitted = new AtomicBoolean(false);
			return responseFlux.doOnNext(response -> chunkEmitted.set(true)).onErrorResume(error -> {
				if (chunkEmitted.get()) {
					// Retrying after partial output would duplicate chunks downstream.
					return Flux.error(error);
				}
				return retryStreamingModelCall(request, handler, attempt, currentDelay, error);
			});
		});
	}

	private Flux<ChatEvent> retryStreamingModelCall(ModelRequest request, ModelCallHandler handler, int failedAttempt,
			long currentDelay, Throwable error) {
		if (error instanceof Error) {
			return Flux.error(error);
		}
		CancellationException cancellation = cancellation(error);
		if (cancellation != null) {
			return Flux.error(cancellation);
		}
		Exception exception = toException(error);
		log.warn("Streaming model call failed (attempted {}/{}): {}", failedAttempt, maxAttempts,
				exception.getMessage());

		if (failedAttempt >= maxAttempts) {
			log.error("The maximum number of retries has been reached {}, and the streaming model call has failed.",
					maxAttempts);
			return Flux.error(new RuntimeException("Model call failed, maximum number of retries reached.", exception));
		}

		if (!retryableExceptionPredicate.test(exception)) {
			log.warn("Exceptions cannot be retried and are thrown immediately: {}", exception.getMessage());
			return Flux.error(new RuntimeException("Model call failed (non-retryable exception)", exception));
		}

		int nextAttempt = failedAttempt + 1;
		long nextDelay = nextDelay(currentDelay);
		return delay(currentDelay).thenMany(Flux.defer(() -> {
			log.info("Retry model call, on the {}th attempt (out of {} attempts).", nextAttempt, maxAttempts);
			try {
				checkInterrupted();
				ModelResponse retryResponse = handler.call(request);
				return toStreamingFlux(request, handler, retryResponse, nextAttempt, nextDelay);
			}
			catch (Exception retryError) {
				return retryStreamingModelCall(request, handler, nextAttempt, nextDelay, retryError);
			}
		}));
	}

	private void pause(long delay) {
		checkInterrupted();
		if (delay <= 0) {
			return;
		}
		try {
			log.info("Retry after {} ms", delay);
			Thread.sleep(delay);
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw cancellation(failure);
		}
	}

	private void checkInterrupted() {
		CancellationException cancellation = cancellation(null);
		if (cancellation != null) {
			throw cancellation;
		}
	}

	private CancellationException cancellation(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof CancellationException cancelled) {
				return cancelled;
			}
			if (cause instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (!Thread.currentThread().isInterrupted()) {
			return null;
		}
		CancellationException cancelled = new CancellationException("Model retry interrupted");
		if (failure != null) {
			cancelled.initCause(failure);
		}
		return cancelled;
	}

	private Flux<ChatEvent> toStreamingFlux(ModelRequest request, ModelCallHandler handler, ModelResponse modelResponse,
			int attempt, long currentDelay) {
		if (modelResponse.isStreaming()) {
			return withStreamingRetry(request, handler, JdkFlowAdapter.flowPublisherToFlux(modelResponse.getStream()),
					attempt, currentDelay);
		}
		return Flux.just(ChatEvent.response(modelResponse.getChatResponse()));
	}

	private Flux<ChatEvent> delay(long currentDelay) {
		if (currentDelay <= 0) {
			return Flux.empty();
		}
		return Flux.<ChatEvent>empty().delaySubscription(Duration.ofMillis(currentDelay));
	}

	private long nextDelay(long currentDelay) {
		return RetryDelayCalculator.nextDelay(currentDelay, backoffMultiplier, maxDelay);
	}

	private Exception toException(Throwable error) {
		if (error instanceof Exception exception) {
			return exception;
		}
		return new RuntimeException(error);
	}

	@Override
	public String getName() {
		return "ModelRetry";
	}

	public static class Builder {

		private int maxAttempts = 3;

		private long initialDelay = 1000;

		private long maxDelay = 30000;

		private double backoffMultiplier = 2.0;

		private Predicate<Exception> retryableExceptionPredicate = Builder::isRetryableException;

		/**
		 * Set the maximum number of retries (including the first call).
		 * @param maxAttempts The maximum number of attempts must be >= 1.
		 */
		public Builder maxAttempts(int maxAttempts) {
			if (maxAttempts < 1) {
				throw new IllegalArgumentException("maxAttempts must be greater than or equal to 1");
			}
			this.maxAttempts = maxAttempts;
			return this;
		}

		/**
		 * Set the initial retry delay (milliseconds).
		 * @param initialDelay Initial delay time, in milliseconds
		 */
		public Builder initialDelay(long initialDelay) {
			if (initialDelay < 0) {
				throw new IllegalArgumentException("initialDelay must be greater than or equal to 0.");
			}
			this.initialDelay = initialDelay;
			return this;
		}

		/**
		 * Set the maximum retry delay (milliseconds).
		 * @param maxDelay Maximum delay time, in milliseconds
		 */
		public Builder maxDelay(long maxDelay) {
			if (maxDelay < 0) {
				throw new IllegalArgumentException("maxDelay must be greater than or equal to 0.");
			}
			this.maxDelay = maxDelay;
			return this;
		}

		/**
		 * Set the backoff factor (the multiplier for the delay time on each retry).
		 * @param backoffMultiplier The retreat factor must be >= 1.0
		 */
		public Builder backoffMultiplier(double backoffMultiplier) {
			if (!Double.isFinite(backoffMultiplier) || backoffMultiplier < 1.0) {
				throw new IllegalArgumentException("The backoffMultiplier must be >= 1.0");
			}
			this.backoffMultiplier = backoffMultiplier;
			return this;
		}

		/**
		 * Configure custom retryable exception handling logic
		 * @param predicate Exception detection function
		 */
		public Builder retryableExceptionPredicate(Predicate<Exception> predicate) {
			this.retryableExceptionPredicate = Objects.requireNonNull(predicate, "predicate must not be null");
			return this;
		}

		public ModelRetryInterceptor build() {
			return new ModelRetryInterceptor(this);
		}

		/**
		 * Default retryable exception handling logic
		 */
		private static boolean isRetryableException(Exception e) {
			String message = e.getMessage();
			if (message == null) {
				return false;
			}

			String lowerMessage = message.toLowerCase();

			// Network-related exceptions
			if (lowerMessage.contains("i/o error") || lowerMessage.contains("remote host terminated")
					|| lowerMessage.contains("connection") || lowerMessage.contains("timeout")
					|| lowerMessage.contains("handshake") || lowerMessage.contains("socket")) {
				return true;
			}

			// Spring WebClient related exceptions
			if (e.getClass().getName().contains("ResourceAccessException")
					|| e.getClass().getName().contains("WebClientRequestException")) {
				return true;
			}

			// Check the exception type
			Throwable cause = e.getCause();
			while (cause != null) {
				String causeClassName = cause.getClass().getName();
				if (causeClassName.contains("IOException") || causeClassName.contains("SocketException")
						|| causeClassName.contains("ConnectException") || causeClassName.contains("TimeoutException")
						|| causeClassName.contains("SSLException")) {
					return true;
				}
				cause = cause.getCause();
			}

			return false;
		}

	}

}
