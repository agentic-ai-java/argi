/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.agent.orchestration;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.agent.AgentResult;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery-boundary tests for the graph {@link ReactAgent} under an execution control:
 * the stop, remaining deadline and ownership carried by the {@link RunnableConfig} must
 * reach the final structured-output repair on both the {@code callResult} and
 * {@code streamResult} paths, so a stop during that repair delivers an interrupted run
 * instead of a completed or validation-failed one.
 */
@DisplayName("ReactAgent delivery inherits the run's execution control")
class ReactAgentExecutionControlDeliveryTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "required": ["status"],
			  "additionalProperties": false,
			  "properties": {
			    "status": {"type": "string"}
			  }
			}""";

	@Test
	@Timeout(15)
	@DisplayName("cancelling the run's control during the graph repair delivers INTERRUPTED, not COMPLETED")
	void cancelledControlDuringGraphRepairInterruptsDelivery() throws Exception {
		final ControllableClient client = ControllableClient.blockingMainModel("plain prose answer");
		final ExecutionControl control = activeControl();
		final RunnableConfig config = RunnableConfig.builder().executionGuard(control).build();
		final ReactAgent agent = agent(client, SCHEMA);

		final Thread stopper = new Thread(() -> {
			try {
				client.awaitRepairStarted(5, TimeUnit.SECONDS);
				control.cancel();
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		stopper.start();
		try {
			final AgentResult result = agent.callResult("give me the status", config);

			assertThat(result.status()).isEqualTo(AgentResult.Status.INTERRUPTED);
			// The stop must not be folded into an ordinary validation failure and must
			// not deliver the repaired output either.
			assertThat(result.issues()).isEmpty();
			assertThat(result.message()).isNull();
			assertThat(client.modelCalls.get()).isEqualTo(1);
			assertThat(client.repairCalls.get()).isEqualTo(1);
			assertThat(client.repairPending.isCancelled()).isTrue();
		}
		finally {
			stopper.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	@Test
	@Timeout(15)
	@DisplayName("a trusted deadline without a guard stops a never-returning graph repair")
	void trustedDeadlineStopsANeverReturningGraphRepair() {
		final ControllableClient client = ControllableClient.blockingMainModel("plain prose answer");
		final TrustedExecutionContext trusted = trustedContext(Instant.now().plusMillis(3000));
		final RunnableConfig config = RunnableConfig.builder().trustedExecutionContext(trusted).build();
		final ReactAgent agent = agent(client, SCHEMA);

		final AgentResult result = agent.callResult("give me the status", config);

		assertThat(result.status()).isEqualTo(AgentResult.Status.INTERRUPTED);
		assertThat(result.issues()).isEmpty();
		assertThat(client.modelCalls.get()).isEqualTo(1);
		assertThat(client.repairCalls.get()).isEqualTo(1);
		assertThat(client.repairPending.isCancelled()).isTrue();
	}

	@Test
	@Timeout(15)
	@DisplayName("cancelling the control during a streamed repair delivers INTERRUPTED")
	void cancelledControlDuringStreamedRepairInterruptsDelivery() throws Exception {
		final ControllableClient client = ControllableClient.streamingMainModel("plain prose");
		final ExecutionControl control = activeControl();
		final RunnableConfig config = RunnableConfig.builder().executionGuard(control).build();
		final ReactAgent agent = agent(client, SCHEMA);

		final Thread stopper = new Thread(() -> {
			try {
				client.awaitRepairStarted(5, TimeUnit.SECONDS);
				control.cancel();
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		stopper.start();
		try {
			final List<AgentResult> results = agent.streamResult("give me the status", config).collectList().block();

			assertThat(results).isNotNull();
			final AgentResult delivery = results.get(results.size() - 1);
			assertThat(delivery.status()).isEqualTo(AgentResult.Status.INTERRUPTED);
			assertThat(delivery.message()).isNull();
			assertThat(client.repairCalls.get()).isEqualTo(1);
			assertThat(client.repairPending.isCancelled()).isTrue();
		}
		finally {
			stopper.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	@Test
	@Timeout(15)
	@DisplayName("a lease-style guard lost during the graph repair delivers INTERRUPTED, not a marked repair failure")
	void leaseStyleGuardLostDuringGraphRepairInterruptsDelivery() throws Exception {
		final LeaseStyleGuard guard = new LeaseStyleGuard();
		final ControllableClient client = ControllableClient.blockingMainModel("plain prose answer");
		final RunnableConfig config = RunnableConfig.builder().executionGuard(guard).build();
		final ReactAgent agent = agent(client, SCHEMA);

		final Thread loser = new Thread(() -> {
			try {
				client.awaitRepairStarted(5, TimeUnit.SECONDS);
				guard.lose();
				// A lease-fenced adapter call fails with the lease's own exception type.
				client.repairPending.completeExceptionally(new IllegalStateException("lease fenced the call"));
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		loser.start();
		try {
			final AgentResult result = agent.callResult("give me the status", config);

			assertThat(result.status()).isEqualTo(AgentResult.Status.INTERRUPTED);
			// The stop must not be folded into a marked repair or validation failure.
			assertThat(result.issues()).isEmpty();
			assertThat(result.message()).isNull();
			assertThat(client.modelCalls.get()).isEqualTo(1);
			assertThat(client.repairCalls.get()).isEqualTo(1);
		}
		finally {
			loser.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	@Test
	@Timeout(15)
	@DisplayName("an expired trusted deadline delivers INTERRUPTED even when the terminal output is schema-valid")
	void expiredDeadlineStopsDeliveryOfAValidOutput() {
		final ControllableClient client = ControllableClient.blockingMainModel("{\"status\":\"ok\"}");
		final TrustedExecutionContext trusted = trustedContext(Instant.now().minusSeconds(1));
		final RunnableConfig config = RunnableConfig.builder().trustedExecutionContext(trusted).build();
		final ReactAgent agent = agent(client, SCHEMA);

		final AgentResult result = agent.callResult("give me the status", config);

		assertThat(result.status()).isEqualTo(AgentResult.Status.INTERRUPTED);
		assertThat(result.issues()).isEmpty();
		assertThat(client.modelCalls.get()).isEqualTo(1);
		assertThat(client.repairCalls.get()).isZero();
	}

	@Test
	@DisplayName("without a config the bounded repair keeps completing the delivery")
	void legacyDeliveryWithoutConfigStillRepairs() {
		final ControllableClient client = ControllableClient.blockingMainModel("plain prose answer");
		client.repairHandler = request -> CompletableFuture.completedFuture(response("{\"status\":\"ok\"}"));
		final ReactAgent agent = agent(client, SCHEMA);

		final AgentResult result = agent.callResult("give me the status");

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.data()).isNotNull();
		assertThat(client.repairCalls.get()).isEqualTo(1);
	}

	private static ReactAgent agent(ChatClient client, String outputSchema) {
		return ReactAgent.builder()
			.name("control-delivery-test")
			.chatClient(client)
			.outputSchema(outputSchema)
			.build();
	}

	/**
	 * Mirrors a checkpoint-lease guard whose stop signal is a plain RuntimeException,
	 * not a cancellation: the framework's own lease scope throws LeaseLostException.
	 */
	private static final class LeaseStyleGuard implements ExecutionGuard {

		private final AtomicBoolean lost = new AtomicBoolean();

		void lose() {
			this.lost.set(true);
		}

		@Override
		public void assertActive() {
			if (this.lost.get()) {
				throw new IllegalStateException("lease lost");
			}
		}

		@Override
		public AutoCloseable onLoss(Runnable cancellation) {
			// The lease scope in this test reports loss only through assertActive.
			return () -> {
			};
		}

	}

	private static ExecutionControl activeControl() {
		return new ExecutionControl(trustedContext(null), Clock.systemUTC());
	}

	private static TrustedExecutionContext trustedContext(final Instant deadline) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "user"))
			.runId("run")
			.correlationId("trace")
			.deadline(deadline)
			.build();
	}

	private static ChatResponse response(final String text) {
		return new ChatResponse("response-id", "test-model", List.of(ChatMessage.assistant(text)), List.of(),
				FinishReason.STOP, new Usage(2L, 3L, 5L), Map.of());
	}

	private static ChatResponse finalStreamResponse(final String text) {
		return new ChatResponse("response-id", "test-model", List.of(ChatMessage.assistant(text)), List.of(),
				FinishReason.STOP, new Usage(2L, 3L, 5L), Map.of("argiFinalResponse", true));
	}

	/**
	 * Serves an invalid terminal answer from the main model (blocking via {@code call} or
	 * streaming via {@code stream}) and hands the bounded repair re-ask to a
	 * test-controlled future that never completes by default, so the delivery boundary
	 * is the only thing that can stop it.
	 */
	private static final class ControllableClient implements ChatClient {

		private final ChatEvent[] streamScript;

		private final String blockingMainText;

		final AtomicInteger modelCalls = new AtomicInteger();

		final AtomicInteger repairCalls = new AtomicInteger();

		final CountDownLatch repairStarted = new CountDownLatch(1);

		final CompletableFuture<ChatResponse> repairPending = new CompletableFuture<>();

		volatile Function<ChatRequest, CompletionStage<ChatResponse>> repairHandler = request -> this.repairPending;

		private ControllableClient(final String blockingMainText, final ChatEvent[] streamScript) {
			this.blockingMainText = blockingMainText;
			this.streamScript = streamScript;
		}

		static ControllableClient blockingMainModel(final String invalidText) {
			return new ControllableClient(invalidText, null);
		}

		static ControllableClient streamingMainModel(final String invalidText) {
			return new ControllableClient(null,
					new ChatEvent[] { ChatEvent.textDelta(invalidText), ChatEvent.response(finalStreamResponse(invalidText)) });
		}

		void awaitRepairStarted(final long timeout, final TimeUnit unit) throws InterruptedException {
			this.repairStarted.await(timeout, unit);
		}

		@Override
		public CompletionStage<ChatResponse> call(final ChatRequest request) {
			// The blocking main-model call comes first; every later call is the
			// structured-output repair re-ask.
			if (this.streamScript == null && this.modelCalls.get() == 0) {
				this.modelCalls.incrementAndGet();
				return CompletableFuture.completedFuture(response(this.blockingMainText));
			}
			this.repairCalls.incrementAndGet();
			this.repairStarted.countDown();
			return this.repairHandler.apply(request);
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(final ChatRequest request) {
			return JdkFlowAdapter.publisherToFlowPublisher(Flux.fromArray(this.streamScript));
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(true, true, false, false, true, true);
		}

	}

}
