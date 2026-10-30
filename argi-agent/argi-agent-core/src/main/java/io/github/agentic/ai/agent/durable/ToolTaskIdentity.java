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
package io.github.agentic.ai.agent.durable;

/**
 * Stable logical tool-task identity within the run's namespace. The caller owns turn
 * allocation and recovery. Attempts, provider call ids and argument hashes are not part
 * of this identity; callers using it outside a run must retain its RunNamespace as well.
 * @param runId the owning run
 * @param turnId the caller-supplied logical model call
 * @param ordinal the zero-based position in the response's tool-call list
 */
public record ToolTaskIdentity(String runId, String turnId, int ordinal) {

	public ToolTaskIdentity {
		if (runId == null || runId.isBlank() || turnId == null || turnId.isBlank()) {
			throw new IllegalArgumentException("runId and turnId must not be blank");
		}
		if (ordinal < 0) {
			throw new IllegalArgumentException("ordinal must be non-negative");
		}
	}

	/** Journal key local to the owning run, not a globally scoped idempotency key. */
	public String entryId() {
		return ModelTurnEntryIds.plan(new ModelTurnRecorder.Turn(runId, turnId), ordinal);
	}

}
