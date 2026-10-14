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
package io.github.agentic.ai.api.chat;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

/** Framework-neutral chat client consumed by ARGI graph and agent runtimes. */
public interface ChatClient {

	/** Starts one model turn without executing tools or blocking the caller. */
	CompletionStage<ChatResponse> call(ChatRequest request);

	/**
	 * Returns a cold publisher. Each subscription starts a separate model turn. Text
	 * events are deltas; tool events contain complete invocations. A successful stream
	 * emits exactly one aggregated RESPONSE followed by onComplete. Errors use onError,
	 * never a successful final response. Cancellation must cancel the native
	 * subscription.
	 */
	Flow.Publisher<ChatEvent> stream(ChatRequest request);

	ChatClientCapabilities capabilities();

	/**
	 * Waits for one turn; thread interruption cancels it and retains the interrupt flag.
	 */
	default ChatResponse callBlocking(ChatRequest request) {
		CompletableFuture<ChatResponse> pending = call(Objects.requireNonNull(request, "request must not be null"))
			.toCompletableFuture();
		try {
			return pending.get();
		}
		catch (InterruptedException interrupted) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			throw new CancellationException("Model call interrupted");
		}
		catch (ExecutionException failed) {
			if (failed.getCause() instanceof RuntimeException cause) {
				throw cause;
			}
			if (failed.getCause() instanceof Error cause) {
				throw cause;
			}
			throw new CompletionException(failed.getCause());
		}
	}

}
