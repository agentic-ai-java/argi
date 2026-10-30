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


import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Engine-neutral, cooperative execution control. Executors check {@link #assertActive()}
 * before and after side effects and bind cancellation to their in-flight operations.
 * Deadline checks are synchronous; this class creates no timer or worker thread.
 * Ownership notifications stop work locally; stores must still fence every commit.
 * Closing a control cancels remaining work and detaches the ownership registration.
 */
public final class ExecutionControl implements ExecutionGuard, AutoCloseable {

	private final Instant deadline;

	private final Clock clock;

	private final ExecutionGuard ownership;

	private final AutoCloseable ownershipRegistration;

	private final Set<Callback> callbacks = new LinkedHashSet<>();

	private final AtomicBoolean closed = new AtomicBoolean();

	private volatile ExecutionStoppedException failure;

	public ExecutionControl(TrustedExecutionContext context, Clock clock) {
		this(context, clock, null);
	}

	/** A null ownership guard means execution has no external lease owner. */
	public ExecutionControl(TrustedExecutionContext context, Clock clock, ExecutionGuard ownership) {
		Long millis = requireNonNull(context, "context cannot be null").toReference().deadlineEpochMillis();
		this.deadline = millis == null ? null : Instant.ofEpochMilli(millis);
		this.clock = requireNonNull(clock, "clock cannot be null");
		this.ownership = ownership;
		this.ownershipRegistration = ownership == null ? () -> {
		} : requireNonNull(ownership.onLoss(this::loseOwnership), "ownership registration cannot be null");
	}

	@Override
	public Optional<Instant> deadline() {
		return Optional.ofNullable(this.deadline);
	}

	@Override
	public void assertActive() {
		checkDeadline();
		if (failure == null && ownership != null) {
			try {
				ownership.assertActive();
			}
			catch (RuntimeException exception) {
				stop(ExecutionStoppedException.Reason.OWNERSHIP_LOST, exception);
			}
		}
		ExecutionStoppedException stopped = failure;
		if (stopped != null) {
			throw stopped;
		}
	}

	/** First stop reason wins; every active callback is attempted at most once. */
	public void cancel() {
		stop(ExecutionStoppedException.Reason.CANCELLED, null);
	}

	public void loseOwnership() {
		stop(ExecutionStoppedException.Reason.OWNERSHIP_LOST, null);
	}

	@Override
	public AutoCloseable onLoss(Runnable cancellation) {
		Callback callback = new Callback(requireNonNull(cancellation, "cancellation cannot be null"));
		checkDeadline();
		ExecutionStoppedException stopped;
		synchronized (callbacks) {
			stopped = failure;
			if (stopped == null) {
				callbacks.add(callback);
			}
		}
		if (stopped != null) {
			callback.invoke(stopped);
		}
		return () -> {
			callback.pending.set(false);
			synchronized (callbacks) {
				callbacks.remove(callback);
			}
		};
	}

	private void checkDeadline() {
		if (deadline != null && !clock.instant().isBefore(deadline)) {
			stop(ExecutionStoppedException.Reason.DEADLINE_EXCEEDED, null);
		}
	}

	private void stop(ExecutionStoppedException.Reason reason, RuntimeException cause) {
		ArrayList<Callback> listeners;
		ExecutionStoppedException stopped;
		synchronized (callbacks) {
			if (failure != null) {
				return;
			}
			stopped = new ExecutionStoppedException(reason);
			if (cause != null) {
				stopped.initCause(cause);
			}
			failure = stopped;
			listeners = new ArrayList<>(callbacks);
			callbacks.clear();
		}
		listeners.forEach(callback -> callback.invoke(stopped));
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			cancel();
			try {
				ownershipRegistration.close();
			}
			catch (Exception exception) {
				throw new IllegalStateException("Cannot detach execution ownership registration", exception);
			}
		}
	}

	private static final class Callback {

		private final Runnable action;

		private final AtomicBoolean pending = new AtomicBoolean(true);

		private Callback(Runnable action) {
			this.action = action;
		}

		private void invoke(ExecutionStoppedException stopped) {
			if (pending.compareAndSet(true, false)) {
				try {
					action.run();
				}
				catch (RuntimeException exception) {
					if (exception != stopped) {
						stopped.addSuppressed(exception);
					}
				}
			}
		}

	}

}
