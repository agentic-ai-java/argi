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

package io.github.agentic.ai.graph.agent.interceptor.toolselection;

/**
 * Opt-in behavior when tool selection fails or returns a malformed response.
 *
 * <p>The historical and default behavior is {@link #FAIL_OPEN_ALL_TOOLS}: every
 * available tool stays exposed after a selection failure. Strict configurations can
 * bound the blast radius instead, so a selection failure never widens the tool set a
 * strict run is allowed to see.</p>
 */
public enum ToolSelectionFailurePolicy {

	/**
	 * Legacy default: a failed or malformed selection falls back to exposing every
	 * available tool.
	 */
	FAIL_OPEN_ALL_TOOLS,

	/**
	 * Fail closed to the bounded always-include set: only tools explicitly marked
	 * always-include remain exposed, possibly none. The run continues with that bounded
	 * set, or without tools when it is empty.
	 */
	FAIL_CLOSED_ALWAYS_INCLUDE,

	/**
	 * Fail closed by aborting the model call with a
	 * {@link ToolSelectionFailedException} carrying the recorded decision.
	 */
	FAIL_CLOSED_THROW

}
