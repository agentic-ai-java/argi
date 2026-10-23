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

import static java.util.Objects.requireNonNull;

/**
 * Append-only journal/event envelope. Stable ids identify model/task attempts, approvals
 * or events. Payload schema evolution is independent of the storage envelope. Reusing an
 * id with different data is a conflict, not an overwrite. No side effect is performed
 * here.
 */
public record RunEntry(int schemaVersion, String id, Kind kind, RunPayload payload) {

	public RunEntry {
		RunValidation.version(schemaVersion);
		RunValidation.text(id, "id");
		requireNonNull(kind, "kind cannot be null");
		requireNonNull(payload, "payload cannot be null");
	}

	public enum Kind {

		MODEL_CALL, TASK_CALL, APPROVAL, EVENT

	}
}
