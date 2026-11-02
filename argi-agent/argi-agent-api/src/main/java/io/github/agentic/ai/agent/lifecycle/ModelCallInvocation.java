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
	}

}
