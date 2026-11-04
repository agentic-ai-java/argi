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
package io.github.agentic.ai.agent.orchestration.interceptors;

import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.orchestration.interceptor.modelretry.ModelRetryInterceptor;
import io.github.agentic.ai.agent.retry.TypeBasedModelCallFailureClassifier;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import io.github.agentic.ai.runtime.execution.ExecutionStoppedException;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证受控重试：尝试次数上界、失败分类、取消与 deadline 门控、用量不重计。
 */
@Timeout(20)
class ModelRetryInterceptorControlledTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	/** 验证连续失败时的调用次数恰好等于配置的总尝试次数。 */
	@Test
	void theNumberOfCallsIsBoundedByTheConfiguredAttempts() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> interceptor.interceptModel(ModelRequest.builder().build(), request -> {
			attempts.incrementAndGet();
			throw new IllegalStateException("I/O error: connection timeout");
		})).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("maximum number of retries reached");

		assertThat(attempts).hasValue(3);
	}

	/** 验证显式分类器按失败类型判定，正文像超时的永久错误也只调用一次。 */
	@Test
	void anExplicitClassifierDecidesWithoutReadingTheFailureMessage() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder()
			.maxAttempts(3)
			.initialDelay(0)
			.failureClassifier(new TypeBasedModelCallFailureClassifier())
			.build();
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> interceptor.interceptModel(ModelRequest.builder().build(), request -> {
			attempts.incrementAndGet();
			throw new IllegalStateException("I/O error: connection timeout");
		})).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("non-retryable exception");

		assertThat(attempts).hasValue(1);
	}

	/** 验证未显式配置分类器时，既有谓词仍按原语义决定是否重试。 */
	@Test
	void theDefaultConfigurationKeepsTheLegacyPredicate() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();
		AtomicInteger attempts = new AtomicInteger();

		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(), request -> {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("I/O error on POST request");
			}
			return ModelResponse.of(ChatMessage.assistant("recovered"));
		});

		assertThat(attempts).hasValue(2);
		assertThat(response.getMessage().text()).isEqualTo("recovered");
	}

	/** 验证退避期间取消运行后不再派发新的模型调用，停止信号原样透传。 */
	@Test
	void aCancelledRunStopsTheBackoffWithoutAnotherCall() throws Exception {
		ExecutionControl control = control(NOW.plusSeconds(60));
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder()
			.maxAttempts(3)
			.initialDelay(30000)
			.clock(CLOCK)
			.build();
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch called = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread worker = new Thread(() -> {
			try {
				interceptor.interceptModel(request(control), request -> {
					attempts.incrementAndGet();
					called.countDown();
					throw new IllegalStateException("I/O error: connection lost");
				});
			}
			catch (Throwable thrown) {
				failure.set(thrown);
			}
		});
		worker.start();
		try {
			boolean started = called.await(5, TimeUnit.SECONDS);
			control.cancel();
			worker.join(10000);

			assertThat(failure.get()).as("the worker must stop with the signal of the guard")
				.isInstanceOf(ExecutionStoppedException.class);
			assertThat(started).as("the model call must have started").isTrue();
			assertThat(attempts).hasValue(1);
		}
		finally {
			worker.interrupt();
			worker.join(2000);
		}
	}

	/** 验证 deadline 不足以再发起一次尝试时，序列在第一次失败后停止。 */
	@Test
	void aDeadlineThatCannotFitAnotherAttemptStopsTheSequence() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder()
			.maxAttempts(3)
			.initialDelay(5000)
			.clock(CLOCK)
			.build();
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> interceptor.interceptModel(requestWithDeadline(NOW.plusMillis(100)), request -> {
			attempts.incrementAndGet();
			throw new IllegalStateException("I/O error: connection timeout");
		})).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("deadline");

		assertThat(attempts).hasValue(1);
	}

	/** 验证返回给引擎的用量只来自成功的那一次尝试。 */
	@Test
	void theUsageOfTheSuccessfulAttemptIsReportedOnce() {
		ModelRetryInterceptor interceptor = ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build();
		AtomicInteger attempts = new AtomicInteger();
		Usage usage = new Usage(7L, 3L, 10L);

		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(), request -> {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("I/O error on POST request");
			}
			return ModelResponse
				.of(new ChatResponse("id", "model", List.of(ChatMessage.assistant("done")), List.of(), null, usage,
						Map.of()));
		});

		assertThat(attempts).hasValue(2);
		assertThat(response.getChatResponse().usage()).isEqualTo(usage);
	}

	/** 验证复制请求时守卫不会丢失，否则上层语义会被静默降级。 */
	@Test
	void aCopiedRequestKeepsTheGuard() {
		ExecutionControl control = control(NOW.plusSeconds(60));
		ModelRequest request = ModelRequest.builder().executionGuard(control).build();

		assertThat(ModelRequest.builder(request).build().getExecutionGuard()).contains(control);
	}

	private ModelRequest request(ExecutionControl control) {
		return ModelRequest.builder().executionGuard(control).build();
	}

	private ModelRequest requestWithDeadline(Instant deadline) {
		return ModelRequest.builder().trustedExecutionContext(trusted(deadline)).build();
	}

	private ExecutionControl control(Instant deadline) {
		return new ExecutionControl(trusted(deadline), CLOCK);
	}

	private TrustedExecutionContext trusted(Instant deadline) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run")
			.correlationId("correlation")
			.deadline(deadline)
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
	}

}
