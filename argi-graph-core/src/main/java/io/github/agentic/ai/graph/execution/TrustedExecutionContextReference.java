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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

/**
 * Versioned persistence envelope containing references and constraints only. Decoding it
 * does not authenticate a caller; an application must pass it to a
 * {@link TrustedExecutionContextResolver} and re-establish a currently valid context.
 */
public record TrustedExecutionContextReference(int schemaVersion, TenantReference tenant,
		PrincipalReference principal, String runId, String correlationId, Long deadlineEpochMillis,
		Map<String, Long> budgetLimits, Set<String> delegationPermissions) {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	public TrustedExecutionContextReference {
		if (schemaVersion <= 0) {
			throw new IllegalArgumentException("schemaVersion must be positive");
		}
		requireNonNull(tenant, "tenant cannot be null");
		requireNonNull(principal, "principal cannot be null");
		if (runId == null || runId.isBlank()) {
			throw new IllegalArgumentException("runId must not be blank");
		}
		if (correlationId == null || correlationId.isBlank()) {
			throw new IllegalArgumentException("correlationId must not be blank");
		}
		budgetLimits = Collections.unmodifiableMap(new TreeMap<>(new ExecutionBudget(
				requireNonNull(budgetLimits, "budgetLimits cannot be null")).limits()));
		delegationPermissions = Collections.unmodifiableSet(new TreeSet<>(new DelegationScope(
				requireNonNull(delegationPermissions, "delegationPermissions cannot be null")).permissions()));
	}
}
