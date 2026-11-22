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
 * Entries have a three-layer lifecycle (issue #228): a rejected submission rolls the record
 * back at the delegation site, a terminal polled result releases it at the receive site once
 * the receive span has been linked, and the retention window below sweeps whatever survives
 * (tasks nobody polls for, repository clear or shutdown). The retention is deliberately long
 * relative to a polling window and independent of the repository type, so the default, the
 * bounded and any injected repository all converge without the store needing repository
 * lifecycle hooks; a live entry is never dropped early just to reclaim space.
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
	 * Record the correlation facts of a background delegation. Expired entries are swept
	 * along the way so a store without consumers still converges to its live entries.
	 * @param taskId the background task id
	 * @param context the correlation facts captured at the delegation site
	 */
	public void record(String taskId, AgentTraceContext context) {
		if (taskId != null && context != null) {
			sweepExpired();
			this.contextsByTaskId.put(taskId, new StampedContext(context, deadline()));
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
		// Sliding retention: reading a live entry re-anchors its expiry, so the receive
		// link of a still-pollable task result never lapses between polls.
		StampedContext refreshed = new StampedContext(stamped.context(), deadline());
		this.contextsByTaskId.replace(taskId, stamped, refreshed);
		return Optional.of(refreshed.context());
	}

	/**
	 * Re-anchor the retention of a recorded entry, even one whose submission-anchored
	 * window has already lapsed (#370 review): the delegation site calls this when the
	 * background task finishes, so the pollable result window is measured from
	 * completion rather than submission and a long-running task keeps its link.
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
	 * Drop the correlation facts of a finished background task. Sweeping expired entries
	 * along the way ties the cleanup to every release event, so a repository removal,
	 * clear or shutdown reported through this call converges the whole store without a
	 * new submission (#370 review gap 2).
	 * @param taskId the background task id
	 */
	public void forget(String taskId) {
		if (taskId != null) {
			sweepExpired();
			this.contextsByTaskId.remove(taskId);
		}
	}

	/** Test observation point for the retention assertions. */
	int retainedCount() {
		return this.contextsByTaskId.size();
	}

	private void sweepExpired() {
		this.contextsByTaskId.forEach((taskId, stamped) -> {
			if (isExpired(stamped)) {
				this.contextsByTaskId.remove(taskId, stamped);
			}
		});
	}

	private boolean isExpired(StampedContext stamped) {
		return this.clock.instant().isAfter(stamped.expiresAt());
	}

	private java.time.Instant deadline() {
		return this.clock.instant().plus(this.retention);
	}

	private record StampedContext(AgentTraceContext context, java.time.Instant expiresAt) {

	}

}
