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
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver.Tag;
import io.github.agentic.ai.graph.serializer.StateSerializer;

import java.lang.reflect.Field;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedMemoryCheckpointSaverTest {

	@Test
	void snapshotRequiresOwnedOptionalAndNonNegativeRevision() {
		assertAll(
				() -> assertThrows(NullPointerException.class, () -> new CheckpointSnapshot(null, 0)),
				() -> assertThrows(IllegalArgumentException.class, () -> new CheckpointSnapshot(Optional.empty(), -1)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CheckpointSnapshot(Optional.of(checkpoint("zero", "invalid")), 0)));
	}

	@Test
	void checkpointConflictExceptionExposesRevisionDetailsWithoutPayloads() {
		CheckpointConflictException exception = new CheckpointConflictException("thread-a", 3, 5);

		assertAll(
				() -> assertEquals("thread-a", exception.getNamespace()),
				() -> assertEquals(3, exception.getExpectedRevision()),
				() -> assertEquals(5, exception.getActualRevision()),
				() -> assertFalse(exception.getMessage().contains("state")));
	}

	@Test
	void conditionalPutAndReleaseAdvanceRevisionAndRejectStaleWriters() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("main");
		Checkpoint firstCheckpoint = checkpoint("first", "first");
		Checkpoint otherCheckpoint = checkpoint("other", "other");

		assertEquals(0, saver.getVersioned(config).revision());
		RunnableConfig writtenConfig = saver.putIfVersion(config, firstCheckpoint, 0);

		assertAll(
				() -> assertEquals(firstCheckpoint.getId(), writtenConfig.checkPointId().orElseThrow()),
				() -> assertEquals(1, saver.getVersioned(config).revision()),
				() -> assertEquals("first", saver.getVersioned(config).checkpoint().orElseThrow().getState().get("value")));

		CheckpointConflictException conflict = assertThrows(CheckpointConflictException.class,
				() -> saver.putIfVersion(config, otherCheckpoint, 0));

		assertAll(
				() -> assertEquals(saver.checkpointThreadId(config), conflict.getNamespace()),
				() -> assertEquals(0, conflict.getExpectedRevision()),
				() -> assertEquals(1, conflict.getActualRevision()),
				() -> assertEquals("first", saver.getVersioned(config).checkpoint().orElseThrow().getState().get("value")));

		Tag releaseTag = saver.releaseIfVersion(config, 1);

		assertAll(
				() -> assertEquals(saver.checkpointThreadId(config), releaseTag.threadId()),
				() -> assertEquals(List.of(firstCheckpoint.getId()),
						releaseTag.checkpoints().stream().map(Checkpoint::getId).toList()),
				() -> assertTrue(saver.getVersioned(config).checkpoint().isEmpty()),
				() -> assertEquals(2, saver.getVersioned(config).revision()));
	}

	@Test
	void releaseOfEmptyNamespaceCreatesPositiveTombstoneAndCanBeReused() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("empty-release");

		Tag tag = saver.releaseIfVersion(config, 0);

		assertAll(
				() -> assertTrue(tag.checkpoints().isEmpty()),
				() -> assertTrue(saver.getVersioned(config).checkpoint().isEmpty()),
				() -> assertEquals(1, saver.getVersioned(config).revision()));

		saver.putIfVersion(config, checkpoint("after-release", "live"), 1);

		assertAll(
				() -> assertEquals(2, saver.getVersioned(config).revision()),
				() -> assertEquals("live", saver.getVersioned(config).checkpoint().orElseThrow().getState().get("value")));
	}

	@Test
	void selectedCheckpointAndReplacementUseTheSameNamespaceRevision() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("selected");
		Checkpoint first = checkpoint("first", "old");
		Checkpoint second = checkpoint("second", "newer");

		saver.putIfVersion(config, first, 0);
		saver.putIfVersion(config, second, 1);

		RunnableConfig selectedFirst = RunnableConfig.builder(config).checkPointId("first").build();
		assertAll(
				() -> assertEquals("old",
						saver.getVersioned(selectedFirst).checkpoint().orElseThrow().getState().get("value")),
				() -> assertEquals(2, saver.getVersioned(selectedFirst).revision()));

		saver.putIfVersion(selectedFirst, checkpoint("replacement", "updated"), 2);

		assertAll(
				() -> assertEquals(3, saver.getVersioned(config).revision()),
				() -> assertTrue(saver.getVersioned(selectedFirst).checkpoint().isEmpty()),
				() -> assertEquals("updated", saver
					.getVersioned(RunnableConfig.builder(config).checkPointId("replacement").build())
					.checkpoint()
					.orElseThrow()
					.getState()
					.get("value")),
				() -> assertEquals("newer", saver.getVersioned(config).checkpoint().orElseThrow().getState().get("value")),
				() -> assertEquals(List.of("second", "replacement"),
						saver.list(config).stream().map(Checkpoint::getId).toList()));
	}

	@Test
	void retentionIsAppliedWithoutExtraRevisionIncrements() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = RunnableConfig.builder().threadId("retained").checkpointsNumRetained(2).build();

		saver.putIfVersion(config, checkpoint("first", "1"), 0);
		saver.putIfVersion(config, checkpoint("second", "2"), 1);
		saver.putIfVersion(config, checkpoint("third", "3"), 2);

		assertAll(
				() -> assertEquals(3, saver.getVersioned(config).revision()),
				() -> assertEquals(List.of("third", "second"), saver.list(config).stream().map(Checkpoint::getId).toList()),
				() -> assertTrue(saver.getVersioned(RunnableConfig.builder(config).checkPointId("first").build())
					.checkpoint()
					.isEmpty()));
	}

	@Test
	void namespacesAreIsolatedByCheckpointThreadId() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig alice = namespacedConfig("assistant", "alice", "shared");
		RunnableConfig bob = namespacedConfig("assistant", "bob", "shared");

		saver.putIfVersion(alice, checkpoint("alice", "alice"), 0);
		saver.releaseIfVersion(bob, 0);

		assertAll(
				() -> assertEquals(1, saver.getVersioned(alice).revision()),
				() -> assertEquals("alice", saver.getVersioned(alice).checkpoint().orElseThrow().getState().get("value")),
				() -> assertEquals(1, saver.getVersioned(bob).revision()),
				() -> assertTrue(saver.getVersioned(bob).checkpoint().isEmpty()));
	}

	@Test
	void negativeExpectedRevisionIsInvalidAndDoesNotMutate() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("negative");

		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> saver.putIfVersion(config, checkpoint("first", "first"), -1)),
				() -> assertThrows(IllegalArgumentException.class, () -> saver.releaseIfVersion(config, -1)),
				() -> assertTrue(saver.getVersioned(config).checkpoint().isEmpty()),
				() -> assertEquals(0, saver.getVersioned(config).revision()));
	}

	@Test
	void snapshotsAndStoredCheckpointsAreIndependentCopies() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("ownership");
		Map<String, Object> incomingState = new HashMap<>();
		incomingState.put("value", "original");
		Checkpoint incoming = Checkpoint.builder().id("owned").state(incomingState).nodeId("node").nextNodeId(StateGraph.END).build();

		saver.putIfVersion(config, incoming, 0);
		incomingState.put("value", "changed-after-write");

		Checkpoint firstRead = saver.getVersioned(config).checkpoint().orElseThrow();
		try {
			firstRead.getState().put("value", "changed-after-read");
		}
		catch (UnsupportedOperationException ignored) {
			// Immutable snapshot state also proves callers cannot mutate internal storage.
		}
		Checkpoint secondRead = saver.getVersioned(config).checkpoint().orElseThrow();

		assertAll(
				() -> assertNotSame(incoming, firstRead),
				() -> assertNotSame(firstRead, secondRead),
				() -> assertEquals("original", secondRead.getState().get("value")));
	}

	@Test
	void concurrentConditionalWritersAllowExactlyOneWinner() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("concurrent");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> first = executor.submit(() -> putAfterLatch(saver, config, checkpoint("first", "first"), ready, start));
			Future<Object> second = executor.submit(() -> putAfterLatch(saver, config, checkpoint("second", "second"), ready, start));

			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			List<Object> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

			assertAll(
					() -> assertEquals(1, results.stream().filter(RunnableConfig.class::isInstance).count()),
					() -> assertEquals(1, results.stream().filter(CheckpointConflictException.class::isInstance).count()),
					() -> assertEquals(1, saver.getVersioned(config).revision()),
					() -> assertTrue(List.of("first", "second")
						.contains(saver.getVersioned(config).checkpoint().orElseThrow().getId())));
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void revisionOverflowFailsBeforeDelegateMutation() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig config = config("overflow");
		setStoredRevision(saver, config, Long.MAX_VALUE);

		assertAll(
				() -> assertThrows(ArithmeticException.class,
						() -> saver.putIfVersion(config, checkpoint("overflow", "overflow"), Long.MAX_VALUE)),
				() -> assertThrows(ArithmeticException.class, () -> saver.releaseIfVersion(config, Long.MAX_VALUE)),
				() -> assertEquals(Long.MAX_VALUE, saver.getVersioned(config).revision()),
				() -> assertTrue(saver.list(config).isEmpty()));
	}

	@Test
	void suppliedSerializerIsUsedForSnapshotCopies() throws Exception {
		CountingStateSerializer serializer = new CountingStateSerializer();
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver(serializer);
		RunnableConfig config = config("serializer");

		saver.putIfVersion(config, checkpoint("first", "first"), 0);
		saver.getVersioned(config);

		assertTrue(serializer.writeCount > 0);
		assertTrue(serializer.readCount > 0);
	}

	@Test
	void releaseSerializationFailureLeavesCheckpointAndRevisionUnchanged() throws Exception {
		FailingStateSerializer serializer = new FailingStateSerializer();
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver(serializer);
		RunnableConfig config = config("release-failure");
		saver.putIfVersion(config, checkpoint("first", "first"), 0);

		serializer.failWrites = true;

		assertThrows(IOException.class, () -> saver.releaseIfVersion(config, 1));
		serializer.failWrites = false;

		assertAll(
				() -> assertEquals(1, saver.getVersioned(config).revision()),
				() -> assertEquals("first", saver.get(config).orElseThrow().getState().get("value")));
	}

	private static Object putAfterLatch(VersionedMemoryCheckpointSaver saver, RunnableConfig config,
			Checkpoint checkpoint, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertTrue(start.await(5, TimeUnit.SECONDS));
		try {
			return saver.putIfVersion(config, checkpoint, 0);
		}
		catch (CheckpointConflictException exception) {
			return exception;
		}
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	private static RunnableConfig namespacedConfig(String appName, String userId, String threadId) {
		return RunnableConfig.builder()
			.threadId(threadId)
			.addMetadata("app_name", appName)
			.addMetadata("user_id", userId)
			.build();
	}

	private static Checkpoint checkpoint(String id, String value) {
		return Checkpoint.builder()
			.id(id)
			.state(Map.of("value", value))
			.nodeId("node")
			.nextNodeId(StateGraph.END)
			.build();
	}

	@SuppressWarnings("unchecked")
	private static void setStoredRevision(VersionedMemoryCheckpointSaver saver, RunnableConfig config, long revision)
			throws Exception {
		Field revisions = VersionedMemoryCheckpointSaver.class.getDeclaredField("revisionsByNamespace");
		revisions.setAccessible(true);
		((Map<String, Long>) revisions.get(saver)).put(saver.checkpointThreadId(config), revision);
	}

	private static final class CountingStateSerializer extends StateSerializer {

		private int writeCount;

		private int readCount;

		private CountingStateSerializer() {
			super(StateGraph.DEFAULT_JACKSON_SERIALIZER.stateFactory());
		}

		@Override
		public void writeData(Map<String, Object> data, java.io.ObjectOutput out) throws java.io.IOException {
			writeCount++;
			StateGraph.DEFAULT_JACKSON_SERIALIZER.writeData(data, out);
		}

		@Override
		public Map<String, Object> readData(java.io.ObjectInput in) throws java.io.IOException, ClassNotFoundException {
			readCount++;
			return StateGraph.DEFAULT_JACKSON_SERIALIZER.readData(in);
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

}
