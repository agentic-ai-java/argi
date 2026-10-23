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

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * One all-or-nothing write. Null checkpoint retains the previous checkpoint. Tasks use
 * expectedRevision=-1 for creation (PENDING only), otherwise exact CAS. Terminal runs
 * cannot contain active tasks. Journals/outbox are append-only; outbox accepts EVENT
 * only.
 */
public record RunCommit(long expectedRevision, RunState state, RunPayload checkpoint, List<TaskUpdate> tasks,
		List<RunEntry> journal, List<RunEntry> outbox) {

	public RunCommit {
		if (expectedRevision < 0) {
			throw new IllegalArgumentException("expectedRevision must be non-negative");
		}
		requireNonNull(state, "state cannot be null");
		tasks = List.copyOf(tasks);
		journal = List.copyOf(journal);
		outbox = List.copyOf(outbox);
	}

	public record TaskUpdate(TaskSpec spec, long expectedRevision, RunState state) {

		public TaskUpdate {
			requireNonNull(spec, "spec cannot be null");
			requireNonNull(state, "state cannot be null");
			if (expectedRevision < -1) {
				throw new IllegalArgumentException("task expectedRevision must be at least -1");
			}
		}
	}
}
