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
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionLease;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseBusyException;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import static java.util.Objects.requireNonNull;

/**
 * Single-process reference implementation of {@link LeasedCheckpointSaver}.
 */
public class MemoryLeasedCheckpointSaver implements LeasedCheckpointSaver {

	private final VersionedMemoryCheckpointSaver delegate;

	private final Object monitor = new Object();

	private final LeaseOptions leaseOptions;

	private final Clock clock;

	private final Map<String, LeaseEntry> leasesByNamespace = new HashMap<>();

	private final Map<String, Long> retainedFencesByNamespace = new HashMap<>();

	public MemoryLeasedCheckpointSaver() {
		this(new ArgiJacksonStateSerializer(OverAllState::new, new ObjectMapper()), LeaseOptions.defaults(),
				Clock.systemUTC());
	}

	public MemoryLeasedCheckpointSaver(StateSerializer serializer, LeaseOptions leaseOptions) {
		this(serializer, leaseOptions, Clock.systemUTC());
	}

	public MemoryLeasedCheckpointSaver(StateSerializer serializer, LeaseOptions leaseOptions, Clock clock) {
		this.delegate = new VersionedMemoryCheckpointSaver(requireNonNull(serializer, "serializer cannot be null"));
		this.leaseOptions = requireNonNull(leaseOptions, "leaseOptions cannot be null");
		this.clock = requireNonNull(clock, "clock cannot be null");
	}

	@Override
	public LeaseOptions leaseOptions() {
		return leaseOptions;
	}

	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		synchronized (monitor) {
			return delegate.list(config);
		}
	}

	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		synchronized (monitor) {
			return delegate.get(config);
		}
	}

	@Override
	public CheckpointSnapshot getVersioned(RunnableConfig config) {
		synchronized (monitor) {
			return delegate.getVersioned(config);
		}
	}

	@Override
	public ExecutionLease acquireLease(RunnableConfig config, UUID ownerId) {
		requireNonNull(ownerId, "ownerId cannot be null");
		String namespace = checkpointThreadId(config);
		synchronized (monitor) {
			long nowMillis = clock.millis();
			long expiresAtMillis = expiresAtMillis(nowMillis);
			LeaseEntry current = leasesByNamespace.get(namespace);
			if (current != null && current.isActive(nowMillis)) {
				throw new LeaseBusyException(namespace);
			}
			long nextFence = nextFence(namespace);
			retainedFencesByNamespace.put(namespace, nextFence);
			LeaseEntry entry = new LeaseEntry(ownerId, nextFence, expiresAtMillis);
			leasesByNamespace.put(namespace, entry);
			return entry.toLease(namespace);
		}
	}

	@Override
	public ExecutionLease renewLease(RunnableConfig config, ExecutionLease lease) {
		requireNonNull(lease, "lease cannot be null");
		String namespace = checkpointThreadId(config);
		synchronized (monitor) {
			long nowMillis = clock.millis();
			long expiresAtMillis = expiresAtMillis(nowMillis);
			requireActiveLease(namespace, lease, nowMillis);
			LeaseEntry entry = new LeaseEntry(lease.ownerId(), lease.fencingToken(), expiresAtMillis);
			leasesByNamespace.put(namespace, entry);
			return entry.toLease(namespace);
		}
	}

	@Override
	public boolean releaseLease(RunnableConfig config, ExecutionLease lease) {
		requireNonNull(lease, "lease cannot be null");
		String namespace = checkpointThreadId(config);
		synchronized (monitor) {
			LeaseEntry current = leasesByNamespace.get(namespace);
			if (current == null || !leaseMatches(namespace, lease, current)) {
				return false;
			}
			leasesByNamespace.remove(namespace);
			return true;
		}
	}

	@Override
	public RunnableConfig putIfLeasedVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision,
			ExecutionLease lease) throws Exception {
		synchronized (monitor) {
			requireActiveLease(checkpointThreadId(config), lease, clock.millis());
			return delegate.putIfVersion(config, checkpoint, expectedRevision);
		}
	}

	@Override
	public Tag releaseIfLeasedVersion(RunnableConfig config, long expectedRevision, ExecutionLease lease)
			throws Exception {
		synchronized (monitor) {
			requireActiveLease(checkpointThreadId(config), lease, clock.millis());
			return delegate.releaseIfVersion(config, expectedRevision);
		}
	}

	private long expiresAtMillis(long nowMillis) {
		return Math.addExact(nowMillis, leaseOptions.ttl().toMillis());
	}

	private long nextFence(String namespace) {
		return Math.addExact(retainedFencesByNamespace.getOrDefault(namespace, 0L), 1L);
	}

	private void requireActiveLease(String namespace, ExecutionLease lease, long nowMillis) {
		requireNonNull(lease, "lease cannot be null");
		LeaseEntry current = leasesByNamespace.get(namespace);
		if (current == null || !leaseMatches(namespace, lease, current)) {
			throw lost(namespace, lease, "lease ownership changed");
		}
		if (!current.isActive(nowMillis)) {
			throw lost(namespace, lease, "lease expired");
		}
	}

	private boolean leaseMatches(String namespace, ExecutionLease lease, LeaseEntry current) {
		return namespace.equals(lease.namespace()) && current.ownerId().equals(lease.ownerId())
				&& current.fencingToken() == lease.fencingToken();
	}

	private LeaseLostException lost(String namespace, ExecutionLease lease, String reason) {
		return new LeaseLostException(namespace, lease.ownerId(), lease.fencingToken(), reason);
	}

	private record LeaseEntry(UUID ownerId, long fencingToken, long expiresAtMillis) {

		private boolean isActive(long nowMillis) {
			return expiresAtMillis > nowMillis;
		}

		private ExecutionLease toLease(String namespace) {
			return new ExecutionLease(namespace, ownerId, fencingToken, expiresAtMillis);
		}

	}

}
