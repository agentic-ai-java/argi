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

package io.github.agentic.ai.agent.orchestration.tools.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceContext;

import org.springframework.util.Assert;

/**
 * Shared, in-memory correlation store between the {@link TaskTool} delegation site and the
 * {@link TaskOutputTool} receive site of background tasks.
 * <p>
 * The producer span (background sub-agent execution) runs on a repository worker thread and
 * is usually finished by the time the caller polls for the result, so the receive span
 * cannot be its child. Both sides instead reference the same {@link AgentTraceContext},
 * whose delegation identifier becomes the span link key of the consumer. Entries are only
 * recorded when an {@link AgentTracer} is enabled.
 * <p>
 * Entries have a three-layer lifecycle (issue #228, #370 review): a rejected submission
 * rolls the record back at the delegation site; a running task's entry has no expiry at
 * all, because its delegation link must survive however long the task runs; completion
 * re-anchors the entry at the delegation site ({@link #refresh}, measured from completion
 * rather than submission), after which polls slide the window and the retention sweeps
 * entries nobody polls for. Repository remove, clear and shutdown report through
 * {@link TaskRepository#addLifecycleListener}, so a shared store converges without waiting
 * for another poll; the retention window then only has to cover results that leave the
 * repository through internal expiry.
 */
public final class TaskTraceCorrelation {

	private final Map<String, StampedContext> contextsByTaskId = new ConcurrentHashMap<>();

	private final Clock clock;

	private final Duration retention;

	public TaskTraceCorrelation() {
		this(Clock.systemUTC(), Duration.ofMinutes(30));
	}

	/**
	 * @param clock the time source driving retention
	 * @param retention how long a recorded correlation may live without being consumed
	 */
	public TaskTraceCorrelation(Clock clock, Duration retention) {
		Assert.notNull(clock, "clock must not be null");
		Assert.notNull(retention, "retention must not be null");
		Assert.isTrue(!retention.isNegative(), "retention must not be negative");
		this.clock = clock;
		this.retention = retention;
	}

	/** @return an empty correlation store */
	public static TaskTraceCorrelation empty() {
		return new TaskTraceCorrelation();
	}

	/**
	 * Record the correlation facts of a background delegation. The entry is active and
	 * never expires while the task runs; only {@link #refresh} starts its retention
	 * window. Expired completed entries are swept along the way.
	 * @param taskId the background task id
	 * @param context the correlation facts captured at the delegation site
	 */
	public void record(String taskId, AgentTraceContext context) {
		if (taskId != null && context != null) {
			sweepExpired();
			this.contextsByTaskId.put(taskId, new StampedContext(context, null));
		}
	}

	/**
	 * @param taskId the background task id
	 * @return the correlation facts recorded at the delegation site, if any; an expired
	 * entry is removed and never served. Any lookup also sweeps expired entries across
	 * the whole store, so convergence never depends on a new submission (#370 review).
	 */
	public Optional<AgentTraceContext> correlation(String taskId) {
		if (taskId == null) {
			return Optional.empty();
		}
		sweepExpired();
		StampedContext stamped = this.contextsByTaskId.get(taskId);
		if (stamped == null) {
			return Optional.empty();
		}
		if (isExpired(stamped)) {
			this.contextsByTaskId.remove(taskId, stamped);
			return Optional.empty();
		}
		if (stamped.expiresAt() == null) {
			// Still running: there is no retention window to slide, and concurrent
			// submissions or polls must never sweep it (#370 review gap 1).
			return Optional.of(stamped.context());
		}
		// Sliding retention: reading a completed entry re-anchors its expiry, so the
		// receive link of a still-pollable task result never lapses between polls.
		StampedContext refreshed = new StampedContext(stamped.context(), deadline());
		this.contextsByTaskId.replace(taskId, stamped, refreshed);
		return Optional.of(refreshed.context());
	}

	/**
	 * Mark a recorded entry completed and start its retention window. The delegation site
	 * calls this when the background task finishes, so the pollable result window is
	 * measured from completion rather than submission. An absent entry is left absent:
	 * a repository removal that already released the correlation must not be resurrected
	 * by the completion callback.
	 * @param taskId the background task id
	 */
	public void refresh(String taskId) {
		if (taskId == null) {
			return;
		}
		StampedContext stamped = this.contextsByTaskId.get(taskId);
		if (stamped != null) {
			this.contextsByTaskId.replace(taskId, stamped, new StampedContext(stamped.context(), deadline()));
		}
	}

	/**
	 * Drop the correlation facts of one task. Called from the receive site when a poll
	 * finds the task gone.
	 * @param taskId the background task id
	 */
	public void forget(String taskId) {
		if (taskId != null) {
			sweepExpired();
			this.contextsByTaskId.remove(taskId);
		}
	}

	/**
	 * Drop the correlation facts of tasks that the repository reports as removed, cleared,
	 * expired or shut down. Registered as a {@link TaskRepository.TaskLifecycleListener}
	 * by both task tools, so cleanup no longer waits for the next poll or submission.
	 * @param taskIds the identifiers that are no longer retrievable
	 */
	public void forgetAll(Collection<String> taskIds) {
		if (taskIds == null || taskIds.isEmpty()) {
			return;
		}
		taskIds.forEach(this.contextsByTaskId::remove);
	}

	/** Test observation point for the retention assertions. */
	int retainedCount() {
		return this.contextsByTaskId.size();
	}

	/** Removes completed entries past their window; running entries are never swept. */
	private void sweepExpired() {
		this.contextsByTaskId.forEach((taskId, stamped) -> {
			if (isExpired(stamped)) {
				this.contextsByTaskId.remove(taskId, stamped);
			}
		});
	}

	private boolean isExpired(StampedContext stamped) {
		// A null expiry marks a running task: no amount of elapsed time expires it.
		return stamped.expiresAt() != null && this.clock.instant().isAfter(stamped.expiresAt());
	}

	private Instant deadline() {
		return this.clock.instant().plus(this.retention);
	}

	/** {@code expiresAt} is null while the delegated task is still running. */
	private record StampedContext(AgentTraceContext context, Instant expiresAt) {

	}

}
