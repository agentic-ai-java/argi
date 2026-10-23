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
package io.github.agentic.ai.graph.checkpoint.savers;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointConflictException;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver.Tag;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionLease;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseBusyException;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseRequiredException;
import io.github.agentic.ai.graph.serializer.StateSerializer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MemoryLeasedCheckpointSaverTest {

	@Test
	void activeLeaseExcludesSecondOwnerAndExpiredOwnerCannotWriteAfterReacquire() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig config = config("main");
		ExecutionLease first = saver.acquireLease(config, UUID.randomUUID());

		assertThrows(LeaseBusyException.class, () -> saver.acquireLease(config, UUID.randomUUID()));
		assertEquals(first.fencingToken(), retainedFence(saver, config));

		saver.putIfLeasedVersion(config, checkpoint("seed", "seed"), 0, first);
		assertEquals(1, saver.getVersioned(config).revision());

		clock.advance(Duration.ofSeconds(31));
		ExecutionLease second = saver.acquireLease(config, UUID.randomUUID());

		assertAll(
				() -> assertTrue(second.fencingToken() > first.fencingToken()),
				() -> assertThrows(LeaseLostException.class,
						() -> saver.putIfLeasedVersion(config, checkpoint("stale", "stale"), 1, first)),
				() -> assertEquals(1, saver.getVersioned(config).revision()),
				() -> assertEquals("seed", saver.get(config).orElseThrow().getState().get("value")));
	}

	@Test
	void ownerlessMutationsFailClosedWhileReadsRemainAvailable() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		LeasedCheckpointSaver leased = saver;
		RunnableConfig config = config("ownerless");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		saver.putIfLeasedVersion(config, checkpoint("visible", "visible"), 0, lease);

		assertAll(
				() -> assertEquals("visible", leased.get(config).orElseThrow().getState().get("value")),
				() -> assertEquals(1, leased.getVersioned(config).revision()),
				() -> assertThrows(LeaseRequiredException.class, () -> leased.put(config, checkpoint("ownerless", "x"))),
				() -> assertThrows(LeaseRequiredException.class, () -> leased.release(config)),
				() -> assertThrows(LeaseRequiredException.class,
						() -> leased.putIfVersion(config, checkpoint("ownerless", "x"), 1)),
				() -> assertThrows(LeaseRequiredException.class, () -> leased.releaseIfVersion(config, 1)),
				() -> assertEquals(1, leased.getVersioned(config).revision()));
	}

	@Test
	void renewAndReleaseRequireExactFenceWithoutChangingCheckpointRevision() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig config = config("renew");
		UUID owner = UUID.randomUUID();
		ExecutionLease lease = saver.acquireLease(config, owner);
		saver.putIfLeasedVersion(config, checkpoint("first", "first"), 0, lease);
		ExecutionLease wrongOwner = new ExecutionLease(lease.namespace(), UUID.randomUUID(), lease.fencingToken(),
				lease.expiresAtMillis());
		ExecutionLease wrongFence = new ExecutionLease(lease.namespace(), owner, lease.fencingToken() + 1,
				lease.expiresAtMillis());

		clock.advance(Duration.ofSeconds(5));
		ExecutionLease renewed = saver.renewLease(config, lease);

		assertAll(
				() -> assertEquals(1, saver.getVersioned(config).revision()),
				() -> assertTrue(renewed.expiresAtMillis() > lease.expiresAtMillis()),
				() -> assertThrows(LeaseLostException.class, () -> saver.renewLease(config, wrongOwner)),
				() -> assertThrows(LeaseLostException.class, () -> saver.renewLease(config, wrongFence)),
				() -> assertThrows(LeaseLostException.class,
						() -> saver.putIfLeasedVersion(config, checkpoint("wrong-owner", "wrong-owner"), 1, wrongOwner)),
				() -> assertThrows(LeaseLostException.class,
						() -> saver.putIfLeasedVersion(config, checkpoint("wrong-fence", "wrong-fence"), 1, wrongFence)),
				() -> assertThrows(LeaseLostException.class, () -> saver.releaseIfLeasedVersion(config, 1, wrongOwner)),
				() -> assertThrows(LeaseLostException.class, () -> saver.releaseIfLeasedVersion(config, 1, wrongFence)),
				() -> assertFalse(saver.releaseLease(config, wrongOwner)),
				() -> assertFalse(saver.releaseLease(config, wrongFence)),
				() -> assertEquals(1, saver.getVersioned(config).revision()));

		assertTrue(saver.releaseLease(config, renewed));
		assertEquals(1, saver.getVersioned(config).revision());
		assertFalse(saver.releaseLease(config, renewed));
	}

	@Test
	void expiredLeaseCannotRenewAndCannotEraseNewOwner() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig config = config("expired-renew");
		ExecutionLease first = saver.acquireLease(config, UUID.randomUUID());

		clock.advance(Duration.ofSeconds(31));

		assertThrows(LeaseLostException.class, () -> saver.renewLease(config, first));

		ExecutionLease second = saver.acquireLease(config, UUID.randomUUID());

		assertAll(
				() -> assertFalse(saver.releaseLease(config, first)),
				() -> assertThrows(LeaseLostException.class,
						() -> saver.releaseIfLeasedVersion(config, 0, first)),
				() -> assertTrue(saver.releaseLease(config, second)));
	}

	@Test
	void renewUsesClockAfterWaitingForContendedMonitor() throws Exception {
		MutableClock clock = new MutableClock();
		BlockingStateSerializer serializer = new BlockingStateSerializer();
		MemoryLeasedCheckpointSaver saver = new MemoryLeasedCheckpointSaver(serializer, LeaseOptions.defaults(), clock);
		RunnableConfig config = config("contended-renew");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		AtomicReference<Throwable> holderFailure = new AtomicReference<>();
		Thread holder = blockingPutThread(saver, config, lease, holderFailure);
		holder.start();
		serializer.awaitBlockedInWrite();

		AtomicReference<Object> renewalResult = new AtomicReference<>();
		Thread renewer = new Thread(() -> renewalResult.set(renewResult(saver, config, lease)),
				"lease-renew-contender");
		renewer.start();
		awaitMonitorBlocked(renewer);

		clock.advance(Duration.ofSeconds(31));
		serializer.releaseWrite();
		join(holder);
		join(renewer);

		assertAll(
				() -> assertNoThreadFailure(holderFailure),
				() -> assertInstanceOf(LeaseLostException.class, renewalResult.get()),
				() -> assertEquals(1, saver.getVersioned(config).revision()));
	}

	@Test
	void acquireUsesClockAfterWaitingForContendedMonitor() throws Exception {
		MutableClock clock = new MutableClock();
		BlockingStateSerializer serializer = new BlockingStateSerializer();
		MemoryLeasedCheckpointSaver saver = new MemoryLeasedCheckpointSaver(serializer, LeaseOptions.defaults(), clock);
		RunnableConfig config = config("contended-acquire");
		ExecutionLease first = saver.acquireLease(config, UUID.randomUUID());
		AtomicReference<Throwable> holderFailure = new AtomicReference<>();
		Thread holder = blockingPutThread(saver, config, first, holderFailure);
		holder.start();
		serializer.awaitBlockedInWrite();

		UUID secondOwner = UUID.randomUUID();
		AtomicReference<Object> acquireResult = new AtomicReference<>();
		Thread acquirer = new Thread(() -> acquireResult.set(acquireResult(saver, config, secondOwner)),
				"lease-acquire-contender");
		acquirer.start();
		awaitMonitorBlocked(acquirer);

		clock.advance(Duration.ofSeconds(31));
		long expectedDeadline = clock.millis() + LeaseOptions.defaults().ttl().toMillis();
		serializer.releaseWrite();
		join(holder);
		join(acquirer);

		ExecutionLease second = assertInstanceOf(ExecutionLease.class, acquireResult.get());
		assertAll(
				() -> assertNoThreadFailure(holderFailure),
				() -> assertEquals(secondOwner, second.ownerId()),
				() -> assertTrue(second.fencingToken() > first.fencingToken()),
				() -> assertEquals(expectedDeadline, second.expiresAtMillis()),
				() -> assertEquals(1, saver.getVersioned(config).revision()));
	}

	@Test
	void leasedConditionalReleaseCreatesPositiveTombstoneAndNamespaceCanBeReused() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig config = config("release");
		ExecutionLease first = saver.acquireLease(config, UUID.randomUUID());

		Tag emptyRelease = saver.releaseIfLeasedVersion(config, 0, first);

		assertAll(
				() -> assertTrue(emptyRelease.checkpoints().isEmpty()),
				() -> assertEquals(1, saver.getVersioned(config).revision()),
				() -> assertTrue(saver.getVersioned(config).checkpoint().isEmpty()));

		saver.putIfLeasedVersion(config, checkpoint("after-release", "live"), 1, first);

		assertAll(
				() -> assertEquals(2, saver.getVersioned(config).revision()),
				() -> assertEquals("live", saver.get(config).orElseThrow().getState().get("value")));
	}

	@Test
	void selectedCheckpointRetentionAndHistoryUseTheLeasedNamespaceRevision() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig config = RunnableConfig.builder().threadId("history").checkpointsNumRetained(2).build();
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());

		saver.putIfLeasedVersion(config, checkpoint("first", "1"), 0, lease);
		saver.putIfLeasedVersion(config, checkpoint("second", "2"), 1, lease);
		saver.putIfLeasedVersion(config, checkpoint("third", "3"), 2, lease);

		RunnableConfig selectedSecond = RunnableConfig.builder(config).checkPointId("second").build();
		saver.putIfLeasedVersion(selectedSecond, checkpoint("replacement", "updated"), 3, lease);

		assertAll(
				() -> assertEquals(4, saver.getVersioned(config).revision()),
				() -> assertEquals(List.of("third", "replacement"),
						saver.list(config).stream().map(Checkpoint::getId).toList()),
				() -> assertTrue(saver.getVersioned(RunnableConfig.builder(config).checkPointId("first").build())
					.checkpoint()
					.isEmpty()),
				() -> assertTrue(saver.getVersioned(selectedSecond).checkpoint().isEmpty()),
				() -> assertEquals("updated", saver
					.getVersioned(RunnableConfig.builder(config).checkPointId("replacement").build())
					.checkpoint()
					.orElseThrow()
					.getState()
					.get("value")));
	}

	@Test
	void revisionConflictAndNegativeRevisionDoNotMutateUnderActiveLease() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig config = config("conflict");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		saver.putIfLeasedVersion(config, checkpoint("first", "first"), 0, lease);

		assertAll(
				() -> assertThrows(CheckpointConflictException.class,
						() -> saver.putIfLeasedVersion(config, checkpoint("stale", "stale"), 0, lease)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> saver.putIfLeasedVersion(config, checkpoint("negative", "negative"), -1, lease)),
				() -> assertThrows(IllegalArgumentException.class, () -> saver.releaseIfLeasedVersion(config, -1, lease)),
				() -> assertEquals(1, saver.getVersioned(config).revision()),
				() -> assertEquals("first", saver.get(config).orElseThrow().getState().get("value")));
	}

	@Test
	void serializerFailuresDoNotAdvanceRevisionOrChangeStoredCheckpoint() throws Exception {
		MutableClock clock = new MutableClock();
		FailingStateSerializer serializer = new FailingStateSerializer();
		MemoryLeasedCheckpointSaver saver = new MemoryLeasedCheckpointSaver(serializer, LeaseOptions.defaults(), clock);
		RunnableConfig config = config("serializer-failure");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		saver.putIfLeasedVersion(config, checkpoint("first", "first"), 0, lease);

		serializer.failWrites = true;

		assertAll(
				() -> assertThrows(IOException.class,
						() -> saver.putIfLeasedVersion(config, checkpoint("failed", "failed"), 1, lease)),
				() -> assertThrows(IOException.class, () -> saver.releaseIfLeasedVersion(config, 1, lease)));

		serializer.failWrites = false;

		assertAll(
				() -> assertEquals(1, saver.getVersioned(config).revision()),
				() -> assertEquals("first", saver.get(config).orElseThrow().getState().get("value")));
	}

	@Test
	void fenceAndExpiryOverflowFailBeforeAnyMutation() throws Exception {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(Long.MAX_VALUE - 1));
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig timeOverflow = config("time-overflow");

		assertThrows(ArithmeticException.class, () -> saver.acquireLease(timeOverflow, UUID.randomUUID()));
		assertEquals(0, retainedFence(saver, timeOverflow));

		RunnableConfig fenceOverflow = config("fence-overflow");
		setRetainedFence(saver, fenceOverflow, Long.MAX_VALUE);

		assertAll(
				() -> assertThrows(ArithmeticException.class, () -> saver.acquireLease(fenceOverflow, UUID.randomUUID())),
				() -> assertEquals(Long.MAX_VALUE, retainedFence(saver, fenceOverflow)),
				() -> assertEquals(0, saver.getVersioned(fenceOverflow).revision()));
	}

	@Test
	void namespaceAndConstructorInputsAreValidated() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver saver = saver(clock);
		RunnableConfig config = config("validation");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		ExecutionLease otherNamespace = new ExecutionLease("other", lease.ownerId(), lease.fencingToken(),
				lease.expiresAtMillis());

		assertAll(
				() -> assertThrows(NullPointerException.class, () -> new MemoryLeasedCheckpointSaver(null,
						LeaseOptions.defaults(), clock)),
				() -> assertThrows(NullPointerException.class,
						() -> new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER, null, clock)),
				() -> assertThrows(NullPointerException.class,
						() -> new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
								LeaseOptions.defaults(), null)),
				() -> assertThrows(NullPointerException.class, () -> saver.acquireLease(config, null)),
				() -> assertThrows(NullPointerException.class, () -> saver.renewLease(config, null)),
				() -> assertThrows(NullPointerException.class, () -> saver.releaseLease(config, null)),
				() -> assertThrows(LeaseLostException.class, () -> saver.renewLease(config, otherNamespace)),
				() -> assertThrows(LeaseLostException.class,
						() -> saver.putIfLeasedVersion(config, checkpoint("wrong-ns", "wrong-ns"), 0, otherNamespace)));
	}

	private static MemoryLeasedCheckpointSaver saver(Clock clock) {
		return new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER, LeaseOptions.defaults(), clock);
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	private static Checkpoint checkpoint(String id, String value) {
		return Checkpoint.builder()
			.id(id)
			.state(Map.of("value", value))
			.nodeId("node")
			.nextNodeId(StateGraph.END)
			.build();
	}

	private static Thread blockingPutThread(MemoryLeasedCheckpointSaver saver, RunnableConfig config,
			ExecutionLease lease, AtomicReference<Throwable> failure) {
		return new Thread(() -> {
			try {
				saver.putIfLeasedVersion(config, checkpoint("blocked-write", "blocked-write"), 0, lease);
			}
			catch (Throwable ex) {
				failure.set(ex);
			}
		}, "lease-monitor-holder");
	}

	private static Object renewResult(MemoryLeasedCheckpointSaver saver, RunnableConfig config, ExecutionLease lease) {
		try {
			return saver.renewLease(config, lease);
		}
		catch (Throwable ex) {
			return ex;
		}
	}

	private static Object acquireResult(MemoryLeasedCheckpointSaver saver, RunnableConfig config, UUID ownerId) {
		try {
			return saver.acquireLease(config, ownerId);
		}
		catch (Throwable ex) {
			return ex;
		}
	}

	private static void awaitMonitorBlocked(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.BLOCKED) {
			if (!thread.isAlive()) {
				fail("Thread ended before blocking on saver monitor");
			}
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for thread to block on saver monitor; state=" + thread.getState());
			}
			Thread.onSpinWait();
		}
	}

	private static void join(Thread thread) throws InterruptedException {
		thread.join(TimeUnit.SECONDS.toMillis(5));
		if (thread.isAlive()) {
			fail("Thread did not finish: " + thread.getName());
		}
	}

	private static void assertNoThreadFailure(AtomicReference<Throwable> failure) {
		Throwable throwable = failure.get();
		if (throwable != null) {
			fail("Thread failed", throwable);
		}
	}

	@SuppressWarnings("unchecked")
	private static void setRetainedFence(MemoryLeasedCheckpointSaver saver, RunnableConfig config, long fence)
			throws Exception {
		Field fences = MemoryLeasedCheckpointSaver.class.getDeclaredField("retainedFencesByNamespace");
		fences.setAccessible(true);
		((Map<String, Long>) fences.get(saver)).put(saver.checkpointThreadId(config), fence);
	}

	@SuppressWarnings("unchecked")
	private static long retainedFence(MemoryLeasedCheckpointSaver saver, RunnableConfig config) throws Exception {
		Field fences = MemoryLeasedCheckpointSaver.class.getDeclaredField("retainedFencesByNamespace");
		fences.setAccessible(true);
		return ((Map<String, Long>) fences.get(saver)).getOrDefault(saver.checkpointThreadId(config), 0L);
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock() {
			this(Instant.ofEpochMilli(1_000));
		}

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}

	}

	private static final class FailingStateSerializer extends StateSerializer {

		private boolean failWrites;

		private FailingStateSerializer() {
			super(StateGraph.DEFAULT_JACKSON_SERIALIZER.stateFactory());
		}

		@Override
		public void writeData(Map<String, Object> data, java.io.ObjectOutput out) throws java.io.IOException {
			if (failWrites) {
				throw new IOException("controlled serializer failure");
			}
			StateGraph.DEFAULT_JACKSON_SERIALIZER.writeData(data, out);
		}

		@Override
		public Map<String, Object> readData(java.io.ObjectInput in) throws java.io.IOException, ClassNotFoundException {
			return StateGraph.DEFAULT_JACKSON_SERIALIZER.readData(in);
		}

	}

	private static final class BlockingStateSerializer extends StateSerializer {

		private final CountDownLatch enteredWrite = new CountDownLatch(1);

		private final CountDownLatch releaseWrite = new CountDownLatch(1);

		private boolean blocking = true;

		private BlockingStateSerializer() {
			super(StateGraph.DEFAULT_JACKSON_SERIALIZER.stateFactory());
		}

		@Override
		public void writeData(Map<String, Object> data, java.io.ObjectOutput out) throws java.io.IOException {
			if (blocking) {
				enteredWrite.countDown();
				try {
					assertTrue(releaseWrite.await(5, TimeUnit.SECONDS));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new java.io.IOException("Interrupted while blocking serializer write", ex);
				}
				blocking = false;
			}
			StateGraph.DEFAULT_JACKSON_SERIALIZER.writeData(data, out);
		}

		@Override
		public Map<String, Object> readData(java.io.ObjectInput in) throws java.io.IOException, ClassNotFoundException {
			return StateGraph.DEFAULT_JACKSON_SERIALIZER.readData(in);
		}

		private void awaitBlockedInWrite() throws InterruptedException {
			assertTrue(enteredWrite.await(5, TimeUnit.SECONDS));
		}

		private void releaseWrite() {
			releaseWrite.countDown();
		}

	}

}
