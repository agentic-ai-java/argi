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

import io.github.agentic.ai.agent.interceptor.ModelResponse;

import java.util.Objects;

/**
 * The result of a model call in the neutral lifecycle contract.
 *
 * <p>
 * A model call either succeeds with a response or fails with an error description.
 * Exactly one of {@code response} and {@code error} is present.
 *
 * @param modelTurn the model turn identity, never null
 * @param response the model response, nullable
 * @param error the failure description, nullable
 */
public record ModelCallOutcome(ModelTurnId modelTurn, ModelResponse response, String error) {

	/**
	 * Creates a model call outcome.
	 * @param modelTurn the model turn identity, must not be null
	 * @param response the model response, nullable
	 * @param error the failure description, nullable
	 */
	public ModelCallOutcome {
		Objects.requireNonNull(modelTurn, "modelTurn must not be null");
		if ((response == null) == (error == null)) {
			throw new IllegalArgumentException("exactly one of response and error must be present");
		}
	}

	/**
	 * Creates a successful outcome.
	 * @param modelTurn the model turn identity, must not be null
	 * @param response the model response, must not be null
	 * @return the successful outcome
	 */
	public static ModelCallOutcome success(ModelTurnId modelTurn, ModelResponse response) {
		return new ModelCallOutcome(modelTurn, response, null);
	}

	/**
	 * Creates a failed outcome.
	 * @param modelTurn the model turn identity, must not be null
	 * @param error the failure description, must not be null
	 * @return the failed outcome
	 */
	public static ModelCallOutcome failure(ModelTurnId modelTurn, String error) {
		return new ModelCallOutcome(modelTurn, null, error);
	}

	/**
	 * Whether the call succeeded.
	 * @return true when a response is present
	 */
	public boolean isSuccess() {
		return response != null;
	}

}
