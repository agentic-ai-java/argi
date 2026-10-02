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
package io.github.agentic.ai.graph.checkpoint;

import io.github.agentic.ai.graph.RunnableConfig;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Serializes executions that share a checkpoint namespace and saver instance in this
 * JVM. Waiting subscriptions do not block a thread or read checkpoint state. Independent
 * saver instances, processes and direct calls to the saver require their own coordination.
 */
public final class CheckpointExecutionQueue {

	private static final Object LEASE_CONTEXT_KEY = new Object();

	private static final Map<ExecutionKey, ArrayDeque<Lease>> QUEUES = new HashMap<>();

	private static final ThreadLocal<Map<ExecutionKey, ArrayDeque<Lease>>> PENDING_GRANTS = new ThreadLocal<>();

	private CheckpointExecutionQueue() {
	}

	/**
	 * Runs the supplied operation after earlier subscriptions for the same checkpoint
	 * namespace finish. The operation must include all checkpoint reads and cancellation
	 * cleanup. Its supplier is invoked once per subscription, only after acquiring a lease.
	 * Nested agent/graph adapters inherit an active lease for the same key through Reactor
	 * context; an expired lease never bypasses the queue.
	 * @param saver the shared checkpoint saver
	 * @param config the checkpoint namespace configuration
	 * @param operation the complete execution lifecycle
	 * @param <T> the emitted value type
	 * @return a cold publisher with FIFO execution per checkpoint namespace
	 */
	public static <T> Flux<T> serialize(BaseCheckpointSaver saver, RunnableConfig config,
			Supplier<? extends Flux<T>> operation) {
		Objects.requireNonNull(saver, "saver cannot be null");
		Objects.requireNonNull(config, "config cannot be null");
		Objects.requireNonNull(operation, "operation cannot be null");
		return Flux.deferContextual(context -> {
			final ExecutionKey key = new ExecutionKey(saver, saver.checkpointThreadId(config));
			final Map<ExecutionKey, Lease> inherited = context.getOrDefault(LEASE_CONTEXT_KEY, Map.of());
			final Lease existing = inherited.get(key);
			if (existing != null && existing.isActive()) {
				return Flux.defer(operation);
			}
			return Flux.usingWhen(acquire(key), lease -> {
				if (!lease.enter()) {
					return Flux.empty();
				}
				final Map<ExecutionKey, Lease> leases = new HashMap<>(inherited);
				leases.put(key, lease);
				return Flux.defer(operation).contextWrite(current -> current.put(LEASE_CONTEXT_KEY, leases));
			}, lease -> Mono.fromRunnable(lease::release),
					(lease, error) -> Mono.fromRunnable(lease::release),
					lease -> Mono.fromRunnable(lease::release));
		});
	}

	private static Mono<Lease> acquire(ExecutionKey key) {
		return Mono.<Lease>create(sink -> {
			final Lease lease = new Lease(key, sink);
			sink.onCancel(lease::cancelBeforeEntry);
			final boolean first;
			synchronized (QUEUES) {
				if (lease.released) {
					return;
				}
				final ArrayDeque<Lease> queue = QUEUES.computeIfAbsent(key, ignored -> new ArrayDeque<>());
				queue.addLast(lease);
				first = queue.peekFirst() == lease;
			}
			if (first) {
				grant(lease);
			}
		}).doOnDiscard(Lease.class, Lease::release);
	}

	// A synchronous completion can release the next lease while it is being granted.
	// Drain those grants iteratively without moving execution to another scheduler.
	private static void grant(Lease lease) {
		Map<ExecutionKey, ArrayDeque<Lease>> grants = PENDING_GRANTS.get();
		if (grants == null) {
			grants = new HashMap<>();
			PENDING_GRANTS.set(grants);
		}
		ArrayDeque<Lease> pending = grants.get(lease.key);
		if (pending != null) {
			pending.addLast(lease);
			return;
		}
		pending = new ArrayDeque<>();
		grants.put(lease.key, pending);
		pending.addLast(lease);
		try {
			while (!pending.isEmpty()) {
				final Lease next = pending.removeFirst();
				if (!next.isReleased()) {
					next.sink.success(next);
				}
			}
		}
		finally {
			grants.remove(lease.key);
			if (grants.isEmpty()) {
				PENDING_GRANTS.remove();
			}
		}
	}

	private static final class Lease {

		private final ExecutionKey key;

		private final MonoSink<Lease> sink;

		private boolean entered;

		private boolean released;

		private Lease(ExecutionKey key, MonoSink<Lease> sink) {
			this.key = key;
			this.sink = sink;
		}

		private boolean enter() {
			synchronized (QUEUES) {
				if (released) {
					return false;
				}
				entered = true;
				return true;
			}
		}

		private boolean isActive() {
			synchronized (QUEUES) {
				return entered && !released;
			}
		}

		private boolean isReleased() {
			synchronized (QUEUES) {
				return released;
			}
		}

		private void cancelBeforeEntry() {
			final Lease next;
			synchronized (QUEUES) {
				// Once the operation starts, usingWhen owns cancellation: it cancels
				// the source and completes its cleanup before releasing this lease.
				if (entered) {
					return;
				}
				next = remove();
			}
			if (next != null) {
				grant(next);
			}
		}

		private void release() {
			final Lease next;
			synchronized (QUEUES) {
				next = remove();
			}
			if (next != null) {
				grant(next);
			}
		}

		// Invoked under QUEUES; no user code or reactive signals run under this lock.
		private Lease remove() {
			if (released) {
				return null;
			}
			released = true;
			final ArrayDeque<Lease> queue = QUEUES.get(key);
			if (queue == null) {
				return null;
			}
			final boolean first = queue.peekFirst() == this;
			queue.remove(this);
			if (queue.isEmpty()) {
				QUEUES.remove(key);
				return null;
			}
			return first ? queue.peekFirst() : null;
		}

	}

	private static final class ExecutionKey {

		private final BaseCheckpointSaver saver;

		private final String namespace;

		private ExecutionKey(BaseCheckpointSaver saver, String namespace) {
			this.saver = saver;
			this.namespace = Objects.requireNonNull(namespace, "checkpoint namespace cannot be null");
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ExecutionKey key && saver == key.saver && namespace.equals(key.namespace);
		}

		@Override
		public int hashCode() {
			return 31 * System.identityHashCode(saver) + namespace.hashCode();
		}

	}

}
