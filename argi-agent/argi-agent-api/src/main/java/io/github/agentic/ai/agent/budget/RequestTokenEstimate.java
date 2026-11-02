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

import java.util.Objects;

/**
 * The estimated token consumption of one model request.
 *
 * <p>
 * The estimate is decomposed instead of being a single number, because the reaction
 * to an oversized request depends on which part caused it: a large tool schema is
 * handled by selecting fewer tools, a large conversation by summarizing it, and a
 * large output reserve by asking for a shorter answer.
 *
 * @param systemTokens the estimated tokens of the system message
 * @param messageTokens the estimated tokens of the conversation
 * @param toolSchemaTokens the estimated tokens of the disclosed tool schemas
 * @param outputReserve the tokens reserved for the completion
 * @param accuracy how accurate the estimate is
 */
public record RequestTokenEstimate(int systemTokens, int messageTokens, int toolSchemaTokens, int outputReserve,
		EstimationAccuracy accuracy) {

	/**
	 * Creates a token estimate.
	 * @param systemTokens the estimated tokens of the system message, must not be
	 * negative
	 * @param messageTokens the estimated tokens of the conversation, must not be
	 * negative
	 * @param toolSchemaTokens the estimated tokens of the disclosed tool schemas, must
	 * not be negative
	 * @param outputReserve the tokens reserved for the completion, must not be negative
	 * @param accuracy how accurate the estimate is, must not be null
	 */
	public RequestTokenEstimate {
		if (systemTokens < 0 || messageTokens < 0 || toolSchemaTokens < 0 || outputReserve < 0) {
			throw new IllegalArgumentException("token estimates must not be negative");
		}
		Objects.requireNonNull(accuracy, "accuracy must not be null");
	}

	/**
	 * The estimated tokens of the request itself, without the reserved output.
	 * @return the estimated input tokens
	 */
	public int inputTokens() {
		return systemTokens + messageTokens + toolSchemaTokens;
	}

	/**
	 * The estimated tokens the request and the reserved output consume together.
	 * @return the estimated total tokens
	 */
	public int totalTokens() {
		return inputTokens() + outputReserve;
	}

	/**
	 * The part of the request which consumes the most tokens.
	 * @return the dominant component, never null
	 */
	public BudgetComponent dominantComponent() {
		if (toolSchemaTokens >= messageTokens && toolSchemaTokens >= systemTokens && toolSchemaTokens >= outputReserve) {
			return BudgetComponent.TOOL_SCHEMA;
		}
		if (messageTokens >= systemTokens && messageTokens >= outputReserve) {
			return BudgetComponent.MESSAGES;
		}
		if (systemTokens >= outputReserve) {
			return BudgetComponent.SYSTEM;
		}
		return BudgetComponent.OUTPUT_RESERVE;
	}

}
