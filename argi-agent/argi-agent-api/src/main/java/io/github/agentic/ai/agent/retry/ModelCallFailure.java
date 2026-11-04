/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.agent.retry;

import java.util.Objects;
import java.util.Set;

/**
 * A classified model call failure.
 *
 * @param category the classification of the failure
 * @param cause the original failure, may be {@code null} when the failure was not
 * classified from a throwable
 */
public record ModelCallFailure(ModelCallFailureCategory category, Throwable cause) {

	public ModelCallFailure {
		Objects.requireNonNull(category, "category must not be null");
	}

	/**
	 * Whether the failure may be retried under the given policy.
	 * @param retryableCategories the categories the policy allows to retry
	 * @return {@code true} when the category is retryable
	 */
	public boolean isRetryable(Set<ModelCallFailureCategory> retryableCategories) {
		return retryableCategories.contains(category);
	}

}
