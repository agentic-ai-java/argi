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
package io.github.agentic.ai.agent.lifecycle;

import io.github.agentic.ai.agent.interceptor.ModelRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A model call in the neutral lifecycle contract.
 *
 * <p>
 * The invocation is immutable: a restricted transformer may replace the request, but
 * neither the identity nor the model turn identity may change. The request keeps the
 * trusted execution context of the run; the neutral contract never exposes a way to
 * rewrite that context.
 *
 * @param identity the identity of the work this call belongs to, never null
 * @param modelTurn the model turn identity, never null
 * @param request the model request, never null
 */
public record ModelCallInvocation(LifecycleIdentity identity, ModelTurnId modelTurn, ModelRequest request) {

	/**
	 * Creates a model call invocation.
	 * @param identity the identity of the work this call belongs to, must not be null
	 * @param modelTurn the model turn identity, must not be null
	 * @param request the model request, must not be null
	 */
	public ModelCallInvocation {
		Objects.requireNonNull(identity, "identity must not be null");
		Objects.requireNonNull(modelTurn, "modelTurn must not be null");
		Objects.requireNonNull(request, "request must not be null");
		request = defensiveCopy(request);
	}

	/**
	 * Returns a copy of the request whose collection-valued accessors are immutable:
	 * observers must not be able to rewrite the execution input (clearing the message
	 * list or mutating the context map) through the invocation reference. Transforming
	 * extensions observe this copy and rebuild a replacement from it; the executor
	 * keeps running on the original request it holds, so a mutated copy would have no
	 * effect on dispatch.
	 */
	private static ModelRequest defensiveCopy(ModelRequest request) {
		return ModelRequest.builder(request)
				.messages(List.copyOf(request.getMessages()))
				.context(Map.copyOf(request.getContext()))
				.attributes(Map.copyOf(request.getAttributes()))
				.build();
	}

}
