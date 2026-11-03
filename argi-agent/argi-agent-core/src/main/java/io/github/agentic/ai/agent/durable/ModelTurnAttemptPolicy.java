/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.agent.durable;

import static java.util.Objects.requireNonNull;

/** Immutable recovery policy and total model-call limit for one logical turn. */
public record ModelTurnAttemptPolicy(MissingResponsePolicy missingResponsePolicy, int maxAttempts) {

	public static final int DEFAULT_MAX_ATTEMPTS = 1;

	/** The default permits the initial call and fails closed on recovery. */
	public static final ModelTurnAttemptPolicy DEFAULT =
			new ModelTurnAttemptPolicy(MissingResponsePolicy.FAIL_CLOSED, DEFAULT_MAX_ATTEMPTS);

	public ModelTurnAttemptPolicy {
		requireNonNull(missingResponsePolicy, "missingResponsePolicy cannot be null");
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be positive");
		}
	}

}
