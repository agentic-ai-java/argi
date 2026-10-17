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
 * The next action an agent decides to take after a model turn.
 *
 * <p>The default ReAct routing keeps its current behavior and does not produce explicit
 * decisions; this vocabulary makes the routing outcome explicit and observable wherever
 * an opt-in policy records one.</p>
 */
public enum ActionType {

	/**
	 * The model requested tool calls; the run routes to tool execution.
	 */
	TOOL_CALL,

	/**
	 * The model produced an answer without requesting tools; the run finishes with that
	 * response.
	 */
	FINAL_RESPONSE,

	/**
	 * The agent cannot proceed without additional external input before the next model
	 * turn. Reserved for later human-in-the-loop integration.
	 */
	NEED_INPUT,

	/**
	 * The current execution plan is no longer adequate and routing should restart from
	 * planning. Reserved for later planner integration.
	 */
	REPLAN,

	/**
	 * Bounded exit: stop the run instead of widening the execution scope, for example
	 * when a strict configuration refuses to fall back to a broader tool set.
	 */
	STOP

}
