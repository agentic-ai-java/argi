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

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/**
 * Per-subscription lease owner held only in Reactor context.
 */
public final class ExecutionLeaseScope {

	private static final Logger log = LoggerFactory.getLogger(ExecutionLeaseScope.class);

	private static final Object CONTEXT_KEY = new Object();

	private final LeasedCheckpointSaver saver;

	private final RunnableConfig config;

	private final String namespace;

	private final LongSupplier nanoTime;

	private final Scheduler timerScheduler;

	private final Scheduler rpcScheduler;

	private final AtomicReference<ExecutionLease> lease;

	private final AtomicBoolean active = new AtomicBoolean(true);

	private final AtomicBoolean closed = new AtomicBoolean();

	private final AtomicReference<LeaseLostException> terminalLoss = new AtomicReference<>();

	private final Object lifecycleMonitor = new Object();

	private final List<LossRegistration> lossCallbacks = new ArrayList<>();

	private final Sinks.Empty<Void> lossSignal = Sinks.empty();

	private final AtomicReference<Disposable> heartbeatTask = new AtomicReference<>();

	private final AtomicReference<Disposable> deadlineTask = new AtomicReference<>();

	private volatile long deadlineNanos;

	ExecutionLeaseScope(LeasedCheckpointSaver saver, RunnableConfig config, ExecutionLease lease,
			long requestStartNanos, LongSupplier nanoTime, Scheduler timerScheduler, Scheduler rpcScheduler) {
		this.saver = Objects.requireNonNull(saver, "saver cannot be null");
		this.config = Objects.requireNonNull(config, "config cannot be null");
		this.lease = new AtomicReference<>(Objects.requireNonNull(lease, "lease cannot be null"));
		this.namespace = lease.namespace();
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime cannot be null");
		this.timerScheduler = Objects.requireNonNull(timerScheduler, "timerScheduler cannot be null");
		this.rpcScheduler = Objects.requireNonNull(rpcScheduler, "rpcScheduler cannot be null");
		this.deadlineNanos = deadlineAfter(requestStartNanos);
		scheduleDeadline();
		scheduleHeartbeat();
	}

	public static <T> Flux<T> withLease(LeasedCheckpointSaver saver, RunnableConfig config,
			Function<ExecutionLeaseScope, Flux<T>> operation) {
		Objects.requireNonNull(saver, "saver cannot be null");
		Objects.requireNonNull(config, "config cannot be null");
		Objects.requireNonNull(operation, "operation cannot be null");
		return Flux.deferContextual(context -> {
			ScopeKey key = new ScopeKey(saver, saver.checkpointThreadId(config));
			Map<ScopeKey, ExecutionLeaseScope> inherited = context.getOrDefault(CONTEXT_KEY, Map.of());
			ExecutionLeaseScope existing = inherited.get(key);
			if (existing != null) {
				existing.assertActive();
				return Flux.defer(() -> operation.apply(existing));
			}
			return Flux.usingWhen(Mono.fromCallable(() -> {
				long requestStartNanos = System.nanoTime();
				ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
				return new ExecutionLeaseScope(saver, config, lease, requestStartNanos, System::nanoTime,
						Schedulers.parallel(), Schedulers.boundedElastic());
			}), scope -> {
				Map<ScopeKey, ExecutionLeaseScope> scopes = new HashMap<>(inherited);
				scopes.put(key, scope);
				Flux<T> operationFlux = Flux.defer(() -> operation.apply(scope))
					.doOnError(error -> {
						if (error instanceof LeaseLostException) {
							scope.invalidate(error);
						}
					})
					.contextWrite(current -> current.put(CONTEXT_KEY, scopes));
				return operationFlux.takeUntilOther(scope.lossPublisher())
					.concatWith(Flux.defer(() -> scope.terminalLoss.get() == null ? Flux.empty()
							: Flux.error(scope.terminalLoss.get())));
			}, scope -> Mono.fromRunnable(scope::close), (scope, error) -> Mono.fromRunnable(scope::close),
					scope -> Mono.fromRunnable(scope::close));
		});
	}

