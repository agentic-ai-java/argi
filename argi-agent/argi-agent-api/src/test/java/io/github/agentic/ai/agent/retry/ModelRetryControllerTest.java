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

import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import io.github.agentic.ai.runtime.execution.ExecutionStoppedException;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证重试裁决：尝试次数上界、失败分类、deadline 门控与取消透传。
 */
class ModelRetryControllerTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	/** 验证总尝试次数含首次，达到上界后不再继续。 */
	@Test
	void theAttemptCountIsBoundedByThePolicy() {
		ModelRetryController controller = controller(policy(3), null, null);

		for (int attempt = 1; attempt <= 3; attempt++) {
			assertThat(controller.beforeAttempt().isAllowed()).isTrue();
			RetryDecision decision = controller.onFailure(new IOException("temporary outage"));
			if (attempt < 3) {
				assertThat(decision.isRetry()).isTrue();
			}
			else {
				assertThat(decision.stopReason()).contains(RetryStopReason.ATTEMPTS_EXHAUSTED);
			}
		}

		assertThat(controller.attempts()).isEqualTo(3);
	}

	/** 验证不可重试的失败在第一次就终止序列。 */
	@Test
	void aPermanentFailureStopsTheSequenceImmediately() {
		ModelRetryController controller = controller(policy(3),
				failure -> new ModelCallFailure(ModelCallFailureCategory.PERMANENT, failure), null);

		assertThat(controller.beforeAttempt().isAllowed()).isTrue();
		RetryDecision decision = controller.onFailure(new IllegalStateException("rejected"));

		assertThat(decision.stopReason()).contains(RetryStopReason.NON_RETRYABLE);
		assertThat(controller.attempts()).isEqualTo(1);
	}

	/** 验证退避按倍数增长且不超过配置上限。 */
	@Test
	void theBackoffGrowsAndStaysBounded() {
		ModelRetryController controller = controller(new RetryPolicy(5, 100L, 250L, 2.0, retryable(), 0L), null, null);

		controller.beforeAttempt();
		assertThat(controller.onFailure(new IOException("temporary")).delay()).contains(Duration.ofMillis(100L));
		controller.beforeAttempt();
		assertThat(controller.onFailure(new IOException("temporary")).delay()).contains(Duration.ofMillis(200L));
		controller.beforeAttempt();
		assertThat(controller.onFailure(new IOException("temporary")).delay()).contains(Duration.ofMillis(250L));
	}

	/** 验证零退避保持为零，不引入额外等待。 */
	@Test
	void aZeroDelayStaysZero() {
		ModelRetryController controller = controller(new RetryPolicy(3, 0L, 30000L, 2.0, retryable(), 0L), null, null);

		controller.beforeAttempt();

		assertThat(controller.onFailure(new IOException("temporary")).delay()).contains(Duration.ZERO);
	}

	/** 验证 deadline 已过时不再允许发起下一次尝试。 */
	@Test
	void anExpiredDeadlineStopsTheSequence() {
		ModelRetryController controller = controller(policy(3), null, NOW.minusSeconds(1));

		RetryDecision decision = controller.beforeAttempt();

		assertThat(decision.stopReason()).contains(RetryStopReason.DEADLINE_EXCEEDED);
		assertThat(controller.attempts()).isZero();
	}

	/** 验证剩余时间不足以再发起一次尝试时停止序列。 */
	@Test
	void aDeadlineWithoutRoomForAnotherAttemptStopsTheSequence() {
		ModelRetryController controller = controller(new RetryPolicy(3, 100L, 1000L, 2.0, retryable(), 500L), null,
				NOW.plusMillis(100));

		assertThat(controller.beforeAttempt().stopReason()).contains(RetryStopReason.DEADLINE_EXCEEDED);
	}

	/** 验证退避时长会越过 deadline 时直接停止，而不是空等到超时。 */
	@Test
	void aBackoffThatWouldOutliveTheDeadlineStopsTheSequence() {
		ModelRetryController controller = controller(new RetryPolicy(3, 1000L, 1000L, 2.0, retryable(), 0L), null,
				NOW.plusMillis(100));

		controller.beforeAttempt();
		RetryDecision decision = controller.onFailure(new IOException("temporary"));

		assertThat(decision.stopReason()).contains(RetryStopReason.DEADLINE_EXCEEDED);
	}

	/** 验证运行被取消时守卫的停止信号原样透传。 */
	@Test
	void aCancelledRunStopsTheSequence() {
		ExecutionControl control = control(NOW.plusSeconds(60));
		control.cancel();
		ModelRetryController controller = new ModelRetryController(policy(3), new TypeBasedModelCallFailureClassifier(),
				control, CLOCK, NOW.plusSeconds(60));

		assertThatThrownBy(controller::beforeAttempt).isInstanceOf(ExecutionStoppedException.class)
			.satisfies(failure -> assertThat(((ExecutionStoppedException) failure).reason())
				.isEqualTo(ExecutionStoppedException.Reason.CANCELLED));
	}

	/** 验证守卫自身的 deadline 已过时同样阻止新的尝试。 */
	@Test
	void anExpiredGuardStopsTheSequence() {
		ExecutionControl control = control(NOW.minusSeconds(1));
		ModelRetryController controller = new ModelRetryController(policy(3), new TypeBasedModelCallFailureClassifier(),
				control, CLOCK, NOW.minusSeconds(1));

		assertThatThrownBy(controller::beforeAttempt).isInstanceOf(ExecutionStoppedException.class)
			.satisfies(failure -> assertThat(((ExecutionStoppedException) failure).reason())
				.isEqualTo(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED));
	}

	/** 验证序列一旦停止就保持停止，不会再放行新的尝试。 */
	@Test
	void aStoppedSequenceStaysStopped() {
		ModelRetryController controller = controller(policy(3),
				failure -> new ModelCallFailure(ModelCallFailureCategory.PERMANENT, failure), null);

		controller.beforeAttempt();
		assertThat(controller.onFailure(new IllegalStateException("rejected")).stopReason())
			.contains(RetryStopReason.NON_RETRYABLE);
		assertThat(controller.beforeAttempt().stopReason()).contains(RetryStopReason.NON_RETRYABLE);
		assertThat(controller.attempts()).isEqualTo(1);
	}

	/** 验证分类只看失败类型，不看异常正文：正文像超时的永久错误仍然不可重试。 */
	@Test
	void theClassificationDoesNotReadTheFailureMessage() {
		TypeBasedModelCallFailureClassifier classifier = new TypeBasedModelCallFailureClassifier();

		ModelCallFailure failure = classifier.classify(new IllegalStateException("I/O error: connection timeout"));

		assertThat(failure.category()).isEqualTo(ModelCallFailureCategory.UNKNOWN);
		assertThat(failure.isRetryable(retryable())).isFalse();
	}

	/** 验证取消与网络超时按类型归类。 */
	@Test
	void cancellationsAndTimeoutsAreClassifiedByType() {
		TypeBasedModelCallFailureClassifier classifier = new TypeBasedModelCallFailureClassifier();

		assertThat(classifier.classify(new CancellationException("cancelled")).category())
			.isEqualTo(ModelCallFailureCategory.CANCELLED);
		assertThat(classifier.classify(new SocketTimeoutException("read timeout")).category())
			.isEqualTo(ModelCallFailureCategory.TRANSIENT);
		assertThat(classifier.classify(new IllegalStateException("connection lost")).category())
			.isEqualTo(ModelCallFailureCategory.UNKNOWN);
	}

	private ModelRetryController controller(RetryPolicy policy, ModelCallFailureClassifier classifier, Instant deadline) {
		return new ModelRetryController(policy,
				classifier == null ? new TypeBasedModelCallFailureClassifier() : classifier, null, CLOCK, deadline);
	}

	private static RetryPolicy policy(int maxAttempts) {
		return new RetryPolicy(maxAttempts, 100L, 1000L, 2.0, retryable(), 0L);
	}

	private static Set<ModelCallFailureCategory> retryable() {
		return Set.of(ModelCallFailureCategory.TRANSIENT, ModelCallFailureCategory.RATE_LIMITED);
	}

	private static ExecutionControl control(Instant deadline) {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run")
			.correlationId("correlation")
			.deadline(deadline)
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new ExecutionControl(trusted, CLOCK);
	}

}
