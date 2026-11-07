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

import io.github.agentic.ai.agent.interceptor.ModelRequest;

/**
 * Estimates how many tokens a model request consumes.
 *
 * <p>
 * The estimator is the replaceable part of the budget contract. The default
 * implementation approximates, a provider specific implementation can be exact. The
 * budget gate does not care which one it is given, it only reads the resulting
 * estimate and its accuracy.
 */
@FunctionalInterface
public interface RequestTokenEstimator {

	/**
	 * Estimates the token consumption of a request.
	 * @param request the request to estimate, must not be null
	 * @param budget the budget of the target model, must not be null
	 * @return the estimate, never null
	 */
	RequestTokenEstimate estimate(ModelRequest request, ContextWindowBudget budget);

}
