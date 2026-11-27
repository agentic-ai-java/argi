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
package io.github.agentic.ai.agent.fallback;

import io.github.agentic.ai.agent.retry.ModelCallFailureCategory;

import java.util.Set;

/** Switch limits for an ordered list of clients. Total attempts include the primary and retries. */
public record ModelFallbackPolicy(Set<ModelCallFailureCategory> switchableCategories, int maxSwitches,
		int maxTotalAttempts) {

	public ModelFallbackPolicy {
		switchableCategories = Set.copyOf(switchableCategories);
		if (maxSwitches < 0 || maxTotalAttempts < 1 || switchableCategories.contains(ModelCallFailureCategory.CANCELLED)) {
			throw new IllegalArgumentException("Invalid fallback limits or cancellation category");
		}
	}

	/** Existing builders switched on any non-cancellation failure. */
	public static ModelFallbackPolicy legacy() {
		return new ModelFallbackPolicy(Set.of(ModelCallFailureCategory.TRANSIENT, ModelCallFailureCategory.RATE_LIMITED,
				ModelCallFailureCategory.PERMANENT, ModelCallFailureCategory.UNKNOWN), Integer.MAX_VALUE, Integer.MAX_VALUE);
	}

}
