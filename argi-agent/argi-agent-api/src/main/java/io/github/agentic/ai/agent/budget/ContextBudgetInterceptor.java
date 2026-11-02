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

import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;

import java.util.Objects;
import java.util.Optional;

/**
 * The gate which keeps a model request inside the context window of the model.
 *
 * <p>
 * The interceptor runs at the last point before the request is dispatched. It
 * estimates the token consumption of the whole request, and when the request does not
 * fit:
 *
 * <ol>
 * <li>the configured {@link RequestCompactor} is asked to shrink the request;</li>
 * <li>the compacted request is estimated again;</li>
 * <li>the tool call pairing of the compacted request is validated;</li>
 * <li>the request is dispatched only when it fits and stays paired.</li>
 * </ol>
 *
 * <p>
 * A request which still does not fit is never dispatched. The interceptor fails with
 * a {@link ContextWindowBudgetExceededException} instead, so an oversized request
 * cannot reach the provider.
 *
 * <p>
 * The interceptor is opt-in and does not change the behaviour of an agent unless it
 * is registered in the interceptor chain.
 */
public final class ContextBudgetInterceptor extends ModelInterceptor {

	private final ContextWindowBudget budget;

	private final RequestTokenEstimator estimator;

	private final RequestCompactor compactor;

	private ContextBudgetInterceptor(Builder builder) {
		this.budget = builder.budget;
		this.estimator = builder.estimator;
		this.compactor = builder.compactor;
	}

	/**
	 * Creates a builder for the interceptor.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(handler, "handler must not be null");
		RequestTokenEstimate estimate = estimator.estimate(request, budget);
		if (estimate.totalTokens() <= budget.inputTokenLimit()) {
			return handler.call(request);
		}
		ModelRequest compacted = request;
		if (compactor != null) {
			compacted = Objects.requireNonNull(compactor.compact(request), "compactor must not return null");
			Optional<ToolCallPairingValidator.Violation> violation = ToolCallPairingValidator
				.validate(compacted.getMessages());
			if (violation.isPresent()) {
				throw new ToolCallPairingValidator.PairingException(violation.get());
			}
		}
		RequestTokenEstimate compactedEstimate = estimator.estimate(compacted, budget);
		if (compactedEstimate.totalTokens() > budget.inputTokenLimit()) {
			throw ContextWindowBudgetExceededException.of(compactedEstimate, budget);
		}
		return handler.call(compacted);
	}

	@Override
	public String getName() {
		return "ContextBudget";
	}

	/**
	 * The budget this interceptor enforces.
	 * @return the budget, never null
	 */
	public ContextWindowBudget budget() {
		return budget;
	}

	/**
	 * Builder for {@link ContextBudgetInterceptor}.
	 */
	public static final class Builder {

		private ContextWindowBudget budget;

		private RequestTokenEstimator estimator = new ApproximateRequestTokenEstimator();

		private RequestCompactor compactor;

		private Builder() {
		}

		/**
		 * Sets the budget of the target model.
		 * @param budget the budget, must not be null
		 * @return this builder
		 */
		public Builder budget(ContextWindowBudget budget) {
			this.budget = budget;
			return this;
		}

		/**
		 * Sets the estimator which measures the request.
		 * @param estimator the estimator, must not be null
		 * @return this builder
		 */
		public Builder estimator(RequestTokenEstimator estimator) {
			this.estimator = estimator;
			return this;
		}

		/**
		 * Sets the compactor which shrinks an oversized request.
		 * @param compactor the compactor, nullable to disable compression
		 * @return this builder
		 */
		public Builder compactor(RequestCompactor compactor) {
			this.compactor = compactor;
			return this;
		}

		/**
		 * Builds the interceptor.
		 * @return the interceptor
		 */
		public ContextBudgetInterceptor build() {
			Objects.requireNonNull(budget, "budget must not be null");
			Objects.requireNonNull(estimator, "estimator must not be null");
			return new ContextBudgetInterceptor(this);
		}
	}

}
