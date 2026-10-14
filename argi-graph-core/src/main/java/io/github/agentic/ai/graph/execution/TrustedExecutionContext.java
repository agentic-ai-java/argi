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
package io.github.agentic.ai.graph.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable authentication facts and execution constraints created by an application
 * authentication boundary. Ordinary graph state, model arguments and RunnableConfig
 * metadata cannot create or replace this context implicitly.
 */
public final class TrustedExecutionContext {

	private final TenantReference tenant;

	private final PrincipalReference principal;

	private final String runId;

	private final String correlationId;

	private final Instant deadline;

	private final ExecutionBudget budget;

	private final DelegationScope delegationScope;

	private TrustedExecutionContext(Builder builder) {
		this.tenant = requireNonNull(builder.tenant, "tenant cannot be null");
		this.principal = requireNonNull(builder.principal, "principal cannot be null");
		this.runId = requireText(builder.runId, "runId");
		this.correlationId = requireText(builder.correlationId, "correlationId");
		this.deadline = builder.deadline;
		this.budget = requireNonNull(builder.budget, "budget cannot be null");
		this.delegationScope = requireNonNull(builder.delegationScope, "delegationScope cannot be null");
	}

	public static Builder builder() {
		return new Builder();
	}

	public TenantReference tenant() {
		return this.tenant;
	}

	public PrincipalReference principal() {
		return this.principal;
	}

	public String runId() {
		return this.runId;
	}

	public String correlationId() {
		return this.correlationId;
	}

	public Optional<Instant> deadline() {
		return Optional.ofNullable(this.deadline);
	}

	public ExecutionBudget budget() {
		return this.budget;
	}

	public DelegationScope delegationScope() {
		return this.delegationScope;
	}

	public boolean isExpired(Instant now) {
		requireNonNull(now, "now cannot be null");
		return this.deadline != null && !now.isBefore(this.deadline);
	}

	/**
	 * Derive a local child execution. Tenant, principal and correlation remain stable;
	 * permissions, deadline and budget may only become narrower.
	 */
	public TrustedExecutionContext delegate(String childRunId, Set<String> permissions, Instant requestedDeadline,
			ExecutionBudget requestedBudget) {
		DelegationScope childScope = this.delegationScope.narrowTo(permissions);
		Instant childDeadline = requestedDeadline != null ? requestedDeadline : this.deadline;
		if (this.deadline != null && childDeadline != null && childDeadline.isAfter(this.deadline)) {
			throw new IllegalArgumentException("delegated deadline cannot exceed parent deadline");
		}
		ExecutionBudget childBudget = this.budget.narrowTo(
				requestedBudget != null ? requestedBudget : ExecutionBudget.unlimited());
		return builder().tenant(this.tenant)
			.principal(this.principal)
			.runId(childRunId)
			.correlationId(this.correlationId)
			.deadline(childDeadline)
			.budget(childBudget)
			.delegationScope(childScope)
			.build();
	}

	public TrustedExecutionContextReference toReference() {
		return new TrustedExecutionContextReference(TrustedExecutionContextReference.CURRENT_SCHEMA_VERSION, this.tenant,
				this.principal, this.runId, this.correlationId, this.deadline != null ? this.deadline.toEpochMilli() : null,
				this.budget.limits(), this.delegationScope.permissions());
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof TrustedExecutionContext that)) {
			return false;
		}
		return this.tenant.equals(that.tenant) && this.principal.equals(that.principal)
				&& this.runId.equals(that.runId) && this.correlationId.equals(that.correlationId)
				&& Objects.equals(this.deadline, that.deadline) && this.budget.equals(that.budget)
				&& this.delegationScope.equals(that.delegationScope);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.tenant, this.principal, this.runId, this.correlationId, this.deadline, this.budget,
				this.delegationScope);
	}

	@Override
	public String toString() {
		return "TrustedExecutionContext{tenant=" + this.tenant + ", principal=" + this.principal + ", runId='"
				+ this.runId + "', correlationId='" + this.correlationId + "', deadline=" + this.deadline + ", budget="
				+ this.budget + ", delegationScope=" + this.delegationScope + '}';
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	public static final class Builder {

		private TenantReference tenant;

		private PrincipalReference principal;

		private String runId;

		private String correlationId;

		private Instant deadline;

		private ExecutionBudget budget = ExecutionBudget.unlimited();

		private DelegationScope delegationScope = DelegationScope.none();

		private Builder() {
		}

		public Builder tenant(TenantReference tenant) {
			this.tenant = tenant;
			return this;
		}

		public Builder principal(PrincipalReference principal) {
			this.principal = principal;
			return this;
		}

		public Builder runId(String runId) {
			this.runId = runId;
			return this;
		}

		public Builder correlationId(String correlationId) {
			this.correlationId = correlationId;
			return this;
		}

		public Builder deadline(Instant deadline) {
			this.deadline = deadline;
			return this;
		}

		public Builder budget(ExecutionBudget budget) {
			this.budget = budget;
			return this;
		}

		public Builder delegationScope(DelegationScope delegationScope) {
			this.delegationScope = delegationScope;
			return this;
		}

		public TrustedExecutionContext build() {
			return new TrustedExecutionContext(this);
		}
	}
}
