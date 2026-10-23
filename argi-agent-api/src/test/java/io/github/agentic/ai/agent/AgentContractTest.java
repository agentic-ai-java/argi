/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.agent;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionGuard;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.runtime.execution.ExecutionStoppedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AgentContractTest {

	private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static final AgentRequest REQUEST = AgentRequest.messages(List.of(ChatMessage.user("hello")));

	private static final AgentOutcome OUTCOME = AgentOutcome.completed(List.of(ChatMessage.assistant("answer")),
			Map.of("count", 1), new Usage(1L, 2L, 3L));

	static TrustedExecutionContext trusted(Instant deadline) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "user"))
			.runId("run")
			.correlationId("correlation")
			.deadline(deadline)
			.build();
	}

	@Test
	void snapshotsBusinessInputWithoutPromotingIdentity() {
		List<ChatMessage> messages = new ArrayList<>(REQUEST.messages());
		Map<String, Object> inputs = new HashMap<>(Map.of("tenant", "untrusted"));
		AgentRequest request = new AgentRequest(messages, inputs, "resume-1");
		messages.clear();
		inputs.clear();
		assertEquals(1, request.messages().size());
		assertEquals("untrusted", request.inputs().get("tenant"));
		assertEquals("resume-1", request.resumeToken());
		assertNull(REQUEST.resumeToken());
		assertThrows(UnsupportedOperationException.class, () -> request.inputs().put("new", 1));
		assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
		assertThrows(IllegalArgumentException.class, () -> new AgentRequest(List.of(), Map.of(), " "));
		assertThrows(NullPointerException.class, () -> new AgentRequest(null, Map.of(), null));
		assertThrows(NullPointerException.class, () -> new AgentRequest(List.of(), null, null));
	}

	@Test
	void outcomesSeparateSuspensionFromCompletedBusinessOutput() {
		Map<String, Object> prompts = new HashMap<>(Map.of("approval", "required"));
		AgentOutcome.Suspension suspension = new AgentOutcome.Suspension("opaque", prompts);
		prompts.clear();
		AgentOutcome suspended = new AgentOutcome(AgentOutcome.Status.SUSPENDED, List.of(), Map.of(), Usage.unknown(),
				suspension);
		assertEquals(AgentOutcome.Status.SUSPENDED, suspended.status());
		assertEquals("opaque", suspended.suspension().resumeToken());
		assertEquals("required", suspended.suspension().prompts().get("approval"));
		assertEquals(AgentOutcome.Status.COMPLETED, OUTCOME.status());
		assertEquals("answer", OUTCOME.messages().get(0).text());
		assertEquals(1, OUTCOME.outputs().get("count"));
		assertEquals(3L, OUTCOME.usage().totalTokens());
		assertNull(OUTCOME.suspension());
		assertThrows(UnsupportedOperationException.class, () -> suspension.prompts().clear());
		assertThrows(IllegalArgumentException.class, () -> new AgentOutcome(AgentOutcome.Status.COMPLETED, List.of(),
				Map.of(), Usage.unknown(), suspension));
		assertThrows(IllegalArgumentException.class,
				() -> new AgentOutcome(AgentOutcome.Status.SUSPENDED, List.of(), Map.of(), Usage.unknown(), null));
		assertThrows(IllegalArgumentException.class, () -> new AgentOutcome.Suspension("", Map.of()));
		assertThrows(NullPointerException.class, () -> new AgentOutcome.Suspension(null, Map.of()));
		assertThrows(NullPointerException.class, () -> AgentOutcome.completed(List.of(), Map.of(), null));
		assertThrows(NullPointerException.class,
				() -> new AgentOutcome(null, List.of(), Map.of(), Usage.unknown(), null));
	}

	@Test
	void eventsValidateRequiredPayloads() {
		assertEquals("part", new AgentEvent.MessageDelta("message", "part").text());
		assertEquals("message", new AgentEvent.MessageDelta("message", "").messageId());
		AgentEvent.Message message = new AgentEvent.Message("message", ChatMessage.assistant("complete"));
		assertEquals("message", message.messageId());
		assertEquals("complete", message.message().text());
		Map<String, Object> details = new HashMap<>(Map.of("percent", 10));
		AgentEvent.Progress progress = new AgentEvent.Progress("step", details);
		details.clear();
		assertEquals("step", progress.step());
		assertEquals(10, progress.details().get("percent"));
		assertEquals(OUTCOME.usage(), new AgentEvent.UsageUpdate(OUTCOME.usage()).usage());
		assertSame(OUTCOME, new AgentEvent.Finished(OUTCOME).outcome());
		assertThrows(NullPointerException.class, () -> new AgentEvent.MessageDelta(null, ""));
		assertThrows(IllegalArgumentException.class, () -> new AgentEvent.MessageDelta(" ", ""));
		assertThrows(NullPointerException.class, () -> new AgentEvent.MessageDelta("message", null));
		assertThrows(NullPointerException.class, () -> new AgentEvent.Message("message", null));
		assertThrows(NullPointerException.class, () -> new AgentEvent.UsageUpdate(null));
		assertThrows(NullPointerException.class, () -> new AgentEvent.Finished(null));
	}

	@Test
	void invokeWaitsForCompletionAndDoesNotAccumulateEvents() {
		ControlledPublisher publisher = new ControlledPublisher();
		try (AgentExecutionContext context = new AgentExecutionContext(trusted(null), CLOCK)) {
			CompletableFuture<AgentOutcome> result = publisher.agent().invoke(REQUEST, context);
			assertEquals(Long.MAX_VALUE, publisher.demand);
			for (int index = 0; index < 100_000; index++) {
				publisher.next(new AgentEvent.MessageDelta("message", "token"));
			}
			publisher.next(new AgentEvent.Finished(OUTCOME));
			assertFalse(result.isDone());
			publisher.complete();
			assertSame(OUTCOME, result.join());
			assertTrue(publisher.cancelled);
			context.control().assertActive();
			context.control().cancel();
			publisher.next(new AgentEvent.Finished(OUTCOME));
			publisher.complete();
			publisher.subscriber.onError(new IllegalStateException("late"));
			assertSame(OUTCOME, result.join());
		}
	}

	@Test
	void malformedAndFailedStreamsNeverBecomeSuccessfulOutcomes() {
		for (int scenario = 0; scenario < 6; scenario++) {
			ControlledPublisher publisher = new ControlledPublisher();
			try (AgentExecutionContext context = new AgentExecutionContext(trusted(null), CLOCK)) {
				CompletableFuture<AgentOutcome> result = publisher.agent().invoke(REQUEST, context);
				switch (scenario) {
					case 0 -> publisher.complete();
					case 1 -> {
						publisher.next(new AgentEvent.Finished(OUTCOME));
						publisher.next(new AgentEvent.Finished(OUTCOME));
					}
					case 2 -> {
						publisher.next(new AgentEvent.Finished(OUTCOME));
						publisher.next(new AgentEvent.Progress("late", Map.of()));
					}
					case 3 -> publisher.next(null);
					case 4 -> publisher.subscriber.onSubscribe(publisher);
					case 5 -> {
						publisher.next(new AgentEvent.Finished(OUTCOME));
						publisher.subscriber.onError(new IllegalStateException("transport failed"));
					}
					default -> fail("Unexpected scenario");
				}
				assertThrows(CompletionException.class, result::join);
				assertTrue(publisher.cancelled);
			}
		}
	}

	@Test
	void synchronousFailuresBecomeExceptionalFutures() {
		List<Agent> agents = List.of((request, context) -> null, (request, context) -> {
			throw new IllegalArgumentException("creation failed");
		}, (request, context) -> subscriber -> {
			throw new IllegalArgumentException("subscription failed");
		}, (request, context) -> subscriber -> subscriber.onNext(new AgentEvent.Finished(OUTCOME)),
				(request, context) -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
					public void request(long demand) {
						throw new IllegalArgumentException("request failed");
					}

					public void cancel() {
					}
				}));
		for (Agent agent : agents) {
			try (AgentExecutionContext context = new AgentExecutionContext(trusted(null), CLOCK)) {
				assertThrows(CompletionException.class, () -> agent.invoke(REQUEST, context).join());
				assertThrows(NullPointerException.class, () -> agent.invoke(null, context));
				assertThrows(NullPointerException.class, () -> agent.invoke(REQUEST, null));
			}
		}
	}

	@Test
	void cancellationPropagatesInBothDirectionsAndToLateSubscriptions() {
		for (boolean cancelFuture : List.of(true, false)) {
			ControlledPublisher publisher = new ControlledPublisher();
			try (AgentExecutionContext context = new AgentExecutionContext(trusted(null), CLOCK)) {
				CompletableFuture<AgentOutcome> result = publisher.agent().invoke(REQUEST, context);
				if (cancelFuture) {
					assertTrue(result.cancel(true));
				}
				else {
					context.control().cancel();
				}
				assertTrue(result.isCancelled());
				assertTrue(publisher.cancelled);
				assertEquals(ExecutionStoppedException.Reason.CANCELLED,
						assertThrows(ExecutionStoppedException.class, context.control()::assertActive).reason());
			}
		}
		ControlledPublisher delayed = new ControlledPublisher();
		try (AgentExecutionContext context = new AgentExecutionContext(trusted(null), CLOCK)) {
			Agent agent = (request, scope) -> subscriber -> delayed.subscriber = subscriber;
			CompletableFuture<AgentOutcome> result = agent.invoke(REQUEST, context);
			result.cancel(false);
			delayed.subscriber.onSubscribe(delayed);
			assertTrue(delayed.cancelled);
			assertEquals(0, delayed.demand);
		}
	}

	@Test
	void deadlineAndOwnershipLossCannotBeReplacedByBusinessInputs() {
		ControlledPublisher unused = new ControlledPublisher();
		try (AgentExecutionContext expired = new AgentExecutionContext(trusted(NOW), CLOCK)) {
			ExecutionStoppedException failure = assertThrows(ExecutionStoppedException.class,
					() -> unused.agent().invoke(REQUEST, expired).join());
			assertEquals(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED, failure.reason());
			assertNull(unused.subscriber);
		}
		AtomicInteger detached = new AtomicInteger();
		Runnable[] loseOwnership = new Runnable[1];
		ExecutionGuard ownership = new ExecutionGuard() {
			public void assertActive() {
			}

			public AutoCloseable onLoss(Runnable callback) {
				loseOwnership[0] = callback;
				return detached::incrementAndGet;
			}
		};
		TrustedExecutionContext trusted = trusted(null);
		AgentExecutionContext context = new AgentExecutionContext(trusted, CLOCK, ownership);
		assertSame(trusted, context.trusted());
		ControlledPublisher publisher = new ControlledPublisher();
		CompletableFuture<AgentOutcome> result = publisher.agent().invoke(REQUEST, context);
		loseOwnership[0].run();
		assertEquals(ExecutionStoppedException.Reason.OWNERSHIP_LOST,
				assertThrows(ExecutionStoppedException.class, result::join).reason());
		assertTrue(publisher.cancelled);
		context.close();
		context.close();
		assertEquals(1, detached.get());
		assertThrows(NullPointerException.class, () -> new AgentExecutionContext(null, CLOCK));
		assertThrows(NullPointerException.class, () -> new AgentExecutionContext(trusted, null));
	}

	@Test
	void cancellationRacesWithCompletionWithoutLosingCleanup() {
		for (int iteration = 0; iteration < 100; iteration++) {
			ControlledPublisher publisher = new ControlledPublisher();
			try (AgentExecutionContext context = new AgentExecutionContext(trusted(null), CLOCK)) {
				CompletableFuture<AgentOutcome> result = publisher.agent().invoke(REQUEST, context);
				publisher.next(new AgentEvent.Finished(OUTCOME));
				CompletableFuture<Void> cancellation = CompletableFuture.runAsync(context.control()::cancel);
				CompletableFuture<Void> completion = CompletableFuture.runAsync(publisher::complete);
				CompletableFuture.allOf(cancellation, completion).join();
				assertTrue(result.isDone());
				assertTrue(publisher.cancelled);
				if (!result.isCancelled()) {
					assertSame(OUTCOME, result.join());
				}
			}
		}
	}

	@Test
	void deadlinesAreCheckedAtSubscriptionEventsAndCompletion() {
		for (int stage = 0; stage < 3; stage++) {
			AtomicReference<Instant> now = new AtomicReference<>(NOW.minusSeconds(1));
			Clock clock = new Clock() {
				public java.time.ZoneId getZone() {
					return ZoneOffset.UTC;
				}

				public Clock withZone(java.time.ZoneId zone) {
					return this;
				}

				public Instant instant() {
					return now.get();
				}
			};
			ControlledPublisher publisher = new ControlledPublisher();
			try (AgentExecutionContext context = new AgentExecutionContext(trusted(NOW), clock)) {
				Agent agent = stage == 0 ? (request, scope) -> subscriber -> {
					now.set(NOW);
					publisher.subscribe(subscriber);
				} : publisher.agent();
				CompletableFuture<AgentOutcome> result = agent.invoke(REQUEST, context);
				if (stage == 1) {
					now.set(NOW);
					publisher.next(new AgentEvent.Progress("step", Map.of()));
				}
				else if (stage == 2) {
					publisher.next(new AgentEvent.Finished(OUTCOME));
					now.set(NOW);
					publisher.complete();
				}
				assertEquals(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED,
						assertThrows(ExecutionStoppedException.class, result::join).reason());
				assertTrue(publisher.cancelled);
			}
		}
	}

	private static final class ControlledPublisher implements Flow.Publisher<AgentEvent>, Flow.Subscription {

		private Flow.Subscriber<? super AgentEvent> subscriber;

		private volatile boolean cancelled;

		private long demand;

		Agent agent() {
			return (request, context) -> this;
		}

		public void subscribe(Flow.Subscriber<? super AgentEvent> subscriber) {
			this.subscriber = subscriber;
			subscriber.onSubscribe(this);
		}

		public void request(long demand) {
			this.demand = demand;
		}

		public void cancel() {
			cancelled = true;
		}

		void next(AgentEvent event) {
			subscriber.onNext(event);
		}

		void complete() {
			subscriber.onComplete();
		}

	}

}
