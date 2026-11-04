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
package io.github.agentic.ai.agent.orchestration.interceptor.modelretry;

import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.retry.ModelCallFailure;
import io.github.agentic.ai.agent.retry.ModelCallFailureCategory;
import io.github.agentic.ai.agent.retry.ModelCallFailureClassifier;
import io.github.agentic.ai.agent.retry.ModelRetryController;
import io.github.agentic.ai.agent.retry.RetryDecision;
import io.github.agentic.ai.agent.retry.RetryPolicy;
import io.github.agentic.ai.agent.retry.RetryStopReason;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
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
 *
 * <p>
 * The retry sequence is bounded on three axes at once: the number of attempts, the
 * retryability of the observed failure and the deadline of the run. When the model
 * request carries the guard of its run, a cancelled or expired run stops the sequence
 * instead of dispatching another provider call.
 */
public class ModelRetryInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ModelRetryInterceptor.class);

	/** The longest a single backoff slice blocks before the run control is checked again. */
	private static final long BACKOFF_SLICE_MILLIS = 100L;

	private final int maxAttempts;

	private final long initialDelay;

	private final long maxDelay;

	private final double backoffMultiplier;

	private final Predicate<Exception> retryableExceptionPredicate;

	private final ModelCallFailureClassifier failureClassifier;

	private final Set<ModelCallFailureCategory> retryableCategories;

	private final long deadlineReserveMillis;

	private final Clock clock;

	private ModelRetryInterceptor(Builder builder) {
		this.maxAttempts = builder.maxAttempts;
		this.initialDelay = builder.initialDelay;
		this.maxDelay = builder.maxDelay;
		this.backoffMultiplier = builder.backoffMultiplier;
		this.retryableExceptionPredicate = builder.retryableExceptionPredicate;
		this.failureClassifier = builder.failureClassifier;
		this.retryableCategories = Set.copyOf(builder.retryableCategories);
		this.deadlineReserveMillis = builder.deadlineReserveMillis;
		this.clock = builder.clock;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		ModelRetryController controller = controller(request);
		Exception lastException = null;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			checkInterrupted();
			RetryDecision start = controller.beforeAttempt();
			if (start.isStop()) {
				throw terminalFailure(start.stopReason().orElse(RetryStopReason.EXECUTION_STOPPED), lastException);
			}
			try {
				if (attempt > 1) {
					log.info("Retry model call, on the {}th attempt (out of {} attempts).", attempt, maxAttempts);
				}

				ModelResponse modelResponse = handler.call(request);
				checkInterrupted();
				if (modelResponse.isStreaming()) {
					return ModelResponse
						.stream(JdkFlowAdapter.publisherToFlowPublisher(withStreamingRetry(request, handler,
								JdkFlowAdapter.flowPublisherToFlux(modelResponse.getStream()), attempt, controller)));
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

				RetryDecision decision = controller.onFailure(e);
				if (!decision.isRetry()) {
					RetryStopReason reason = decision.stopReason().orElse(RetryStopReason.NON_RETRYABLE);
					if (reason == RetryStopReason.ATTEMPTS_EXHAUSTED) {
						log.error("The maximum number of retries has been reached {}, and the model call has failed.",
								maxAttempts);
					}
					else if (reason == RetryStopReason.NON_RETRYABLE) {
						log.warn("Exceptions cannot be retried and are thrown immediately: {}", e.getMessage());
					}
					throw terminalFailure(reason, e);
				}

				// Wait and try again
				pause(decision.delay().orElseThrow().toMillis(), controller);
			}
		}

		// All retries failed.
		throw new RuntimeException("Model call failed, maximum number of retries reached. " + maxAttempts,
				lastException);
	}

	/**
	 * Wraps a streaming response. Every subscription of the response owns its attempt
	 * budget, so that a repeated subscription is not charged for the attempts of an
	 * earlier one.
	 */
	private Flux<ChatEvent> withStreamingRetry(ModelRequest request, ModelCallHandler handler, Flux<ChatEvent> responseFlux,
			int attempt, ModelRetryController controller) {
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
				return retryStreamingModelCall(request, handler, attempt, controller, error);
			});
		});
	}

	private Flux<ChatEvent> retryStreamingModelCall(ModelRequest request, ModelCallHandler handler, int failedAttempt,
			ModelRetryController controller, Throwable error) {
		if (error instanceof Error) {
			return Flux.error(error);
		}
		CancellationException cancellation = cancellation(error);
		if (cancellation != null) {
			return Flux.error(cancellation);
		}
		Exception exception = toException(error);
		log.warn("Streaming model call failed (attempted {}/{}): {}", failedAttempt, maxAttempts, exception.getMessage());

		RetryDecision decision = controller.onFailure(exception, failedAttempt);
		if (!decision.isRetry()) {
			if (decision.stopReason().orElse(RetryStopReason.NON_RETRYABLE) == RetryStopReason.NON_RETRYABLE) {
				log.warn("Exceptions cannot be retried and are thrown immediately: {}", exception.getMessage());
			}
			return Flux.error(terminalFailure(decision.stopReason().orElse(RetryStopReason.NON_RETRYABLE), exception));
		}

		int nextAttempt = failedAttempt + 1;
		long nextDelay = decision.delay().orElseThrow().toMillis();
		return delay(nextDelay).thenMany(Flux.defer(() -> {
			log.info("Retry model call, on the {}th attempt (out of {} attempts).", nextAttempt, maxAttempts);
			try {
				checkInterrupted();
				RetryDecision start = controller.checkAttemptAllowed();
				if (start.isStop()) {
					return Flux
						.error(terminalFailure(start.stopReason().orElse(RetryStopReason.EXECUTION_STOPPED), exception));
				}
				ModelResponse retryResponse = handler.call(request);
				return toStreamingFlux(request, handler, retryResponse, nextAttempt, controller);
			}
			catch (Exception retryError) {
				return retryStreamingModelCall(request, handler, nextAttempt, controller, retryError);
			}
		}));
	}

	/**
	 * Waits for the backoff. A run that carries a guard waits in slices, so that a
	 * cancellation or an expired deadline stops the wait instead of being noticed
	 * only when the next attempt would start.
	 */
	private void pause(long delay, ModelRetryController controller) {
		if (delay <= 0) {
			return;
		}
		log.info("Retry after {} ms", delay);
		ExecutionGuard guard = controller.guard().orElse(null);
		if (guard == null) {
			sleep(delay);
			return;
		}
		long remaining = delay;
		while (remaining > 0) {
			guard.assertActive();
			long slice = Math.min(remaining, BACKOFF_SLICE_MILLIS);
			sleep(slice);
			remaining -= slice;
		}
	}

	private void sleep(long millis) {
		checkInterrupted();
		try {
			Thread.sleep(millis);
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
			int attempt, ModelRetryController controller) {
		if (modelResponse.isStreaming()) {
			return withStreamingRetry(request, handler, JdkFlowAdapter.flowPublisherToFlux(modelResponse.getStream()),
					attempt, controller);
		}
		return Flux.just(ChatEvent.response(modelResponse.getChatResponse()));
	}

	private Flux<ChatEvent> delay(long currentDelay) {
		if (currentDelay <= 0) {
			return Flux.empty();
		}
		return Flux.<ChatEvent>empty().delaySubscription(Duration.ofMillis(currentDelay));
	}

	private Exception toException(Throwable error) {
		if (error instanceof Exception exception) {
			return exception;
		}
		return new RuntimeException(error);
	}

	/**
	 * Builds the controller of one model call. The deadline of the run comes from its
	 * guard, and falls back to the trusted execution context of the request.
	 */
	private ModelRetryController controller(ModelRequest request) {
		ExecutionGuard guard = request.getExecutionGuard().orElse(null);
		Instant deadline = guard == null ? null : guard.deadline().orElse(null);
		if (deadline == null) {
			deadline = request.getTrustedExecutionContext()
				.flatMap(TrustedExecutionContext::deadline)
				.orElse(null);
		}
		RetryPolicy policy = new RetryPolicy(maxAttempts, initialDelay, maxDelay, backoffMultiplier, retryableCategories,
				deadlineReserveMillis);
		return new ModelRetryController(policy, this::classify, guard, clock, deadline);
	}

	/**
	 * Classifies a failed attempt. Without an explicit classifier the legacy
	 * predicate decides, so that an existing configuration keeps its behaviour. An
	 * explicit classifier classifies by failure type instead of by message text.
	 */
	private ModelCallFailure classify(Throwable failure) {
		if (failure instanceof CancellationException || failure instanceof InterruptedException) {
			return new ModelCallFailure(ModelCallFailureCategory.CANCELLED, failure);
		}
		if (failureClassifier != null) {
			ModelCallFailure classified = failureClassifier.classify(failure);
			return classified != null ? classified : new ModelCallFailure(ModelCallFailureCategory.UNKNOWN, failure);
		}
		boolean retryable = failure instanceof Exception && retryableExceptionPredicate.test((Exception) failure);
		return new ModelCallFailure(retryable ? ModelCallFailureCategory.TRANSIENT : ModelCallFailureCategory.PERMANENT,
				failure);
	}

	private RuntimeException terminalFailure(RetryStopReason reason, Throwable cause) {
		return switch (reason) {
			case ATTEMPTS_EXHAUSTED -> new RuntimeException("Model call failed, maximum number of retries reached.",
					cause);
			case NON_RETRYABLE -> new RuntimeException("Model call failed (non-retryable exception)", cause);
			case DEADLINE_EXCEEDED -> new RuntimeException(
					"Model call failed, the run deadline does not allow another attempt", cause);
			case CANCELLED -> new RuntimeException("Model call failed, the attempt was cancelled", cause);
			case EXECUTION_STOPPED -> new RuntimeException("Model call failed, the run stopped", cause);
		};
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

		private ModelCallFailureClassifier failureClassifier;

		private Set<ModelCallFailureCategory> retryableCategories = EnumSet.of(ModelCallFailureCategory.TRANSIENT,
				ModelCallFailureCategory.RATE_LIMITED);

		private long deadlineReserveMillis;

		private Clock clock = Clock.systemUTC();

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

		/**
		 * Classifies the failures by their type instead of by the text of their
		 * message. Without an explicit classifier the retryable exception predicate
		 * decides, which keeps the behaviour of an existing configuration.
		 * @param failureClassifier The classifier of a failed attempt
		 */
		public Builder failureClassifier(ModelCallFailureClassifier failureClassifier) {
			this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier must not be null");
			return this;
		}

		/**
		 * Sets the failure categories that may be retried.
		 * @param retryableCategories The retryable categories, must not be null
		 */
		public Builder retryableCategories(Set<ModelCallFailureCategory> retryableCategories) {
			this.retryableCategories = Objects
				.requireNonNull(retryableCategories, "retryableCategories must not be null");
			return this;
		}

		/**
		 * Sets the time that must remain before the deadline of the run to start
		 * another attempt.
		 * @param deadlineReserve The reserved time, must not be negative
		 */
		public Builder deadlineReserve(Duration deadlineReserve) {
			Objects.requireNonNull(deadlineReserve, "deadlineReserve must not be null");
			if (deadlineReserve.isNegative()) {
				throw new IllegalArgumentException("deadlineReserve must be greater than or equal to 0.");
			}
			this.deadlineReserveMillis = deadlineReserve.toMillis();
			return this;
		}

		/**
		 * Sets the clock used for the deadline checks.
		 * @param clock The clock, must not be null
		 */
		public Builder clock(Clock clock) {
			this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
