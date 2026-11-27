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
package io.github.agentic.ai.agent.orchestration.interceptor.modelfallback;

import io.github.agentic.ai.agent.fallback.ModelFallbackException;
import io.github.agentic.ai.agent.fallback.ModelFallbackPolicy;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.retry.ModelAttempt;
import io.github.agentic.ai.agent.retry.ModelCallFailure;
import io.github.agentic.ai.agent.retry.ModelCallFailureCategory;
import io.github.agentic.ai.agent.retry.ModelCallFailureClassifier;
import io.github.agentic.ai.agent.retry.ModelRetryFailure;
import io.github.agentic.ai.agent.retry.RetryStopReason;
import io.github.agentic.ai.agent.retry.TypeBasedModelCallFailureClassifier;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Automatic fallback to alternative models on errors.
 *
 * Retries failed model calls with alternative models in sequence until success or all
 * models exhausted. A failure of a streaming response switches to the next candidate
 * only when it arrives before any event has been emitted downstream; once a text delta,
 * tool call, usage or response event has been observed, the failure terminates the
 * stream, so output of two models is never concatenated. A cancellation — however
 * deeply it is wrapped in the cause chain — keeps its unwrapped identity on either side
 * of that boundary, and it, an interruption, or a run whose {@link ExecutionGuard} is
 * no longer active never triggers another candidate. Each subscription of a streaming
 * response owns its switch sequence.
 *
 * When combined with {@code ModelRetryInterceptor}, register this interceptor first
 * (outermost), so retry and fallback share the smaller configured total attempt limit.
 *
 * Example: ModelFallbackInterceptor interceptor = ModelFallbackInterceptor.builder()
 * .addFallbackClient(primaryAlternative) .addFallbackClient(secondaryAlternative)
 * .build();
 */
public class ModelFallbackInterceptor extends ModelInterceptor {

	private final ModelFallbackPolicy policy;

	private final ModelCallFailureClassifier classifier;

	/** Bounds the cause-chain walks; a cyclic chain must not spin the thread forever. */
	private static final int MAX_CAUSE_DEPTH = 32;

	private final List<ChatClient> fallbackClients;

	private ModelFallbackInterceptor(Builder builder) {
		this.policy = builder.policy;
		this.classifier = builder.classifier;
		this.fallbackClients = List.copyOf(builder.fallbackClients);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		ModelAttempt first = request.getModelAttempt().orElseGet(ModelAttempt::first);
		return callCandidates(request, handler, 0, first.boundedBy(policy.maxTotalAttempts()));
	}

	private ModelResponse callCandidates(ModelRequest request, ModelCallHandler handler, int index, ModelAttempt attempt) {
		while (true) {
			assertRunActive(request);
			propagateCancellation(null);
			ModelRequest.Builder candidate = ModelRequest.builder(request).modelAttempt(attempt);
			if (index > 0) {
				candidate.chatClient(fallbackClients.get(index - 1));
			}
			try {
				ModelResponse response = handler.call(candidate.build());
				if (!response.isStreaming()) {
					return response;
				}
				return withSwitchBoundary(request, handler, response, index, attempt);
			}
			catch (Exception failure) {
				propagateCancellation(failure);
				attempt = nextAttempt(request, failure, index, attempt);
				index++;
			}
		}
	}

