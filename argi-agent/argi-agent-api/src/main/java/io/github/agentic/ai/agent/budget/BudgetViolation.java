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
 * A budget which was exceeded.
 *
 * <p>
 * The violation is a value, not a message: a caller can react to the classified
 * component instead of parsing an error string.
 *
 * @param component the part which exceeded the budget
 * @param estimatedTokens the estimated tokens of that part
 * @param limitTokens the tokens available for it
 */
public record BudgetViolation(BudgetComponent component, int estimatedTokens, int limitTokens) {

	/**
	 * Creates a budget violation.
	 * @param component the part which exceeded the budget, must not be null
	 * @param estimatedTokens the estimated tokens of that part, must not be negative
	 * @param limitTokens the tokens available for it, must not be negative
	 */
	public BudgetViolation {
		Objects.requireNonNull(component, "component must not be null");
		if (estimatedTokens < 0 || limitTokens < 0) {
			throw new IllegalArgumentException("token counts must not be negative");
		}
	}

	/**
	 * Describes the violation of the total budget.
	 * @param estimate the estimate which exceeded the budget, must not be null
	 * @param budget the budget which was exceeded, must not be null
	 * @return the violation
	 */
	public static BudgetViolation ofTotal(RequestTokenEstimate estimate, ContextWindowBudget budget) {
		Objects.requireNonNull(estimate, "estimate must not be null");
		Objects.requireNonNull(budget, "budget must not be null");
		return new BudgetViolation(BudgetComponent.TOTAL, estimate.totalTokens(), budget.inputTokenLimit());
	}

	/**
	 * The tokens which exceeded the budget.
	 * @return the excess in tokens
	 */
	public int excessTokens() {
		return Math.max(0, estimatedTokens - limitTokens);
	}

}
