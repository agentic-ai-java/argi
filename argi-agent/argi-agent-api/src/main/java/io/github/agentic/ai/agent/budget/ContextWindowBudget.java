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
package io.github.agentic.ai.agent.budget;

/**
 * The context window budget of a model.
 *
 * <p>
 * A budget has two parts. The {@code inputTokenLimit} is the total context window of
 * the model, and the {@code outputReserve} is the part of that window which is kept
 * for the completion. Everything the request carries - the system message, the
 * conversation, the tool schemas and the reserved output - has to fit into the
 * window together.
 *
 * <p>
 * The budget describes the window of one model, not of a run. A run that talks to
 * several models carries one budget per model.
 */
public record ContextWindowBudget(int inputTokenLimit, int outputReserve) {

	/**
	 * Creates a context window budget.
	 * @param inputTokenLimit the total context window of the model, must be positive
	 * @param outputReserve the tokens reserved for the completion, must be positive and
	 * smaller than the window
	 */
	public ContextWindowBudget {
		if (inputTokenLimit <= 0) {
			throw new IllegalArgumentException("inputTokenLimit must be positive");
		}
		if (outputReserve <= 0) {
			throw new IllegalArgumentException("outputReserve must be positive");
		}
		if (outputReserve >= inputTokenLimit) {
			throw new IllegalArgumentException("outputReserve must be smaller than inputTokenLimit");
		}
	}

	/**
	 * The number of tokens which may be spent on the request itself.
	 * @return the usable input budget
	 */
	public int usableInputTokens() {
		return inputTokenLimit - outputReserve;
	}

}
