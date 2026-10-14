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
package io.github.agentic.ai.graph.agent.interceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

import io.github.agentic.ai.api.chat.ChatEvent;

import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * Utility class for chaining model and tool interceptors.
 *
 * This implements the Chain of Responsibility pattern. Interceptors are composed so that
 * the first in the list becomes the outermost layer.
 */
public class InterceptorChain {

	/**
	 * Chain multiple ModelInterceptors into a single handler.
	 *
	 * The first interceptor wraps all others, creating a nested structure:
	 * interceptors[0] -> interceptors[1] -> ... -> base handler
	 * @param interceptors List of ModelInterceptors to chain
	 * @param baseHandler The base handler that executes the actual model call
	 * @return A composed handler, or the base handler if no interceptors
	 */
	public static ModelCallHandler chainModelInterceptors(List<ModelInterceptor> interceptors,
			ModelCallHandler baseHandler) {

		if (interceptors == null || interceptors.isEmpty()) {
			return baseHandler;
		}

		// Start with the base handler
		ModelCallHandler current = baseHandler;

		// Wrap from last to first (right-to-left composition)
		// This ensures first interceptor is outermost
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			ModelInterceptor interceptor = interceptors.get(i);
			ModelCallHandler nextHandler = current;

			// Create a wrapper that calls the interceptor's wrap method
			current = request -> interceptor.interceptModel(request, nextHandler);
		}

