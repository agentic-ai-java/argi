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

import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * (outermost), so each model is retried to exhaustion before the sequence fails over to
 * the next candidate.
 *
 * Example: ModelFallbackInterceptor interceptor = ModelFallbackInterceptor.builder()
 * .addFallbackClient(primaryAlternative) .addFallbackClient(secondaryAlternative)
 * .build();
 */
public class ModelFallbackInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ModelFallbackInterceptor.class);

	/** Bounds the cause-chain walks; a cyclic chain must not spin the thread forever. */
	private static final int MAX_CAUSE_DEPTH = 32;

	private final List<ChatClient> fallbackClients;

	private ModelFallbackInterceptor(Builder builder) {
		this.fallbackClients = List.copyOf(builder.fallbackClients);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		Exception lastException = null;

		// A cancelled or expired run is terminal: no provider call at all, the primary
		// included, mirroring the per-attempt guard check of the retry interceptor.
		assertRunActive(request);

		// Try primary model first
		try {
			return withSwitchBoundary(request, handler, handler.call(request), 0);
		}
		catch (Exception e) {
			propagateCancellation(e);
			log.warn("Primary model failed: {}", e.getMessage());
			lastException = e;
		}

		// Try fallback models in sequence
		for (int i = 0; i < fallbackClients.size(); i++) {
			// A cancelled or expired run is terminal: no further provider call.
			assertRunActive(request);
			try {
				log.info("Trying fallback client {} of {}", i + 1, fallbackClients.size());
				// Reuse the request pipeline so system messages, tools and usage are
				// retained.
				ModelResponse fallback = handler
					.call(ModelRequest.builder(request).chatClient(fallbackClients.get(i)).build());
				return withSwitchBoundary(request, handler, fallback, i + 1);
			}
			catch (Exception e) {
				propagateCancellation(e);
				log.warn("Fallback model {} failed: {}", i + 1, e.getMessage());
				lastException = e;
			}
		}

		// All models failed
		throw allClientsFailed(lastException);
	}

	/**
	 * Attaches the switch boundary to a candidate's streaming response. A
	 * non-streaming response is returned unchanged. Every subscription of the returned
	 * publisher owns its own switch state, so a repeated subscription replays the
	 * captured candidate stream instead of resuming the fallback sequence of an earlier
	 * one.
	 */
	private ModelResponse withSwitchBoundary(ModelRequest request, ModelCallHandler handler, ModelResponse response,
			int nextClientIndex) {
		if (!response.isStreaming()) {
			return response;
		}
		return ModelResponse.stream(JdkFlowAdapter.publisherToFlowPublisher(
				switchingFluxDeferred(request, handler, JdkFlowAdapter.flowPublisherToFlux(response.getStream()),
						nextClientIndex)));
	}

	/**
	 * The deferred form of the switch boundary: fresh switch state per subscription and
	 * a subscription-time interrupt check, mirroring the retry interceptor.
	 */
	private Flux<ChatEvent> switchingFluxDeferred(ModelRequest request, ModelCallHandler handler,
			Flux<ChatEvent> candidateFlux, int nextClientIndex) {
		return Flux.defer(() -> {
			// Mirrors the retry interceptor: a subscribing thread that is already
			// interrupted must not start (or resume) a provider sequence.
			if (Thread.currentThread().isInterrupted()) {
				return Flux.<ChatEvent>error(new CancellationException("Model call interrupted"));
			}
			return switchingFlux(request, handler, candidateFlux, nextClientIndex);
		});
	}

	/**
	 * Wraps one candidate's stream with the switch boundary: a failure that arrives
	 * before any event has been emitted downstream switches to the next candidate,
	 * because no observable effect has escaped yet. Once an event — text delta, tool
	 * call, usage or response — has been emitted, the failure terminates the stream: a
	 * second model's output must never be appended to a partially delivered one. A
	 * cancellation keeps its unwrapped identity on both sides of that boundary.
	 */
	private Flux<ChatEvent> switchingFlux(ModelRequest request, ModelCallHandler handler, Flux<ChatEvent> candidateFlux,
			int nextClientIndex) {
		AtomicBoolean eventEmitted = new AtomicBoolean(false);
		return candidateFlux.doOnNext(event -> eventEmitted.set(true)).onErrorResume(error -> {
			if (error instanceof Error) {
				return Flux.error(error);
			}
			// The error signal itself carries the cancellation truth on a reactive
			// thread; the thread's interrupt flag belongs to the pool, not to this run,
			// and is deliberately not consulted here.
			CancellationException cancellation = findWrappedCancellation(error);
			if (cancellation != null) {
				log.info("Streaming candidate failed with a cancellation; terminating the fallback sequence: {}",
						cancellation.getMessage());
				return Flux.error(cancellation);
			}
			if (eventEmitted.get()) {
				return Flux.error(error);
			}
			// Dispatch off the signal thread: a non-streaming fallback candidate would
			// otherwise park the thread that delivered the failure, which for a Netty
			// event loop stalls every other stream multiplexed on it.
			return Flux.defer(() -> nextClientFlux(request, handler, nextClientIndex, toException(error)))
				.subscribeOn(Schedulers.boundedElastic());
		});
	}

	/**
	 * Streams the next fallback candidate, advancing until one succeeds or the sequence
	 * is exhausted. A cancellation, however wrapped, and a dead execution guard end the
	 * sequence instead of dispatching another provider call.
	 */
	private Flux<ChatEvent> nextClientFlux(ModelRequest request, ModelCallHandler handler, int clientIndex,
			Exception lastException) {
		if (clientIndex >= fallbackClients.size()) {
			return Flux.error(allClientsFailed(lastException));
		}
		try {
			assertRunActive(request);
		}
		catch (Exception lostRun) {
			return Flux.error(lostRun);
		}
		log.info("Trying fallback client {} of {}", clientIndex + 1, fallbackClients.size());
		try {
			ModelResponse next = handler
				.call(ModelRequest.builder(request).chatClient(fallbackClients.get(clientIndex)).build());
			if (next.isStreaming()) {
				return switchingFluxDeferred(request, handler, JdkFlowAdapter.flowPublisherToFlux(next.getStream()),
						clientIndex + 1);
			}
			return Flux.just(ChatEvent.response(next.getChatResponse()));
		}
		catch (Exception e) {
			CancellationException cancellation = findWrappedCancellation(e);
			if (cancellation != null) {
				return Flux.error(cancellation);
			}
			log.warn("Fallback model {} failed: {}", clientIndex + 1, e.getMessage());
			return nextClientFlux(request, handler, clientIndex + 1, e);
		}
	}

	private static void assertRunActive(ModelRequest request) {
		request.getExecutionGuard().ifPresent(ExecutionGuard::assertActive);
	}

	private RuntimeException allClientsFailed(Exception lastException) {
		StringBuilder sequence = new StringBuilder("primary");
		for (int i = 1; i <= fallbackClients.size(); i++) {
			sequence.append(", fallback-").append(i);
		}
		return new RuntimeException(
				"All clients failed after " + (fallbackClients.size() + 1) + " attempts (" + sequence + ")",
				lastException);
	}

	private static Exception toException(Throwable error) {
		return error instanceof Exception exception ? exception : new RuntimeException(error);
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
