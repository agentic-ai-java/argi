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

import io.github.agentic.ai.runtime.execution.ExecutionGuard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a model call may start another attempt.
 *
 * <p>
 * One controller belongs to one model call. It owns the attempt counter, the
 * classification of the failures it observes and the deadline of the run, so that a
 * retry sequence is bounded on every axis at once: the number of attempts, the
 * retryability of the failure and the remaining time.
 *
 * <p>
 * The controller never waits. The caller owns the backoff, and must re-check the
 * controller before every attempt, because a cancellation or a deadline can stop the
 * run while the caller is waiting.
 */
public final class ModelRetryController {

	private final RetryPolicy policy;

	private final ModelCallFailureClassifier classifier;

	private final ExecutionGuard guard;

	private final Clock clock;

	private final Instant deadline;

	private int attempts;

	private volatile RetryStopReason stopReason;

	/**
	 * Creates a controller.
	 * @param policy the bounded retry configuration
	 * @param classifier the failure classifier
	 * @param guard the run guard, may be {@code null} for an uncontrolled run
	 * @param clock the clock used for the deadline checks
	 * @param deadline the deadline of the run, may be {@code null} when the run has
	 * none
	 */
	public ModelRetryController(RetryPolicy policy, ModelCallFailureClassifier classifier, ExecutionGuard guard,
			Clock clock, Instant deadline) {
		this.policy = Objects.requireNonNull(policy, "policy must not be null");
		this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
		this.guard = guard;
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
		this.deadline = deadline;
	}

	/**
	 * Creates a controller that takes its deadline from the guard.
	 * @param policy the bounded retry configuration
	 * @param classifier the failure classifier
	 * @param guard the run guard, may be {@code null} for an uncontrolled run
	 * @param clock the clock used for the deadline checks
	 * @return the controller
	 */
	public static ModelRetryController forGuard(RetryPolicy policy, ModelCallFailureClassifier classifier,
			ExecutionGuard guard, Clock clock) {
		Instant deadline = guard == null ? null : guard.deadline().orElse(null);
		return new ModelRetryController(policy, classifier, guard, clock, deadline);
	}

	/**
	 * Checks whether the next attempt may start and counts it.
	 * @return {@link RetryDecision#allow()} when the attempt may start, otherwise the
	 * stop decision
	 */
	public RetryDecision beforeAttempt() {
		if (stopReason != null) {
			return RetryDecision.stop(stopReason);
		}
		if (guard != null) {
			// A stopped run is not a retryable failure: the guard owns the reason.
			guard.assertActive();
		}
		RetryDecision deadlineDecision = checkDeadline();
		if (deadlineDecision.isStop()) {
			return deadlineDecision;
		}
		attempts++;
		return RetryDecision.allow();
	}

	/**
	 * Checks whether the deadline still allows another attempt, without counting an
	 * attempt.
	 * @return {@link RetryDecision#allow()} when the attempt may start, otherwise the
	 * stop decision
	 */
	public RetryDecision checkAttemptAllowed() {
		if (stopReason != null) {
			return RetryDecision.stop(stopReason);
		}
		if (guard != null) {
			guard.assertActive();
		}
		return checkDeadline();
	}

	/**
	 * Decides what happens after a failed attempt.
	 * @param failure the failure raised by the attempt
	 * @return the retry decision with its backoff, or the stop decision
	 */
	public RetryDecision onFailure(Throwable failure) {
		return onFailure(failure, attempts);
	}

	/**
	 * Decides what happens after a failed attempt of a sequence that counts its
	 * attempts itself, such as a streaming response whose subscriptions each own their
	 * attempt budget.
	 * @param failure the failure raised by the attempt
	 * @param attemptsMade the number of attempts already made by that sequence
	 * @return the retry decision with its backoff, or the stop decision
	 */
	public RetryDecision onFailure(Throwable failure, int attemptsMade) {
		if (stopReason != null) {
			return RetryDecision.stop(stopReason);
		}
		ModelCallFailure classified = classifier.classify(failure);
		if (classified == null) {
			classified = new ModelCallFailure(ModelCallFailureCategory.UNKNOWN, failure);
		}
		if (classified.category() == ModelCallFailureCategory.CANCELLED) {
			return stop(RetryStopReason.CANCELLED);
		}
		if (!classified.isRetryable(policy.retryableCategories())) {
			return stop(RetryStopReason.NON_RETRYABLE);
		}
		if (attemptsMade >= policy.maxAttempts()) {
			return stop(RetryStopReason.ATTEMPTS_EXHAUSTED);
		}
		long delay = policy.delayMillisAfter(attemptsMade);
		if (deadline != null && !clock.instant().plusMillis(delay).isBefore(deadline)) {
			return stop(RetryStopReason.DEADLINE_EXCEEDED);
		}
		return RetryDecision.retryAfter(Duration.ofMillis(delay));
	}

	/**
	 * The number of attempts that were allowed to start.
	 * @return the attempt count
	 */
	public int attempts() {
		return attempts;
	}

	/**
	 * Why the sequence stopped.
	 * @return the stop reason, empty while the sequence may continue
	 */
	public Optional<RetryStopReason> stopReason() {
		return Optional.ofNullable(stopReason);
	}

	/**
	 * The policy of this controller.
	 * @return the retry policy
	 */
	public RetryPolicy policy() {
		return policy;
	}

	/**
	 * The deadline of the run.
	 * @return the deadline, empty when the run has none
	 */
	public Optional<Instant> deadline() {
		return Optional.ofNullable(deadline);
	}

	/**
	 * The guard of the run.
	 * @return the guard, empty for an uncontrolled run
	 */
	public Optional<ExecutionGuard> guard() {
		return Optional.ofNullable(guard);
	}

	private RetryDecision checkDeadline() {
		if (deadline == null) {
			return RetryDecision.allow();
		}
		Instant now = clock.instant();
		if (!now.isBefore(deadline)) {
			return stop(RetryStopReason.DEADLINE_EXCEEDED);
		}
		long remaining = Duration.between(now, deadline).toMillis();
		if (remaining <= policy.deadlineReserveMillis()) {
			return stop(RetryStopReason.DEADLINE_EXCEEDED);
		}
		return RetryDecision.allow();
	}

	private RetryDecision stop(RetryStopReason reason) {
		this.stopReason = reason;
		return RetryDecision.stop(reason);
	}

}
