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

package io.github.agentic.ai.graph.agent.action;

/**
 * Short, stable reason codes carried by {@link ActionDecision}.
 *
 * <p>Reason codes are operational labels, never private model reasoning. Codes cover tool
 * selection outcomes and the bounded-exit policies of the task completion work.</p>
 */
public final class ActionReasonCodes {

	/**
	 * Tool selection legally resolved to an empty tool set, so the run proceeds without
	 * tools.
	 */
	public static final String TOOL_SELECTION_EMPTY = "TOOL_SELECTION_EMPTY";

	/**
	 * The tool selection response could not be parsed as a valid tool selection.
	 */
	public static final String TOOL_SELECTION_MALFORMED = "TOOL_SELECTION_MALFORMED";

	/**
	 * The tool selection model call itself failed.
	 */
	public static final String TOOL_SELECTION_MODEL_FAILED = "TOOL_SELECTION_MODEL_FAILED";

	/**
	 * Required tasks are still unfinished and the bounded re-ask budget is exhausted, so
	 * the run terminates with an explicit state instead of a plain success.
	 */
	public static final String UNFINISHED_REQUIRED_TASKS = "UNFINISHED_REQUIRED_TASKS";

	/**
	 * The bounded re-ask budget is exhausted and every no-tool answer in the sequence was
	 * identical: the run is not making progress.
	 */
	public static final String NO_PROGRESS = "NO_PROGRESS";

	/**
	 * The same decision fingerprint (identical tool calls or identical final answer)
	 * repeated for too many consecutive model turns.
	 */
	public static final String REPEATED_DECISION = "REPEATED_DECISION";

	/**
	 * More tool calls to unresolvable tool names occurred in this run than the configured
	 * bound allows.
	 */
	public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";

	private ActionReasonCodes() {
	}

}
