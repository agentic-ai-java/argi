/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.runtime.execution.run.metrics;

import io.github.agentic.ai.runtime.execution.run.RunNamespace;
import io.github.agentic.ai.runtime.execution.run.RunState;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One bounded aggregate read of persisted run state stock: a namespace scope plus
 * the states of interest. An empty state set asks for every {@link RunState} value.
 * The namespace is the aggregation scope — never a metric dimension — and run or
 * tenant identifiers must not appear in the query, keeping gauge cardinality bounded
 * (issue #318).
 *
 * @param namespace the aggregation scope, never null
 * @param states the states of interest, never null; empty means all states
 */
public record PersistedRunStateQuery(RunNamespace namespace, Set<RunState> states) {

	public PersistedRunStateQuery {
		Objects.requireNonNull(namespace, "namespace must not be null");
		Objects.requireNonNull(states, "states must not be null");
		states = Set.copyOf(states);
	}

	public static PersistedRunStateQuery allStates(RunNamespace namespace) {
		return new PersistedRunStateQuery(namespace, EnumSet.noneOf(RunState.class));
	}

}
