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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a single retry decision: start the attempt, wait and retry, or stop
 * with a stable reason.
 */
public final class RetryDecision {

	private static final RetryDecision ALLOWED = new RetryDecision(null, null);

	private final Duration delay;

	private final RetryStopReason stopReason;

	private RetryDecision(Duration delay, RetryStopReason stopReason) {
		this.delay = delay;
		this.stopReason = stopReason;
	}

	/**
	 * The attempt may start.
	 * @return the shared allow decision
	 */
	public static RetryDecision allow() {
		return ALLOWED;
	}

	/**
	 * The attempt may start again after the given delay.
	 * @param delay the backoff to wait before the next attempt
	 * @return a retry decision
	 */
	public static RetryDecision retryAfter(Duration delay) {
		Objects.requireNonNull(delay, "delay must not be null");
		if (delay.isNegative()) {
			throw new IllegalArgumentException("delay must not be negative");
		}
		return new RetryDecision(delay, null);
	}

	/**
	 * The sequence stops.
	 * @param reason why the sequence stopped
	 * @return a stop decision
	 */
	public static RetryDecision stop(RetryStopReason reason) {
		Objects.requireNonNull(reason, "reason must not be null");
		return new RetryDecision(null, reason);
	}

	/**
	 * Whether the attempt may start.
	 * @return {@code true} when neither a delay nor a stop reason is present
	 */
	public boolean isAllowed() {
		return delay == null && stopReason == null;
	}

	/**
	 * Whether another attempt follows after a delay.
	 * @return {@code true} when a delay is present
	 */
	public boolean isRetry() {
		return delay != null;
	}

	/**
	 * Whether the sequence stopped.
	 * @return {@code true} when a stop reason is present
	 */
	public boolean isStop() {
		return stopReason != null;
	}

	/**
	 * The backoff before the next attempt.
	 * @return the delay, empty when the decision is not a retry
	 */
	public Optional<Duration> delay() {
		return Optional.ofNullable(delay);
	}

	/**
	 * Why the sequence stopped.
	 * @return the stop reason, empty when the sequence did not stop
	 */
	public Optional<RetryStopReason> stopReason() {
		return Optional.ofNullable(stopReason);
	}

	@Override
	public String toString() {
		if (isRetry()) {
			return "RetryDecision[retryAfter=" + delay + "]";
		}
		if (isStop()) {
			return "RetryDecision[stop=" + stopReason + "]";
		}
		return "RetryDecision[allow]";
	}

}
