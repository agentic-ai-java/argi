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

package io.github.agentic.ai.graph.execution.run;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * One consistent, immutable read of a run and its atomic data. Revision covers the whole
 * snapshot, including checkpoint, tasks, journal and outbox. A null checkpoint means none
 * has been stored. Outbox entries remain available after completion for external
 * delivery.
 */
public record RunSnapshot(RunSpec spec, long revision, RunState state, RunPayload checkpoint,
		Map<String, TaskRecord> tasks, Map<String, RunEntry> journal, Map<String, RunEntry> outbox) {

	public RunSnapshot {
		requireNonNull(spec, "spec cannot be null");
		requireNonNull(state, "state cannot be null");
		if (revision < 0) {
			throw new IllegalArgumentException("revision must be non-negative");
		}
		tasks = Map.copyOf(tasks);
		journal = Map.copyOf(journal);
		outbox = Map.copyOf(outbox);
	}
}