		return current;
	}

	/**
	 * Chain multiple ToolInterceptors into a single handler.
	 *
	 * The first interceptor wraps all others, creating a nested structure:
	 * interceptors[0] -> interceptors[1] -> ... -> base handler
	 * @param interceptors List of ToolInterceptors to chain
	 * @param baseHandler The base handler that executes the actual tool call
	 * @return A composed handler, or the base handler if no interceptors
	 */
	public static ToolCallHandler chainToolInterceptors(List<ToolInterceptor> interceptors,
			ToolCallHandler baseHandler) {

		if (interceptors == null || interceptors.isEmpty()) {
			return baseHandler;
		}

		// Start with the base handler
		ToolCallHandler current = baseHandler;

		// Wrap from last to first (right-to-left composition)
		// This ensures first interceptor is outermost
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			ToolInterceptor interceptor = interceptors.get(i);
			ToolCallHandler nextHandler = current;

			// Create a wrapper that calls the interceptor's wrap method
			current = request -> interceptor.interceptToolCall(request, nextHandler);
		}

		return current;
	}

	/**
	 * Apply streaming interceptors to a cold publisher of neutral {@link ChatEvent}s.
	 *
	 * <p>
	 * The full pipeline is wrapped in {@link Flux#defer(java.util.function.Supplier)} so
	 * that every subscription (including reactor-driven retries) gets its own
	 * {@code beforeStreamCall} pass and its own per-interceptor aggregator buffer.
	 * Nothing runs at composition time.
	 *
	 * <p>
	 * Per subscription, for each interceptor in registration order (first to last):
	 * <ol>
	 * <li>{@link StreamingModelInterceptor#beforeStreamCall} is invoked, threading the
	 * (possibly mutated) request to the next interceptor</li>
	 * <li>{@link StreamingModelInterceptor#onStreamChunk} is invoked per chunk via
	 * {@link Flux#handle}: returning the original/transformed chunk emits it downstream;
	 * returning {@code null} drops it (filter)</li>
	 * <li>{@link StreamingModelInterceptor#afterStreamComplete} receives the terminal
	 * response reconciled with the text emitted by that interceptor, retaining tools,
	 * usage, nontext content and metadata</li>
	 * <li>{@link StreamingModelInterceptor#onStreamError} is invoked on error</li>
	 * </ol>
	 *
	 * <p>
	 * <b>Request threading:</b> the {@code request} parameter passed to every
	 * {@code onStreamChunk} / {@code afterStreamComplete} / {@code onStreamError}
	 * invocation is the request as it stands <i>after every interceptor's
	 * {@code beforeStreamCall} has run</i>, not the original {@code request} argument to
	 * this method. This means {@code beforeStreamCall} is purely a context-propagation
	 * hook between interceptors; it cannot influence the outbound model call (the chunk
	 * Flux has already been built from the original request). To modify what is sent to
	 * the model, use {@link ModelInterceptor}.
	 *
	 * <p>
	 * <b>Wrapping order:</b> the first registered interceptor is the <i>innermost</i>
	 * operator, i.e. it sees raw model chunks first; later interceptors see whatever the
	 * previous one returned. This differs from the synchronous
	 * {@code chainXxxInterceptors} methods above (where the first interceptor is
	 * outermost) because chunk-level interceptors typically want to observe untransformed
	 * model output.
	 * @param interceptors List of StreamingModelInterceptors to apply
	 * @param publisher The original publisher of neutral events
	 * @param request The model request (for context)
	 * @return The transformed cold publisher with all interceptors applied
	 */
	public static Flow.Publisher<ChatEvent> applyStreamingInterceptors(List<StreamingModelInterceptor> interceptors,
			Flow.Publisher<ChatEvent> publisher, ModelRequest request) {

		List<StreamingModelInterceptor> registered = interceptors == null ? List.of() : List.copyOf(interceptors);
		Objects.requireNonNull(publisher, "publisher must not be null");

		return JdkFlowAdapter.publisherToFlowPublisher(Flux.defer(() -> {
			// Defensive copy: each subscription starts from its own ModelRequest so an
			// interceptor that mutates context/messages/options in-place inside
			// beforeStreamCall does not leak state across retries / multi-subscribe.
			ModelRequest scopedRequest = copyForSubscription(request);

			// Thread request through beforeStreamCall (first to last). Wrapped in
			// try/catch
			// because we are inside the defer supplier — if we let the exception escape
			// before any doOnError operator is attached below, per-interceptor
			// onStreamError
			// callbacks would never fire and startup failures would be invisible to
			// users.
			ModelRequest threaded = scopedRequest;
			try {
				for (StreamingModelInterceptor interceptor : registered) {
					threaded = Objects.requireNonNull(interceptor.beforeStreamCall(threaded),
							"beforeStreamCall returned null");
				}
			}
			catch (Throwable startupError) {
				for (StreamingModelInterceptor interceptor : registered) {
					try {
						interceptor.onStreamError(startupError, scopedRequest);
					}
					catch (Throwable cbThrown) {
						// Don't let one buggy callback hide the original failure
						if (cbThrown != startupError) {
							startupError.addSuppressed(cbThrown);
						}
					}
				}
				return Flux.error(startupError);
			}
			final ModelRequest finalRequest = threaded;

			Flux<ChatEvent> current = ModelStreamResponses.normalize(JdkFlowAdapter.flowPublisherToFlux(publisher));
			for (StreamingModelInterceptor interceptor : registered) {
				final ModelStreamResponses aggregator = new ModelStreamResponses();

				current = current.handle((ChatEvent chunk, reactor.core.publisher.SynchronousSink<ChatEvent> sink) -> {
					ChatEvent reconciled = aggregator.reconcile(chunk);
					ChatEvent transformed = interceptor.onStreamChunk(reconciled, finalRequest);
					if (transformed == null) {
						if (chunk.type() == ChatEvent.Type.RESPONSE) {
							throw new IllegalStateException("Streaming interceptors must not drop the final response");
						}
						return;
					}
					if (transformed.type() != chunk.type()) {
						throw new IllegalStateException("Streaming interceptors must preserve event types");
					}
					if (transformed.type() == ChatEvent.Type.RESPONSE
							&& !transformed.response().text().equals(reconciled.response().text())) {
						throw new IllegalStateException("Transform streamed text on TEXT_DELTA events, not RESPONSE");
					}
					aggregator.accept(transformed);
					sink.next(transformed);
				})
					.doOnComplete(() -> interceptor.afterStreamComplete(aggregator.completedResponse(), finalRequest))
					.doOnError(error -> {
						try {
							interceptor.onStreamError(error, finalRequest);
						}
						catch (Throwable callbackError) {
							if (callbackError != error) {
								error.addSuppressed(callbackError);
							}
						}
					});
			}

			return current;
		}));
	}

	/**
	 * Build a per-subscription copy of {@link ModelRequest} so concurrent / retried
	 * subscriptions of the same wrapped {@link Flux} do not share mutable state.
	 *
	 * <p>
	 * {@link ModelRequest#builder(ModelRequest)} already shallow-copies editable message,
	 * tool-selection, description, and context collections. Immutable tool registries and
	 * generation options can be safely reused. This helper explicitly supplies a fresh
	 * {@code messages} list. An interceptor that calls
	 * {@code request.getMessages().add(...)} mutates only this subscription's copy.
	 *
	 * <p>
	 * Individual neutral ChatMessage records contain immutable content lists and metadata
	 * maps, so copying the outer list is sufficient for message isolation.
	 */
	private static ModelRequest copyForSubscription(ModelRequest src) {
		if (src == null) {
			return null;
		}
		return ModelRequest.builder(src)
			.messages(src.getMessages() != null ? new ArrayList<>(src.getMessages()) : null)
			.build();
	}

	/**
	 * Example of how synchronous (model/tool) interceptors are chained:
	 *
	 * Given interceptors [auth, retry, cache] and baseHandler:
	 *
	 * 1. Start: current = baseHandler 2. Wrap with cache: current = req ->
	 * cache.wrap(req, baseHandler) 3. Wrap with retry: current = req -> retry.wrap(req,
	 * cache.wrap(...)) 4. Wrap with auth: current = req -> auth.wrap(req,
	 * retry.wrap(...))
	 *
	 * Final call flow (first interceptor is outermost): request -> auth -> retry -> cache
	 * -> baseHandler
	 *
	 * Response flow: baseHandler -> cache -> retry -> auth -> response
	 *
	 * Note: streaming interceptors use the opposite convention — the first registered
	 * interceptor is the innermost operator and sees raw chunks first. See
	 * {@link #applyStreamingInterceptors}.
	 */

}
