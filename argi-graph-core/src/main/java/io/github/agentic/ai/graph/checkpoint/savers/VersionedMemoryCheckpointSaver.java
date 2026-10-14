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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointConflictException;
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.VersionedCheckpointSaver;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.serializer.check_point.CheckPointSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import static java.util.Objects.requireNonNull;

/**
 * Single-process reference implementation of {@link VersionedCheckpointSaver}.
 * <p>
 * This saver composes {@link MemorySaver} under one local monitor. It is not a
 * distributed locking or failover mechanism. All snapshots and stored checkpoints are
 * cloned through the configured {@link StateSerializer}; successful namespace mutations
 * increment the revision once, stale conditional mutations throw
 * {@link CheckpointConflictException}, and release preserves positive empty tombstones.
 */
public class VersionedMemoryCheckpointSaver implements VersionedCheckpointSaver {

	private final MemorySaver delegate = new MemorySaver();

	private final Object monitor = new Object();

	private final StateSerializer stateSerializer;

	private final CheckPointSerializer checkpointSerializer;

	private final Map<String, Long> revisionsByNamespace = new HashMap<>();

	public VersionedMemoryCheckpointSaver() {
		this(new ArgiJacksonStateSerializer(OverAllState::new, new ObjectMapper()));
	}

	public VersionedMemoryCheckpointSaver(StateSerializer serializer) {
		this.stateSerializer = requireNonNull(serializer, "serializer cannot be null");
		this.checkpointSerializer = new CheckPointSerializer(this.stateSerializer);
	}

	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		synchronized (monitor) {
			return delegate.list(config).stream().map(this::cloneCheckpointUnchecked).toList();
		}
	}

	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		synchronized (monitor) {
			return delegate.get(config).map(this::cloneCheckpointUnchecked);
		}
	}

	@Override
	public CheckpointSnapshot getVersioned(RunnableConfig config) {
		synchronized (monitor) {
			String namespace = checkpointThreadId(config);
			long revision = revision(namespace);
			return new CheckpointSnapshot(delegate.get(config).map(this::cloneCheckpointUnchecked), revision);
		}
	}

	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		synchronized (monitor) {
			String namespace = checkpointThreadId(config);
			long nextRevision = nextRevision(revision(namespace));
			RunnableConfig updated = delegate.put(config, cloneCheckpoint(checkpoint));
			revisionsByNamespace.put(namespace, nextRevision);
			return updated;
		}
	}

	@Override
	public RunnableConfig putIfVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision)
			throws Exception {
		validateExpectedRevision(expectedRevision);
		synchronized (monitor) {
			String namespace = checkpointThreadId(config);
			long actualRevision = revision(namespace);
			requireExpectedRevision(namespace, expectedRevision, actualRevision);
			long nextRevision = nextRevision(actualRevision);
			RunnableConfig updated = delegate.put(config, cloneCheckpoint(checkpoint));
			revisionsByNamespace.put(namespace, nextRevision);
			return updated;
		}
	}

	@Override
	public Tag release(RunnableConfig config) throws Exception {
		synchronized (monitor) {
			String namespace = checkpointThreadId(config);
			long nextRevision = nextRevision(revision(namespace));
			Tag releaseTag = releaseTag(config, namespace);
			delegate.release(config);
			revisionsByNamespace.put(namespace, nextRevision);
			return releaseTag;
		}
	}

	@Override
	public Tag releaseIfVersion(RunnableConfig config, long expectedRevision) throws Exception {
		validateExpectedRevision(expectedRevision);
		synchronized (monitor) {
			String namespace = checkpointThreadId(config);
			long actualRevision = revision(namespace);
			requireExpectedRevision(namespace, expectedRevision, actualRevision);
			long nextRevision = nextRevision(actualRevision);
			Tag releaseTag = releaseTag(config, namespace);
			delegate.release(config);
			revisionsByNamespace.put(namespace, nextRevision);
			return releaseTag;
		}
	}

	private long revision(String namespace) {
		return revisionsByNamespace.getOrDefault(namespace, 0L);
	}

	private long nextRevision(long actualRevision) {
		return Math.addExact(actualRevision, 1L);
	}

	private void validateExpectedRevision(long expectedRevision) {
		if (expectedRevision < 0) {
			throw new IllegalArgumentException("expectedRevision cannot be negative");
		}
	}

	private void requireExpectedRevision(String namespace, long expectedRevision, long actualRevision)
			throws CheckpointConflictException {
		if (expectedRevision != actualRevision) {
			throw new CheckpointConflictException(namespace, expectedRevision, actualRevision);
		}
	}

	private Tag releaseTag(RunnableConfig config, String namespace) throws IOException, ClassNotFoundException {
		return new Tag(namespace, cloneCheckpoints(delegate.list(config)));
	}

	private List<Checkpoint> cloneCheckpoints(Collection<Checkpoint> checkpoints)
			throws IOException, ClassNotFoundException {
		List<Checkpoint> copies = new ArrayList<>(checkpoints.size());
		for (Checkpoint checkpoint : checkpoints) {
			copies.add(cloneCheckpoint(checkpoint));
		}
		return copies;
	}

	private Checkpoint cloneCheckpoint(Checkpoint checkpoint) throws IOException, ClassNotFoundException {
		requireNonNull(checkpoint, "checkpoint cannot be null");
		return checkpointSerializer.bytesToObject(checkpointSerializer.objectToBytes(checkpoint));
	}

	private Checkpoint cloneCheckpointUnchecked(Checkpoint checkpoint) {
		try {
			return cloneCheckpoint(checkpoint);
		}
		catch (IOException | ClassNotFoundException ex) {
			throw new IllegalStateException("Failed to clone checkpoint", ex);
		}
	}

}
