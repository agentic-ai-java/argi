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
 * Thrown when a request does not fit into the context window of the model and could
 * not be compressed into it.
 *
 * <p>
 * The failure is explicit. The request is not dispatched, and it is not silently
 * truncated either: a caller which cannot fit the request has to decide how to
 * continue.
 */
public class ContextWindowBudgetExceededException extends RuntimeException {

	private final BudgetViolation violation;

	private final RequestTokenEstimate estimate;

	/**
	 * Creates the exception.
	 * @param violation the classified violation, must not be null
	 * @param estimate the estimate which exceeded the budget, must not be null
	 */
	public ContextWindowBudgetExceededException(BudgetViolation violation, RequestTokenEstimate estimate) {
		super("the request does not fit into the context window: " + violation.estimatedTokens() + " estimated tokens, "
				+ violation.limitTokens() + " available, dominant part is " + estimate.dominantComponent());
		this.violation = violation;
		this.estimate = estimate;
	}

	/**
	 * The classified violation.
	 * @return the violation, never null
	 */
	public BudgetViolation violation() {
		return violation;
	}

	/**
	 * The estimate which exceeded the budget.
	 * @return the estimate, never null
	 */
	public RequestTokenEstimate estimate() {
		return estimate;
	}

	/**
	 * The part of the request which consumes the most tokens.
	 * @return the dominant component, never null
	 */
	public BudgetComponent dominantComponent() {
		return estimate.dominantComponent();
	}

	/**
	 * Creates the exception for an estimate which exceeded a budget.
	 * @param estimate the estimate which exceeded the budget, must not be null
	 * @param budget the budget which was exceeded, must not be null
	 * @return the exception
	 */
	public static ContextWindowBudgetExceededException of(RequestTokenEstimate estimate, ContextWindowBudget budget) {
		Objects.requireNonNull(estimate, "estimate must not be null");
		Objects.requireNonNull(budget, "budget must not be null");
		return new ContextWindowBudgetExceededException(BudgetViolation.ofTotal(estimate, budget), estimate);
	}

}
