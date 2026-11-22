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
package io.github.agentic.ai.workflow.graph.checkpoint;

import io.github.agentic.ai.runtime.execution.ExecutionGuard;

/**
 * Optional invocation-owned checkpoint boundary. It replaces the configured saver for
 * this invocation so execution journals and graph checkpoints can share one commit. It
 * borrows external ownership; the graph never acquires, releases or rewinds it.
 */
public interface GraphCheckpointBoundary {

	Checkpoint loadOrCreate(Checkpoint initial) throws Exception;

	void commit(Checkpoint checkpoint) throws Exception;

	void complete(Checkpoint checkpoint) throws Exception;

	ExecutionGuard guard();

}
