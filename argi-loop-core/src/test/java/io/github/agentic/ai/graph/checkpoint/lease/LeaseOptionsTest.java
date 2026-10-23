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
package io.github.agentic.ai.graph.checkpoint.lease;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeaseOptionsTest {

	@Test
	void defaultsUseThirtySecondTtlAndTenSecondHeartbeat() {
		LeaseOptions options = LeaseOptions.defaults();

		assertAll(
				() -> assertEquals(Duration.ofSeconds(30), options.ttl()),
				() -> assertEquals(Duration.ofSeconds(10), options.heartbeatInterval()));
	}

	@Test
	void leaseOptionsRejectNonPositiveFractionalOrOutOfRangeDurations() {
		assertAll(
				() -> assertThrows(NullPointerException.class, () -> new LeaseOptions(null, Duration.ofMillis(1))),
				() -> assertThrows(NullPointerException.class, () -> new LeaseOptions(Duration.ofMillis(1), null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new LeaseOptions(Duration.ZERO, Duration.ofMillis(1))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new LeaseOptions(Duration.ofMillis(1), Duration.ZERO)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new LeaseOptions(Duration.ofNanos(1), Duration.ofMillis(1))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new LeaseOptions(Duration.ofMillis(2), Duration.ofNanos(1))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new LeaseOptions(Duration.ofMillis(10), Duration.ofMillis(10))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new LeaseOptions(Duration.ofDays(1).plusMillis(1), Duration.ofMillis(1))));
	}

	@Test
	void executionLeaseRequiresNamespaceOwnerPositiveFenceAndPositiveExpiry() {
		UUID owner = UUID.randomUUID();

		ExecutionLease lease = new ExecutionLease("thread-a", owner, 7, 100);

		assertAll(
				() -> assertEquals("thread-a", lease.namespace()),
				() -> assertEquals(owner, lease.ownerId()),
				() -> assertEquals(7, lease.fencingToken()),
				() -> assertEquals(100, lease.expiresAtMillis()),
				() -> assertThrows(NullPointerException.class, () -> new ExecutionLease(null, owner, 1, 1)),
				() -> assertThrows(NullPointerException.class, () -> new ExecutionLease("thread-a", null, 1, 1)),
				() -> assertThrows(IllegalArgumentException.class, () -> new ExecutionLease("", owner, 1, 1)),
				() -> assertThrows(IllegalArgumentException.class, () -> new ExecutionLease("thread-a", owner, 0, 1)),
				() -> assertThrows(IllegalArgumentException.class, () -> new ExecutionLease("thread-a", owner, 1, 0)));
	}

	@Test
	void leaseExceptionsExposeOnlyMetadataNeededByCallers() {
		UUID owner = UUID.randomUUID();
		RuntimeException cause = new RuntimeException("backend");
		LeaseBusyException busy = new LeaseBusyException("thread-a");
		LeaseRequiredException required = new LeaseRequiredException("thread-b");
		LeaseLostException lost = new LeaseLostException("thread-c", owner, 9, "expired", cause);

		assertAll(
				() -> assertEquals("thread-a", busy.getNamespace()),
				() -> assertEquals("thread-b", required.getNamespace()),
				() -> assertEquals("thread-c", lost.getNamespace()),
				() -> assertEquals(owner, lost.getOwnerId()),
				() -> assertEquals(9, lost.getFencingToken()),
				() -> assertEquals("expired", lost.getReason()),
				() -> assertSame(cause, lost.getCause()),
				() -> assertFalse(lost.getMessage().contains("state")),
				() -> assertThrows(NullPointerException.class, () -> new LeaseBusyException(null)),
				() -> assertThrows(NullPointerException.class, () -> new LeaseRequiredException(null)),
				() -> assertThrows(NullPointerException.class, () -> new LeaseLostException(null, owner, 1, "x")),
				() -> assertThrows(NullPointerException.class, () -> new LeaseLostException("thread", null, 1, "x")),
				() -> assertThrows(NullPointerException.class, () -> new LeaseLostException("thread", owner, 1, null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new LeaseLostException("thread", owner, 0, "x")));
	}

}
