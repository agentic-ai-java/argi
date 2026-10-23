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
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionLease;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseRequiredException;

import java.util.UUID;

/**
 * Opt-in checkpoint saver contract for leased checkpoint mutations.
 */
public interface LeasedCheckpointSaver extends VersionedCheckpointSaver {

	LeaseOptions leaseOptions();

	ExecutionLease acquireLease(RunnableConfig config, UUID ownerId) throws Exception;

	ExecutionLease renewLease(RunnableConfig config, ExecutionLease lease) throws Exception;

	boolean releaseLease(RunnableConfig config, ExecutionLease lease) throws Exception;

	RunnableConfig putIfLeasedVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision,
			ExecutionLease lease) throws Exception;

	Tag releaseIfLeasedVersion(RunnableConfig config, long expectedRevision, ExecutionLease lease) throws Exception;

	@Override
	default RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		throw new LeaseRequiredException(checkpointThreadId(config));
	}

	@Override
	default Tag release(RunnableConfig config) throws Exception {
		throw new LeaseRequiredException(checkpointThreadId(config));
	}

	@Override
	default RunnableConfig putIfVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision)
			throws Exception {
		throw new LeaseRequiredException(checkpointThreadId(config));
	}

	@Override
	default Tag releaseIfVersion(RunnableConfig config, long expectedRevision) throws Exception {
		throw new LeaseRequiredException(checkpointThreadId(config));
	}

}
