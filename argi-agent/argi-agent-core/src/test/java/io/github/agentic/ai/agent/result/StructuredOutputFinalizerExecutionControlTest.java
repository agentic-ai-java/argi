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
package io.github.agentic.ai.agent.result;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.agentic.ai.agent.AgentResult;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import io.github.agentic.ai.runtime.execution.ExecutionStoppedException;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Execution-control boundary of {@link StructuredOutputFinalizer}: a run that is
 * cancelled, past its deadline or has lost ownership must keep those semantics through
 * the optional repair re-ask instead of degrading them to a validation failure.
 */
@DisplayName("StructuredOutputFinalizer execution control boundary")
class StructuredOutputFinalizerExecutionControlTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "required": ["status"],
			  "additionalProperties": false,
			  "properties": {
			    "status": {"type": "string"}
			  }
			}""";

	private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

	@Test
	@DisplayName("a control cancelled before the repair propagates the stop without calling the repair client")
	void preCancelledControlStopsBeforeRepair() {
		final ControlledClient client = ControlledClient.neverCalled();
		final ExecutionControl control = activeControl();
		control.cancel();
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final ExecutionStoppedException stopped = catchThrowableOfType(
				() -> repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control),
				ExecutionStoppedException.class);

		assertThat(stopped.reason()).isEqualTo(ExecutionStoppedException.Reason.CANCELLED);
		assertThat(client.calls.get()).isZero();
	}

	@Test
	@DisplayName("an expired deadline propagates the stop without calling the repair client")
	void expiredDeadlineStopsBeforeRepair() {
		final ControlledClient client = ControlledClient.neverCalled();
		final ExecutionControl control = new ExecutionControl(trustedContext(NOW.minusNanos(1)),
				Clock.fixed(NOW, ZoneOffset.UTC));
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final ExecutionStoppedException stopped = catchThrowableOfType(
				() -> repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control),
				ExecutionStoppedException.class);

		assertThat(stopped.reason()).isEqualTo(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED);
		assertThat(client.calls.get()).isZero();
	}

	@Test
	@DisplayName("lost ownership propagates the stop without calling the repair client")
	void lostOwnershipStopsBeforeRepair() {
		final ControlledClient client = ControlledClient.neverCalled();
		final ExecutionControl control = activeControl();
		control.loseOwnership();
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final ExecutionStoppedException stopped = catchThrowableOfType(
				() -> repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control),
				ExecutionStoppedException.class);

		assertThat(stopped.reason()).isEqualTo(ExecutionStoppedException.Reason.OWNERSHIP_LOST);
		assertThat(client.calls.get()).isZero();
	}

	@Test
	@DisplayName("a stop raised while the repair call is in flight cancels that call and propagates the stop")
	void inFlightStopCancelsTheRepairCallAndPropagates() throws Exception {
		final CountDownLatch callStarted = new CountDownLatch(1);
		final CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		final ControlledClient client = new ControlledClient(request -> {
			callStarted.countDown();
			return pending;
		});
		final ExecutionControl control = activeControl();
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final Thread stopper = new Thread(() -> {
			try {
				callStarted.await(3, TimeUnit.SECONDS);
				control.cancel();
				pending.completeExceptionally(new CancellationException("repair call cancelled"));
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		stopper.start();
		try {
			final ExecutionStoppedException stopped = catchThrowableOfType(
					() -> repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control),
					ExecutionStoppedException.class);

			assertThat(stopped.reason()).isEqualTo(ExecutionStoppedException.Reason.CANCELLED);
			assertThat(client.calls.get()).isEqualTo(1);
		}
		finally {
			stopper.join(TimeUnit.SECONDS.toMillis(3));
		}
	}

	@Test
	@DisplayName("a deadline that expires during the repair call stops the delivery after the call")
	void deadlineExpiryDuringRepairStopsTheDelivery() {
		final MutableClock clock = new MutableClock(NOW);
		final ControlledClient client = new ControlledClient(request -> {
			clock.advanceTo(NOW.plusSeconds(30));
			return CompletableFuture.completedFuture(repairResponse("{\"status\":\"ok\"}"));
		});
		final ExecutionControl control = new ExecutionControl(trustedContext(NOW.plusSeconds(29)), clock);
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final ExecutionStoppedException stopped = catchThrowableOfType(
				() -> repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control),
				ExecutionStoppedException.class);

		assertThat(stopped.reason()).isEqualTo(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED);
		assertThat(client.calls.get()).isEqualTo(1);
	}

	@Test
	@Timeout(10)
	@DisplayName("a repair request that never returns is cancelled once the deadline expires")
	void neverReturningRepairRequestIsCancelledAtTheDeadline() {
		final CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		final ControlledClient client = new ControlledClient(request -> pending);
		final ExecutionControl control = new ExecutionControl(trustedContext(Instant.now().plusMillis(500)),
				Clock.systemUTC());
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final long startNanos = System.nanoTime();
		final ExecutionStoppedException stopped = catchThrowableOfType(
				() -> repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control),
				ExecutionStoppedException.class);

		assertThat(stopped.reason()).isEqualTo(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED);
		assertThat(client.calls.get()).isEqualTo(1);
		// The deadline boundary must cancel the real in-flight request, not only stop
		// the waiting thread.
		assertThat(pending.isCancelled()).isTrue();
		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)).isLessThan(4000L);
	}

	@Test
	@Timeout(10)
	@DisplayName("a repair response completing while the deadline is far away delivers normally")
	void completionRacingTheDeadlineDeliversNormally() throws Exception {
		final CountDownLatch callStarted = new CountDownLatch(1);
		final CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		final ControlledClient client = new ControlledClient(request -> {
			callStarted.countDown();
			return pending;
		});
		final ExecutionControl control = new ExecutionControl(trustedContext(Instant.now().plusSeconds(30)),
				Clock.systemUTC());
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final Thread completer = new Thread(() -> {
			try {
				callStarted.await(3, TimeUnit.SECONDS);
				pending.complete(repairResponse("{\"status\":\"ok\"}"));
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		completer.start();
		try {
			final AgentResult result = repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control);

			assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
			assertThat(result.data()).isNotNull();
			assertThat(client.calls.get()).isEqualTo(1);
		}
		finally {
			completer.join(TimeUnit.SECONDS.toMillis(3));
		}
	}

	@Test
	@DisplayName("an active control leaves a successful repair untouched")
	void activeControlAllowsSuccessfulRepair() {
		final ControlledClient client = ControlledClient.returning("{\"status\":\"ok\"}");
		final ExecutionControl control = activeControl();
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final AgentResult result = repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control);

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.data()).isNotNull();
		assertThat(client.calls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("a failing repair client is marked and keeps the original validation failure")
	void repairClientFailureIsMarkedAndKeepsTheOriginalFailure() {
		final ControlledClient client = ControlledClient.failingWith(new IllegalStateException("repair backend down"));
		final ExecutionControl control = activeControl();
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);
		final ChatMessage terminal = invalidTerminal();

		final AgentResult result = repairing.finalizeOutput(terminal, SCHEMA, Usage.unknown(), control);

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		assertThat(result.issues()).hasSize(2);
		assertThat(result.issues().get(0).errorCode()).isEqualTo(AgentResult.ERROR_CODE_REPAIR_CALL_FAILED);
		assertThat(result.issues().get(1).errorCode()).isEqualTo(AgentResult.ERROR_CODE_INVALID_JSON);
		assertThat(result.message()).isSameAs(terminal);
		assertThat(client.calls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("a client-side cancellation under an active control stays a cancellation")
	void clientSideCancellationUnderAnActiveControlPropagates() {
		final ControlledClient client = new ControlledClient(request -> {
			throw new CancellationException("repair client cancelled itself");
		});
		final ExecutionControl control = activeControl();
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final CancellationException cancelled = catchThrowableOfType(
				() -> repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown(), control),
				CancellationException.class);

		assertThat(cancelled).isNotInstanceOf(ExecutionStoppedException.class);
	}

	@Test
	@DisplayName("the legacy overload without a control degrades a cancellation to a marked repair failure")
	void legacyOverloadWithoutControlDegradesCancellation() {
		final ControlledClient client = new ControlledClient(request -> {
			throw new CancellationException("repair client cancelled itself");
		});
		final StructuredOutputFinalizer repairing = new StructuredOutputFinalizer(client);

		final AgentResult result = repairing.finalizeOutput(invalidTerminal(), SCHEMA, Usage.unknown());

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		assertThat(result.issues()).hasSize(2);
		assertThat(result.issues().get(0).errorCode()).isEqualTo(AgentResult.ERROR_CODE_REPAIR_CALL_FAILED);
		assertThat(result.issues().get(1).errorCode()).isEqualTo(AgentResult.ERROR_CODE_INVALID_JSON);
		assertThat(client.calls.get()).isEqualTo(1);
	}

	private static ChatMessage invalidTerminal() {
		return ChatMessage.assistant("not json");
	}

	private static ExecutionControl activeControl() {
		return new ExecutionControl(trustedContext(null), Clock.fixed(NOW, ZoneOffset.UTC));
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

	private static ChatResponse repairResponse(final String text) {
		return new ChatResponse("repair-id", "test-model", List.of(ChatMessage.assistant(text)), List.of(), null,
				new Usage(1L, 1L, 2L), Map.of());
	}

	/** A repair client whose behavior is fully scripted by the test. */
	private static final class ControlledClient implements ChatClient {

		private final AtomicInteger calls = new AtomicInteger();

		private final Function<ChatRequest, CompletionStage<ChatResponse>> handler;

		private ControlledClient(final Function<ChatRequest, CompletionStage<ChatResponse>> handler) {
			this.handler = handler;
		}

		static ControlledClient neverCalled() {
			return new ControlledClient(request -> {
				throw new AssertionError("the repair client must not be called");
			});
		}

		static ControlledClient returning(final String repairedText) {
			return new ControlledClient(
					request -> CompletableFuture.completedFuture(repairResponse(repairedText)));
		}

		static ControlledClient failingWith(final RuntimeException failure) {
			return new ControlledClient(request -> {
				throw failure;
			});
		}

		@Override
		public CompletionStage<ChatResponse> call(final ChatRequest request) {
			this.calls.incrementAndGet();
			return this.handler.apply(request);
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(final ChatRequest request) {
			throw new UnsupportedOperationException("Streaming is unavailable");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

	/** A clock the scripted repair client can advance to expire a deadline mid-call. */
	private static final class MutableClock extends Clock {

		private volatile Instant current;

		private MutableClock(final Instant start) {
			this.current = start;
		}

		private void advanceTo(final Instant next) {
			this.current = next;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(final ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.current;
		}

	}

}
