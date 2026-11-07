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
 * How accurate a token estimate is.
 *
 * <p>
 * The distinction matters because a budget gate that believes it knows the exact
 * token count behaves differently from a gate that only approximates it. A caller
 * which cannot tolerate an approximation has to plug in an exact estimator instead
 * of trusting a heuristic.
 */
public enum EstimationAccuracy {

	/**
	 * The count is the exact number of tokens the provider will consume.
	 */
	EXACT,

	/**
	 * The count is a heuristic and may be lower or higher than the real usage.
	 */
	APPROXIMATE,

	/**
	 * The count never underestimates the real usage.
	 */
	CONSERVATIVE

}
