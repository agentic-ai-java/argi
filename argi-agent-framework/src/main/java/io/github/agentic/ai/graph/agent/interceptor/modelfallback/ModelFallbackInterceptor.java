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
package io.github.agentic.ai.graph.agent.interceptor.modelfallback;

import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;

import io.github.agentic.ai.api.chat.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automatic fallback to alternative models on errors.
 *
 * Retries failed model calls with alternative models in sequence until success or all
 * models exhausted. Streaming responses are passed through unchanged. Errors emitted by
 * the publisher after subscription are not handled by this interceptor.
 *
 * Example: ModelFallbackInterceptor interceptor = ModelFallbackInterceptor.builder()
 * .addFallbackClient(primaryAlternative) .addFallbackClient(secondaryAlternative)
 * .build();
 */
public class ModelFallbackInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ModelFallbackInterceptor.class);

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

		// Try primary model first
		try {
			return handler.call(request);
		}
		catch (Exception e) {
			propagateCancellation(e);
			log.warn("Primary model failed: {}", e.getMessage());
			lastException = e;
		}

		// Try fallback models in sequence
		for (int i = 0; i < fallbackClients.size(); i++) {
			try {
				log.info("Trying fallback client {} of {}", i + 1, fallbackClients.size());
				// Reuse the request pipeline so system messages, tools and usage are
				// retained.
				return handler.call(ModelRequest.builder(request).chatClient(fallbackClients.get(i)).build());
			}
			catch (Exception e) {
				propagateCancellation(e);
				log.warn("Fallback model {} failed: {}", i + 1, e.getMessage());
				lastException = e;
			}
		}

		// All models failed
		throw new RuntimeException("All clients failed after " + (fallbackClients.size() + 1) + " attempts",
				lastException);
	}

	private static void propagateCancellation(Exception failure) {
		if (failure instanceof CancellationException cancelled) {
			throw cancelled;
		}
		if (Thread.currentThread().isInterrupted() || failure instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			throw new CancellationException("Model call interrupted");
		}
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