	public static Optional<ExecutionLeaseScope> current(ContextView context, LeasedCheckpointSaver saver,
			RunnableConfig config) {
		Objects.requireNonNull(context, "context cannot be null");
		Objects.requireNonNull(saver, "saver cannot be null");
		Objects.requireNonNull(config, "config cannot be null");
		Map<ScopeKey, ExecutionLeaseScope> scopes = context.getOrDefault(CONTEXT_KEY, Map.of());
		ExecutionLeaseScope scope = scopes.get(new ScopeKey(saver, saver.checkpointThreadId(config)));
		if (scope == null) {
			return Optional.empty();
		}
		scope.assertActive();
		return Optional.of(scope);
	}

	public ExecutionLease lease() {
		assertActive();
		return lease.get();
	}

	public ExecutionGuard guard() {
		return new ScopeGuard();
	}

	public void assertActive() {
		LeaseLostException loss = terminalLoss.get();
		if (loss != null) {
			throw loss;
		}
		if (isDeadlineExpired()) {
			LeaseLostException deadlineLoss = new LeaseLostException(namespace, lease.get().ownerId(),
					lease.get().fencingToken(), "local lease deadline passed");
			invalidate(deadlineLoss);
			throw terminalLoss.get();
		}
		if (!active.get() || closed.get()) {
			ExecutionLease current = lease.get();
			throw new LeaseLostException(namespace, current.ownerId(), current.fencingToken(), "lease is inactive");
		}
	}

	public void invalidate(Throwable cause) {
		ExecutionLease current = lease.get();
		LeaseLostException loss = cause instanceof LeaseLostException leaseLost ? leaseLost
				: new LeaseLostException(namespace, current.ownerId(), current.fencingToken(), "lease lost", cause);
		List<LossRegistration> callbacks;
		synchronized (lifecycleMonitor) {
			if (terminalLoss.get() != null) {
				return;
			}
			terminalLoss.set(loss);
			active.set(false);
			callbacks = new ArrayList<>(lossCallbacks);
			lossCallbacks.clear();
		}
		dispose(heartbeatTask);
		dispose(deadlineTask);
		for (LossRegistration callback : callbacks) {
			callback.run();
		}
		lossSignal.tryEmitEmpty();
	}

	private Mono<Void> lossPublisher() {
		return lossSignal.asMono();
	}

