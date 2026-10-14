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

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatResponse;

/**
 * Streaming model interceptor that provides per-chunk interception for streaming model
 * calls.
 *
 * <p>
 * Unlike {@link ModelInterceptor} which intercepts the entire model call
 * (request/response), this interceptor provides fine-grained control over individual
 * streaming chunks.
 * </p>
 *
 * <p>
 * Lifecycle (per subscription — see {@link InterceptorChain#applyStreamingInterceptors}):
 * </p>
 * <ol>
 * <li>{@link #beforeStreamCall} - Called once before the chunk Flux is subscribed. The
 * returned {@link ModelRequest} is threaded into <i>subsequent callbacks</i> of this and
 * later interceptors as the {@code request} parameter; it does <b>not</b> alter the
 * actual outbound model call (the Flux has already been constructed). To modify the
 * outbound request use {@link ModelInterceptor}.</li>
 * <li>{@link #onStreamChunk} - Called for each {@link ChatEvent}; can transform, observe,
 * or drop ({@code return null}) the chunk</li>
 * <li>{@link #afterStreamComplete} - Called once after all chunks have been received,
 * with the complete {@link ChatResponse}, retaining tools, usage and metadata</li>
 * <li>{@link #onStreamError} - Called if an error occurs during streaming</li>
 * </ol>
 *
 * <p>
 * Example usage:
 * </p>
 * <pre>{@code
 * StreamingModelInterceptor interceptor = new StreamingModelInterceptor() {
 *     &#64;Override
 *     public ChatEvent onStreamChunk(ChatEvent chunk, ModelRequest request) {
 *         logger.debug("Event: {}", chunk.type());
 *         return chunk;
 *     }
 *
 *

&#64;Override
 *     public void afterStreamComplete(ChatResponse response, ModelRequest request) {
 *         logger.debug("Complete: {}", response.text());
 *     }
 * };
 *
 * ReactAgent agent = ReactAgent.builder()
 *     .name("my-agent")
 *     .chatClient(chatClient)
 *     .streamingInterceptors(interceptor)
 *     .build();
 * }</pre>
 *
 * @author haojunpan
 * @since 1.0.0
 * @see ModelInterceptor
 */
public interface StreamingModelInterceptor {

	/**
	 * Called once per subscription, before the chunk Flux is subscribed to.
	 *
	 * <p>
	 * The returned {@link ModelRequest} is propagated as the {@code request} parameter to
	 * this and later interceptors' {@link #onStreamChunk}, {@link #afterStreamComplete},
	 * and {@link #onStreamError} callbacks. Use this hook to attach context (correlation
	 * IDs, trace metadata, computed state) that downstream callbacks need to read.
	 *
	 * <p>
	 * <b>Important:</b> the returned request does <i>not</i> alter the outbound model
	 * call — by the time this method runs, the chunk Flux has already been built from the
	 * original request. To modify what is sent to the model (messages, options, tools),
	 * use {@link ModelInterceptor} instead.
	 * @param request the model request as threaded through earlier interceptors'
	 * {@code beforeStreamCall} (or the original request for the first interceptor in the
	 * chain)
	 * @return the (possibly enriched) model request to pass to subsequent callbacks
	 */
	default ModelRequest beforeStreamCall(ModelRequest request) {
		return request;
	}

	/**
	 * Called for each {@link ChatEvent} during streaming. Can be used to inspect,
	 * transform, or filter individual chunks.
	 * @param chunk the current streaming chunk
	 * @param request the model request as it stands after every interceptor's
	 * {@link #beforeStreamCall} has run (i.e. the fully-threaded request, not necessarily
	 * the original one passed to the model)
	 * @return the transformed event or null to drop a nonterminal event. Event types must
	 * remain unchanged. Text transformations belong on TEXT_DELTA events. The terminal
	 * RESPONSE is reconciled with the emitted text before interception, retaining nontext
	 * content, tools, usage and metadata. It must not be dropped or change the
	 * already-emitted text, but its tools, usage and metadata may be transformed.
	 */
	default ChatEvent onStreamChunk(ChatEvent chunk, ModelRequest request) {
		return chunk;
	}

	/**
	 * Called once after all streaming chunks have been received successfully. Can be used
	 * for logging, metrics collection, or post-processing.
	 * @param response this interceptor's complete response with emitted text and terminal
	 * tools, usage, content parts and metadata
	 * @param request the model request as it stands after every interceptor's
	 * {@link #beforeStreamCall} has run
	 */
	default void afterStreamComplete(ChatResponse response, ModelRequest request) {
	}

	/**
	 * Called if an error occurs during streaming. Can be used for error logging,
	 * alerting, or fallback logic.
	 * @param error the error that occurred
	 * @param request the model request as it stands after every interceptor's
	 * {@link #beforeStreamCall} has run
	 */
	default void onStreamError(Throwable error, ModelRequest request) {
	}

}
