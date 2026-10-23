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

package io.github.agentic.ai.graph.execution.run;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.observation.trace.AgentTraceContext;
import io.github.agentic.ai.graph.observation.trace.AgentTraceOperation;
import io.github.agentic.ai.graph.observation.trace.AgentTraceSpan;
import io.github.agentic.ai.graph.observation.trace.AgentTracer;
import org.springframework.util.Assert;

/**
 * Run store decorator that emits cross-agent trace spans for the durable run lifecycle.
 * <p>
 * Wrapping a store with this decorator is the explicit opt-in for durable runner
 * correlation: {@code submit}, {@code acquire} (the recovery point after a restart) and
 * {@code commit} each emit an {@code argi.run.store} span carrying tenant, run id, task ids
 * and the lease fencing token as the attempt identifier. Read-only lookups pass through
 * untouched, and the decorator changes no storage semantics.
 */
public final class ObservedRunStore implements RunStore {

	private static final int MAX_TASK_IDS = 16;

	private final RunStore delegate;

	private final AgentTracer tracer;

	public ObservedRunStore(RunStore delegate, AgentTracer tracer) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(tracer, "tracer must not be null");
		this.delegate = delegate;
		this.tracer = tracer;
	}

	/** @return the wrapped store */
	public RunStore delegate() {
		return this.delegate;
	}

	@Override
	public RunStoreCapabilities capabilities() {
		return this.delegate.capabilities();
	}

	@Override
	public RunSnapshot submit(RunSpec spec, TrustedExecutionContext context) {
		AgentTraceSpan span = startSpan("submit", tenantOf(spec), spec.runId());
		try {
			RunSnapshot snapshot = this.delegate.submit(spec, context);
			span.success();
			return snapshot;
		}
		catch (RuntimeException e) {
			span.error(e);
			throw e;
		}
		finally {
			span.close();
		}
	}

	@Override
	public Optional<RunSnapshot> find(RunNamespace namespace, String runId) {
		return this.delegate.find(namespace, runId);
	}

	@Override
	public Optional<String> activeRun(RunNamespace namespace) {
		return this.delegate.activeRun(namespace);
	}

	@Override
	public RunLease acquire(RunNamespace namespace, String runId, UUID ownerId, Duration ttl) {
		AgentTraceSpan span = startSpan("acquire", tenantOf(namespace), runId);
		try {
			RunLease lease = this.delegate.acquire(namespace, runId, ownerId, ttl);
			if (lease != null && !span.isDisabled()) {
				span.attempt(String.valueOf(lease.fencingToken()));
			}
			span.success();
			return lease;
		}
		catch (RuntimeException e) {
			span.error(e);
			throw e;
		}
		finally {
			span.close();
		}
	}

	@Override
	public RunLease renew(RunLease lease, Duration ttl) {
		return this.delegate.renew(lease, ttl);
	}

	@Override
	public boolean release(RunLease lease) {
		return this.delegate.release(lease);
	}

	@Override
	public RunSnapshot commit(RunNamespace namespace, String runId, RunLease lease, RunCommit commit) {
		AgentTraceSpan span = startSpan("commit", tenantOf(namespace), runId);
		try {
			RunSnapshot snapshot = this.delegate.commit(namespace, runId, lease, commit);
			span.taskId(taskIdsOf(commit));
			span.success();
			return snapshot;
		}
		catch (RuntimeException e) {
			span.error(e);
			throw e;
		}
		finally {
			span.close();
		}
	}

	private AgentTraceSpan startSpan(String phase, String tenant, String runId) {
		return this.tracer.start(AgentTraceOperation.RUN_STORE, AgentTraceContext.builder().tenant(tenant).runId(runId).build())
			.phase(phase);
	}

	private String tenantOf(RunSpec spec) {
		return spec != null ? tenantOf(spec.namespace()) : null;
	}

	private String tenantOf(RunNamespace namespace) {
		return namespace != null && namespace.tenant() != null ? namespace.tenant().id() : null;
	}

	private String taskIdsOf(RunCommit commit) {
		if (commit == null || commit.tasks() == null) {
			return null;
		}
		String joined = commit.tasks()
			.stream()
			.map(RunCommit.TaskUpdate::spec)
			.filter(Objects::nonNull)
			.map(TaskSpec::taskId)
			.filter(Objects::nonNull)
			.limit(MAX_TASK_IDS)
			.collect(Collectors.joining(","));
		return joined.isEmpty() ? null : joined;
	}

}
