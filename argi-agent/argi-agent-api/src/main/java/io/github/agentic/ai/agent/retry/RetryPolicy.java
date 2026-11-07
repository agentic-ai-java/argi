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
 * The bounded configuration of a retry sequence.
 *
 * @param maxAttempts the total number of attempts, the first call included
 * @param initialDelayMillis the backoff before the second attempt
 * @param maxDelayMillis the upper bound of a single backoff
 * @param backoffMultiplier the factor applied to every following backoff
 * @param retryableCategories the failure categories that may be retried
 * @param deadlineReserveMillis the time that must remain before the deadline to
 * start another attempt
 */
public record RetryPolicy(int maxAttempts, long initialDelayMillis, long maxDelayMillis, double backoffMultiplier,
		Set<ModelCallFailureCategory> retryableCategories, long deadlineReserveMillis) {

	public RetryPolicy {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be greater than or equal to 1");
		}
		if (initialDelayMillis < 0) {
			throw new IllegalArgumentException("initialDelayMillis must be greater than or equal to 0");
		}
		if (maxDelayMillis < 0) {
			throw new IllegalArgumentException("maxDelayMillis must be greater than or equal to 0");
		}
		if (!Double.isFinite(backoffMultiplier) || backoffMultiplier < 1.0) {
			throw new IllegalArgumentException("backoffMultiplier must be greater than or equal to 1.0");
		}
		if (deadlineReserveMillis < 0) {
			throw new IllegalArgumentException("deadlineReserveMillis must be greater than or equal to 0");
		}
		Objects.requireNonNull(retryableCategories, "retryableCategories must not be null");
		retryableCategories = Set.copyOf(retryableCategories);
	}

	/**
	 * The default policy: three attempts, one second initial backoff, thirty seconds
	 * upper bound, and the retryable categories that are safe by default.
	 * @return the default policy
	 */
	public static RetryPolicy defaults() {
		return new RetryPolicy(3, 1000L, 30000L, 2.0,
				Set.of(ModelCallFailureCategory.TRANSIENT, ModelCallFailureCategory.RATE_LIMITED), 0L);
	}

	/**
	 * The backoff that follows the given number of attempts.
	 * @param attemptsMade the number of attempts already made, at least one
	 * @return the delay in milliseconds before the next attempt
	 */
	public long delayMillisAfter(int attemptsMade) {
		long delay = initialDelayMillis;
		for (int attempt = 1; attempt < attemptsMade; attempt++) {
			delay = Math.min((long) (delay * backoffMultiplier), maxDelayMillis);
		}
		return delay;
	}

}
