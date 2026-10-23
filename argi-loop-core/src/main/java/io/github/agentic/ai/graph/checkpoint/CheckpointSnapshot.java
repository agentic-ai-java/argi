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
package io.github.agentic.ai.graph.checkpoint;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable versioned read result for a checkpoint namespace.
 * <p>
 * A never-written namespace is represented by an empty checkpoint at revision {@code 0}.
 * Empty snapshots with a positive revision are release tombstones. Saver implementations
 * must provide independently owned checkpoint instances in snapshots so callers cannot
 * mutate saver-owned storage.
 */
public record CheckpointSnapshot(Optional<Checkpoint> checkpoint, long revision) {

	public CheckpointSnapshot {
		requireNonNull(checkpoint, "checkpoint cannot be null");
		if (revision < 0) {
			throw new IllegalArgumentException("revision cannot be negative");
		}
		if (revision == 0 && checkpoint.isPresent()) {
			throw new IllegalArgumentException("present checkpoint requires a positive revision");
		}
	}

}
