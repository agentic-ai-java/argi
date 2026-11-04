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

/**
 * Classification of a failed model call.
 *
 * <p>
 * The category is derived from the failure itself, never from the text of an
 * exception message: a permanent rejection that happens to mention a timeout must not
 * be retried.
 */
public enum ModelCallFailureCategory {

	/** A temporary failure that a later attempt may still overcome. */
	TRANSIENT,

	/** The provider asked the caller to slow down. */
	RATE_LIMITED,

	/** The request itself was rejected; another attempt cannot change the outcome. */
	PERMANENT,

	/** The call was cancelled or interrupted and must never be retried. */
	CANCELLED,

	/** The failure could not be classified and is therefore not retried by default. */
	UNKNOWN;

	/**
	 * Whether this category is retryable without further configuration.
	 * @return {@code true} for {@link #TRANSIENT} and {@link #RATE_LIMITED}
	 */
	public boolean isRetryableByDefault() {
		return this == TRANSIENT || this == RATE_LIMITED;
	}

}
