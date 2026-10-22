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
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Immutable named limits carried with a trusted execution context. This is a limit
 * contract, not a usage ledger; durable consumption accounting belongs to the runtime
 * that enforces a particular budget.
 */
public record ExecutionBudget(Map<String, Long> limits) {

	private static final ExecutionBudget UNLIMITED = new ExecutionBudget(Map.of());

	public ExecutionBudget {
		requireNonNull(limits, "limits cannot be null");
		TreeMap<String, Long> copy = new TreeMap<>();
		limits.forEach((name, value) -> {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("budget name must not be blank");
			}
			if (value == null || value < 0) {
				throw new IllegalArgumentException("budget limit must be non-negative: " + name);
			}
			copy.put(name, value);
		});
		limits = Collections.unmodifiableMap(copy);
	}

	public static ExecutionBudget of(Map<String, Long> limits) {
		return limits == null || limits.isEmpty() ? unlimited() : new ExecutionBudget(limits);
	}

	public static ExecutionBudget unlimited() {
		return UNLIMITED;
	}

	/**
	 * Apply additional child limits without allowing an existing parent limit to be
	 * increased or removed. Omitted child entries inherit the parent limit.
	 */
	public ExecutionBudget narrowTo(ExecutionBudget requested) {
		requireNonNull(requested, "requested budget cannot be null");
		TreeMap<String, Long> narrowed = new TreeMap<>(this.limits);
		requested.limits.forEach((name, requestedLimit) -> {
			Long parentLimit = this.limits.get(name);
			if (parentLimit != null && requestedLimit > parentLimit) {
				throw new IllegalArgumentException("delegated budget cannot exceed parent budget: " + name);
			}
			narrowed.put(name, requestedLimit);
		});
		return narrowed.equals(this.limits) ? this : new ExecutionBudget(narrowed);
	}
}
