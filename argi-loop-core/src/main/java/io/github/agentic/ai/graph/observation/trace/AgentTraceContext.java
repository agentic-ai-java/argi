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

package io.github.agentic.ai.graph.observation.trace;

import java.util.Map;
import java.util.Optional;

import io.github.agentic.ai.graph.GraphLifecycleListener;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

/**
 * Immutable correlation facts carried with every cross-agent trace span.
 * <p>
 * Identifiers are captured from the invocation-local {@link RunnableConfig} (thread id and
 * the {@link TrustedExecutionContext} facts tenant and run id) and from the graph execution
 * state (the per-execution attempt id). Instances are value objects: they are captured on
 * the delegating thread and handed to worker threads explicitly, so parallel chains never
 * read each other's state.
 */
public record AgentTraceContext(String tenant, String runId, String threadId, String taskId, String attemptId,
		String delegationId) {

	/**
	 * Capture correlation facts from an invocation-local config and the graph state.
	 * @param config the invocation-local runnable config, may be null
	 * @param state the graph execution state, may be null
	 * @return a context with every fact that could be derived; unknown facts stay null
	 */
	public static AgentTraceContext capture(RunnableConfig config, Map<String, Object> state) {
		String tenant = null;
		String runId = null;
		if (config != null) {
			Optional<TrustedExecutionContext> trusted = config.trustedExecutionContext();
			if (trusted.isPresent()) {
				TenantReference tenantReference = trusted.get().tenant();
				tenant = tenantReference != null ? tenantReference.id() : null;
				runId = trusted.get().runId();
			}
		}
		String threadId = config != null ? config.threadId().orElse(null) : null;
		String attemptId = state != null && state.get(GraphLifecycleListener.EXECUTION_ID_KEY) instanceof String executionId
				? executionId : null;
		return new AgentTraceContext(tenant, runId, threadId, null, attemptId, null);
	}

	/** @return a copy of this context with the given task id */
	public AgentTraceContext withTaskId(String newTaskId) {
		return new AgentTraceContext(this.tenant, this.runId, this.threadId, newTaskId, this.attemptId,
				this.delegationId);
	}

	/** @return a copy of this context with the given delegation id */
	public AgentTraceContext withDelegationId(String newDelegationId) {
		return new AgentTraceContext(this.tenant, this.runId, this.threadId, this.taskId, this.attemptId,
				newDelegationId);
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Fluent builder for hand-assembled contexts, for example inside a run store decorator. */
	public static final class Builder {

		private String tenant;

		private String runId;

		private String threadId;

		private String taskId;

		private String attemptId;

		private String delegationId;

		public Builder tenant(String tenant) {
			this.tenant = tenant;
			return this;
		}

		public Builder runId(String runId) {
			this.runId = runId;
			return this;
		}

		public Builder threadId(String threadId) {
			this.threadId = threadId;
			return this;
		}

		public Builder taskId(String taskId) {
			this.taskId = taskId;
			return this;
		}

		public Builder attemptId(String attemptId) {
			this.attemptId = attemptId;
			return this;
		}

		public Builder delegationId(String delegationId) {
			this.delegationId = delegationId;
			return this;
		}

		public AgentTraceContext build() {
			return new AgentTraceContext(this.tenant, this.runId, this.threadId, this.taskId, this.attemptId,
					this.delegationId);
		}

	}

}
