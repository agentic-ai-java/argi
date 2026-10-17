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
package io.github.agentic.ai.graph.execution.durable;

/** Application-authored replay guarantee for the complete pinned graph and tool catalog. */
public enum ReplaySafety {
	/** Every reachable action, routing callback and tool is read-only. */
	READ_ONLY,
	/** Repeating any uncommitted action is safe, including its external side effects. */
	REPLAYABLE,
	/** Side effects have no replay guarantee; admission must reject the definition. */
	UNSAFE;

	void requireSafe() {
		if (this == UNSAFE) {
			throw new IllegalArgumentException("Durable execution requires explicit replay-safe actions and tools");
		}
	}
}