	private void scheduleHeartbeat() {
		Duration heartbeatInterval = saver.leaseOptions().heartbeatInterval();
		Disposable heartbeat = timerScheduler.schedule(() -> {
			Disposable renewal = Mono.fromCallable(() -> {
				renew();
				return true;
			}).subscribeOn(rpcScheduler).subscribe(ignored -> scheduleHeartbeat(), this::invalidate);
			setDisposable(heartbeatTask, renewal);
		}, heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
		setDisposable(heartbeatTask, heartbeat);
	}

	private void renew() throws Exception {
		assertActive();
		long requestStartNanos = nanoTime.getAsLong();
		long currentDeadline = deadlineNanos;
		if (isAtOrAfter(requestStartNanos, currentDeadline)) {
			invalidate(new LeaseLostException(namespace, lease.get().ownerId(), lease.get().fencingToken(),
					"local lease deadline passed"));
			return;
		}
		ExecutionLease renewed = saver.renewLease(config, lease.get());
		long acknowledgeNanos = nanoTime.getAsLong();
		LeaseLostException lateLoss = null;
		synchronized (lifecycleMonitor) {
			if (!active.get() || closed.get() || terminalLoss.get() != null) {
				return;
			}
			if (isAtOrAfter(acknowledgeNanos, currentDeadline)) {
				lateLoss = new LeaseLostException(namespace, lease.get().ownerId(), lease.get().fencingToken(),
						"lease renewal acknowledged after local deadline");
			}
			else {
				lease.set(renewed);
				deadlineNanos = deadlineAfter(requestStartNanos);
				scheduleDeadlineLocked();
			}
		}
		if (lateLoss != null) {
			invalidate(lateLoss);
		}
	}

	private void scheduleDeadline() {
		synchronized (lifecycleMonitor) {
			if (!active.get() || closed.get() || terminalLoss.get() != null) {
				return;
			}
			scheduleDeadlineLocked();
		}
	}

	private void scheduleDeadlineLocked() {
		dispose(deadlineTask);
		long expectedDeadline = deadlineNanos;
		long delay = remainingNanos(nanoTime.getAsLong(), expectedDeadline);
		Disposable deadline = timerScheduler.schedule(() -> expireIfDeadlineElapsed(expectedDeadline), delay,
				TimeUnit.NANOSECONDS);
		deadlineTask.set(deadline);
	}

	private void expireIfDeadlineElapsed(long expectedDeadline) {
		LeaseLostException deadlineLoss = null;
		synchronized (lifecycleMonitor) {
			if (!active.get() || closed.get() || terminalLoss.get() != null || deadlineNanos != expectedDeadline) {
				return;
			}
			if (!isAtOrAfter(nanoTime.getAsLong(), expectedDeadline)) {
				scheduleDeadlineLocked();
				return;
			}
			deadlineLoss = new LeaseLostException(namespace, lease.get().ownerId(), lease.get().fencingToken(),
					"local lease deadline passed");
		}
		invalidate(deadlineLoss);
	}

	private void close() {
		synchronized (lifecycleMonitor) {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			active.set(false);
			lossCallbacks.clear();
		}
		dispose(heartbeatTask);
		dispose(deadlineTask);
		try {
			saver.releaseLease(config, lease.get());
		}
		catch (Exception ex) {
			log.warn("Best-effort checkpoint lease release failed for namespace '{}'", namespace, ex);
		}
	}

	private boolean isDeadlineExpired() {
		return active.get() && !closed.get() && terminalLoss.get() == null
				&& isAtOrAfter(nanoTime.getAsLong(), deadlineNanos);
	}

	private long deadlineAfter(long requestStartNanos) {
		return requestStartNanos + TimeUnit.MILLISECONDS.toNanos(saver.leaseOptions().ttl().toMillis());
	}

	private static long remainingNanos(long now, long deadline) {
		long remaining = deadline - now;
		return remaining > 0 ? remaining : 0L;
	}

	private static boolean isAtOrAfter(long now, long deadline) {
		return now - deadline >= 0;
	}

	private static void dispose(AtomicReference<Disposable> ref) {
		Disposable disposable = ref.getAndSet(null);
		if (disposable != null) {
			disposable.dispose();
		}
	}

	private void setDisposable(AtomicReference<Disposable> ref, Disposable disposable) {
		Disposable previous = ref.getAndSet(disposable);
		if (previous != null) {
			previous.dispose();
		}
		if (!active.get() || closed.get() || terminalLoss.get() != null) {
			dispose(ref);
		}
	}

	private final class ScopeGuard implements ExecutionGuard {

		@Override
		public void assertActive() {
			ExecutionLeaseScope.this.assertActive();
		}

		@Override
		public AutoCloseable onLoss(Runnable cancellation) {
			Objects.requireNonNull(cancellation, "cancellation cannot be null");
			LossRegistration registration = new LossRegistration(cancellation);
			synchronized (lifecycleMonitor) {
				if (terminalLoss.get() == null && active.get() && !closed.get()) {
					lossCallbacks.add(registration);
					return registration;
				}
			}
			if (terminalLoss.get() != null) {
				registration.run();
			}
			return () -> {
			};
		}

	}

	private final class LossRegistration implements Runnable, AutoCloseable {

		private final Runnable callback;

		private final AtomicBoolean notified = new AtomicBoolean();

		private LossRegistration(Runnable callback) {
			this.callback = callback;
		}

		@Override
		public void run() {
			if (!notified.compareAndSet(false, true)) {
				return;
			}
			try {
				callback.run();
			}
			catch (Throwable ex) {
				log.debug("Execution lease loss callback failed", ex);
			}
		}

		@Override
		public void close() {
			synchronized (lifecycleMonitor) {
				lossCallbacks.remove(this);
			}
		}

	}

	private record ScopeKey(LeasedCheckpointSaver saver, String namespace) {

		private ScopeKey {
			Objects.requireNonNull(saver, "saver cannot be null");
			Objects.requireNonNull(namespace, "namespace cannot be null");
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ScopeKey key && saver == key.saver && namespace.equals(key.namespace);
		}

		@Override
		public int hashCode() {
			return 31 * System.identityHashCode(saver) + namespace.hashCode();
		}

	}

}
