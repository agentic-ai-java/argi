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
package io.github.agentic.ai.runtime.execution;

import io.github.agentic.ai.graph.checkpoint.lease.ExecutionGuard;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExecutionControlTest {

	private final Instant now = Instant.parse("2030-01-01T00:00:00Z");

	private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

	@Test
	void cancellationIsIdempotentAndCallbackFailureDoesNotLoseNotifications() throws Exception {
		ExecutionControl control = new ExecutionControl(context(null), clock);
		AtomicInteger calls = new AtomicInteger();
		control.assertActive();
		control.onLoss(() -> {
			throw new IllegalStateException("callback failed");
		});
		control.onLoss(calls::incrementAndGet);
		control.onLoss(() -> {
			throw new AssertionError("detached");
		}).close();
		control.cancel();
		control.loseOwnership();
		control.cancel();
		control.onLoss(calls::incrementAndGet);
		assertEquals(2, calls.get());
		ExecutionStoppedException stopped = assertThrows(ExecutionStoppedException.class, control::assertActive);
		assertEquals(ExecutionStoppedException.Reason.CANCELLED, stopped.reason());
		assertEquals(1, stopped.getSuppressed().length);
		control.onLoss(control::assertActive);
		assertSame(stopped, assertThrows(ExecutionStoppedException.class, control::assertActive));
		control.close();
		control.close();
	}

	@Test
	void deadlineUsesPersistedMillisecondPrecisionAndFirstStopWins() {
		ExecutionControl active = new ExecutionControl(context(now.plusMillis(1)), clock);
		active.assertActive();
		ExecutionControl expired = new ExecutionControl(context(now.plusNanos(1)), clock);
		AtomicInteger calls = new AtomicInteger();
		expired.onLoss(calls::incrementAndGet);
		ExecutionStoppedException stopped = assertThrows(ExecutionStoppedException.class, expired::assertActive);
		assertEquals(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED, stopped.reason());
		assertEquals(1, calls.get());
		expired.cancel();
		assertSame(stopped, assertThrows(ExecutionStoppedException.class, expired::assertActive));
	}

	@Test
	void ownershipNotificationsAndSynchronousLossStopTheExecution() {
		ExecutionControl owner = new ExecutionControl(context(null), clock);
		ExecutionControl child = new ExecutionControl(context(null), clock, owner);
		child.assertActive();
		owner.cancel();
		assertEquals(ExecutionStoppedException.Reason.OWNERSHIP_LOST,
				assertThrows(ExecutionStoppedException.class, child::assertActive).reason());
		child.close();
	}

	@Test
	void concurrentRegistrationAndCancellationDeliverEachCallbackOnce() throws Exception {
		ExecutionControl control = new ExecutionControl(context(null), clock);
		AtomicInteger calls = new AtomicInteger();
		var executor = Executors.newFixedThreadPool(4);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var futures = new ArrayList<Future<?>>();
			futures.add(executor.submit(() -> {
				start.await();
				control.cancel();
				return null;
			}));
			for (int index = 0; index < 100; index++) {
				futures.add(executor.submit(() -> {
					start.await();
					control.onLoss(calls::incrementAndGet);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(5, TimeUnit.SECONDS);
			}
			control.cancel();
			assertEquals(100, calls.get());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void ownershipChecksPreserveCauseAndClosingDetachesExactlyOnce() {
		AtomicInteger detached = new AtomicInteger();
		IllegalStateException lost = new IllegalStateException("lease expired");
		ExecutionGuard ownership = new ExecutionGuard() {
			@Override
			public void assertActive() {
				throw lost;
			}

			@Override
			public AutoCloseable onLoss(Runnable callback) {
				return detached::incrementAndGet;
			}
		};
		ExecutionControl control = new ExecutionControl(context(null), clock, ownership);
		ExecutionStoppedException stopped = assertThrows(ExecutionStoppedException.class, control::assertActive);
		assertEquals(ExecutionStoppedException.Reason.OWNERSHIP_LOST, stopped.reason());
		assertSame(lost, stopped.getCause());
		control.close();
		control.close();
		assertEquals(1, detached.get());
	}

	@Test
	void detachmentFailureIsExplicitAndInputsAreValidated() {
		ExecutionGuard ownership = new ExecutionGuard() {
			@Override
			public void assertActive() {
			}

			@Override
			public AutoCloseable onLoss(Runnable callback) {
				return () -> {
					throw new Exception("cannot detach");
				};
			}
		};
		ExecutionControl control = new ExecutionControl(context(null), clock, ownership);
		assertThrows(IllegalStateException.class, control::close);
		assertThrows(NullPointerException.class, () -> new ExecutionControl(null, clock));
		assertThrows(NullPointerException.class, () -> new ExecutionControl(context(null), null));
		assertThrows(NullPointerException.class, () -> control.onLoss(null));
	}

	private TrustedExecutionContext context(Instant deadline) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "user"))
			.runId("run")
			.correlationId("trace")
			.deadline(deadline)
			.build();
	}

}