	private ModelResponse withSwitchBoundary(ModelRequest request, ModelCallHandler handler, ModelResponse response,
			int index, ModelAttempt attempt) {
		Flux<ChatEvent> candidate = JdkFlowAdapter.flowPublisherToFlux(response.getStream());
		Flux<ChatEvent> stream = Flux.defer(() -> {
			assertRunActive(request);
			propagateCancellation(null);
			AtomicBoolean emitted = new AtomicBoolean();
			return candidate.doOnNext(event -> emitted.set(true)).onErrorResume(error -> {
				if (error instanceof Error) {
					return Flux.error(error);
				}
				CancellationException cancellation = findWrappedCancellation(error);
				if (cancellation != null) {
					return Flux.error(cancellation);
				}
				if (emitted.get()) {
					return Flux.error(error);
				}
				ModelAttempt next = nextAttempt(request, error, index, attempt);
				// Synchronous alternatives must not block the provider's signal thread.
				return Flux.defer(() -> {
					ModelResponse alternative = callCandidates(request, handler, index + 1, next);
					return alternative.isStreaming() ? JdkFlowAdapter.flowPublisherToFlux(alternative.getStream())
							: Flux.just(ChatEvent.response(alternative.getChatResponse()));
				}).subscribeOn(Schedulers.boundedElastic());
			});
		});
		return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(stream));
	}

	private ModelAttempt nextAttempt(ModelRequest request, Throwable failure, int switches, ModelAttempt attempt) {
		assertRunActive(request);
		if (failure instanceof ModelRetryFailure retry) {
			if (retry.reason() != RetryStopReason.NON_RETRYABLE && retry.reason() != RetryStopReason.ATTEMPTS_EXHAUSTED) {
				throw retry;
			}
			attempt = retry.attempt();
			failure = retry.getCause();
		}
		ModelCallFailure classified = classifier.classify(failure);
		ModelCallFailureCategory category = classified == null ? ModelCallFailureCategory.UNKNOWN : classified.category();
		ModelFallbackException.Reason reason;
		if (!policy.switchableCategories().contains(category)) {
			reason = ModelFallbackException.Reason.NON_SWITCHABLE;
		}
		else if (attempt.exhausted()) {
			reason = ModelFallbackException.Reason.ATTEMPT_LIMIT;
		}
		else if (switches == fallbackClients.size()) {
			reason = ModelFallbackException.Reason.CANDIDATES_EXHAUSTED;
		}
		else if (switches == policy.maxSwitches()) {
			reason = ModelFallbackException.Reason.SWITCH_LIMIT;
		}
		else {
			return attempt.next();
		}
		throw new ModelFallbackException(reason, attempt.number(), switches, failure);
	}

	private static void assertRunActive(ModelRequest request) {
		request.getExecutionGuard().ifPresent(ExecutionGuard::assertActive);
	}

	private static void propagateCancellation(Exception failure) {
		CancellationException cancellation = cancellation(failure);
		if (cancellation != null) {
			throw cancellation;
		}
	}

	/**
	 * Finds the cancellation of a failed attempt, however deeply it is wrapped, without
	 * touching thread state. A wrapped cancellation is still a cancellation: switching
	 * to another model would dispatch a call the caller already aborted (issue #210).
	 * The walk is depth-bounded, because a cyclic cause chain (constructible through
	 * {@code Throwable.initCause} on two nodes) would otherwise spin the thread
	 * forever.
	 */
	private static CancellationException findWrappedCancellation(Throwable failure) {
		Throwable cause = failure;
		for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (cause instanceof CancellationException cancelled) {
				return cancelled;
			}
			if (cause instanceof InterruptedException interrupted) {
				CancellationException cancelled = new CancellationException("Model call interrupted");
				cancelled.initCause(interrupted);
				return cancelled;
			}
			cause = cause.getCause() == cause ? null : cause.getCause();
		}
		return null;
	}

	/**
	 * The cancellation of a synchronous attempt. On the caller's own thread the
	 * interrupt flag carries the same meaning: an interruption in the chain restores
	 * the flag so upstream blocking work also terminates, and a bare interrupt flag
	 * converts to a cancellation. Reactive paths use
	 * {@link #findWrappedCancellation(Throwable)} instead, because a pooled signal
	 * thread's flag belongs to the pool, not to this run.
	 */
	private static CancellationException cancellation(Throwable failure) {
		CancellationException wrapped = findWrappedCancellation(failure);
		if (wrapped != null) {
			if (chainsInterruption(failure)) {
				Thread.currentThread().interrupt();
			}
			return wrapped;
		}
		if (!Thread.currentThread().isInterrupted()) {
			return null;
		}
		CancellationException cancelled = new CancellationException("Model call interrupted");
		if (failure != null) {
			cancelled.initCause(failure);
		}
		return cancelled;
	}

	private static boolean chainsInterruption(Throwable failure) {
		Throwable cause = failure;
		for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (cause instanceof InterruptedException) {
				return true;
			}
			cause = cause.getCause() == cause ? null : cause.getCause();
		}
		return false;
	}

	@Override
	public String getName() {
		return "ModelFallback";
	}

	public static class Builder {

		private final List<ChatClient> fallbackClients = new ArrayList<>();

		private ModelFallbackPolicy policy = ModelFallbackPolicy.legacy();

		private ModelCallFailureClassifier classifier = new TypeBasedModelCallFailureClassifier();

		public Builder policy(ModelFallbackPolicy policy) {
			this.policy = Objects.requireNonNull(policy);
			return this;
		}

		public Builder failureClassifier(ModelCallFailureClassifier classifier) {
			this.classifier = Objects.requireNonNull(classifier);
			return this;
		}

		public Builder addFallbackClient(ChatClient client) {
			this.fallbackClients.add(Objects.requireNonNull(client, "Fallback client must not be null"));
			return this;
		}

		public Builder fallbackClients(List<ChatClient> clients) {
			clients.forEach(this::addFallbackClient);
			return this;
		}

		public ModelFallbackInterceptor build() {
			if (fallbackClients.isEmpty()) {
				throw new IllegalArgumentException("At least one fallback client must be specified");
			}
			return new ModelFallbackInterceptor(this);
		}

	}

}
