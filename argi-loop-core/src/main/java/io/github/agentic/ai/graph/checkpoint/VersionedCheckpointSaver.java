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

import io.github.agentic.ai.graph.RunnableConfig;

/**
 * Opt-in checkpoint saver contract for compare-and-set checkpoint mutations.
 * <p>
 * Implementations expose a namespace revision alongside checkpoint reads. Successful
 * conditional puts and releases must increment the namespace revision exactly once.
 * Negative expected revisions are invalid, stale expected revisions must throw
 * {@link CheckpointConflictException} without mutation, and revision overflow must fail
 * before mutating storage. Releasing an empty namespace still creates a positive
 * empty tombstone revision.
 */
public interface VersionedCheckpointSaver extends BaseCheckpointSaver {

	/**
	 * Reads the checkpoint selected by {@code config} together with the current exact
	 * namespace revision.
	 */
	CheckpointSnapshot getVersioned(RunnableConfig config);

	/**
	 * Writes {@code checkpoint} only when the namespace is currently at
	 * {@code expectedRevision}; implementations must not retry on conflict.
	 */
	RunnableConfig putIfVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision) throws Exception;

	/**
	 * Releases the namespace only when it is currently at {@code expectedRevision};
	 * release of an empty namespace creates a positive empty tombstone.
	 */
	Tag releaseIfVersion(RunnableConfig config, long expectedRevision) throws Exception;

}
