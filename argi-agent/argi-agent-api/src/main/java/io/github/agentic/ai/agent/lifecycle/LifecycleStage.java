/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.agent.lifecycle;

/**
 * The lifecycle stages an extension may bind to.
 *
 * <p>
 * The catalog distinguishes two scopes:
 *
 * <ul>
 * <li>{@link Scope#COMMON_RUNTIME} — stages every engine is expected to expose, at
 * least at the level of the neutral contract.</li>
 * <li>{@link Scope#ENGINE_SPECIFIC} — stages that only a particular engine family can
 * offer. The neutral contract does not promise them; an engine that implements one must
 * declare the mapping explicitly.</li>
 * </ul>
 *
 * <p>
 * Two boundaries are deliberate. A workflow step is not a model turn: a workflow engine
 * must not fabricate model turns to satisfy the catalog. A remote agent is not an
 * in-process tool caller: the neutral contract only exposes the remote agent's own
 * public run-level stages, never the internals of its tool execution.
 */
public enum LifecycleStage {

	/**
	 * Definition validation and creation.
	 */
	DEFINITION(LifecycleScope.COMMON_RUNTIME, LifecycleCapabilities.synchronousOnly()),

	/**
	 * Admission of a run before any work is scheduled.
	 */
	RUN_ADMISSION(LifecycleScope.COMMON_RUNTIME, LifecycleCapabilities.synchronousOnly()),

	/**
	 * Start of an admitted run.
	 */
	RUN_START(LifecycleScope.COMMON_RUNTIME, LifecycleCapabilities.synchronousOnly()),

	/**
	 * Construction of the execution context of a run.
	 */
	CONTEXT_BUILD(LifecycleScope.COMMON_RUNTIME, LifecycleCapabilities.synchronousOnly()),

	/**
	 * A single model call.
	 */
	MODEL_CALL(LifecycleScope.COMMON_RUNTIME,
			new LifecycleCapabilities(true, true, true, false, false)),

	/**
	 * A streamed model call.
	 */
	MODEL_STREAM(LifecycleScope.COMMON_RUNTIME,
			new LifecycleCapabilities(false, true, true, true, false)),

	/**
	 * The decision of which action to take next.
	 */
	ACTION_DECISION(LifecycleScope.COMMON_RUNTIME, LifecycleCapabilities.synchronousOnly()),

	/**
	 * A single tool call.
	 */
	TOOL_CALL(LifecycleScope.COMMON_RUNTIME, new LifecycleCapabilities(true, true, true, false, false)),

	/**
	 * Delegation, handover and join of work.
	 */
	DELEGATION(LifecycleScope.COMMON_RUNTIME, new LifecycleCapabilities(true, true, false, false, false)),

	/**
	 * A workflow graph step. Engine specific: a workflow engine owns this stage.
	 */
	WORKFLOW_STEP(LifecycleScope.ENGINE_SPECIFIC,
			new LifecycleCapabilities(true, true, true, false, true)),

	/**
	 * Suspension and resumption of a run. Engine specific: the owning engine defines
	 * the resume protocol.
	 */
	SUSPEND_RESUME(LifecycleScope.ENGINE_SPECIFIC,
			new LifecycleCapabilities(false, true, false, false, true)),

	/**
	 * Delivery of the run output to the caller.
	 */
	OUTPUT_DELIVERY(LifecycleScope.COMMON_RUNTIME, new LifecycleCapabilities(true, true, false, false, false)),

	/**
	 * Completion, failure, cancellation and release of a run.
	 */
	TERMINATION(LifecycleScope.COMMON_RUNTIME, LifecycleCapabilities.synchronousOnly());

	private final LifecycleScope scope;

	private final LifecycleCapabilities capabilities;

	LifecycleStage(LifecycleScope scope, LifecycleCapabilities capabilities) {
		this.scope = scope;
		this.capabilities = capabilities;
	}

	/**
	 * The scope of this stage.
	 * @return the stage scope, never null
	 */
	public LifecycleScope scope() {
		return scope;
	}

	/**
	 * The execution modes this stage supports.
	 * @return the stage capabilities, never null
	 */
	public LifecycleCapabilities capabilities() {
		return capabilities;
	}

	/**
	 * Whether the stage is common to all engines.
	 * @return true for a common runtime stage
	 */
	public boolean isCommonRuntime() {
		return scope == LifecycleScope.COMMON_RUNTIME;
	}

	/**
	 * Whether the stage belongs to a specific engine family.
	 * @return true for an engine specific stage
	 */
	public boolean isEngineSpecific() {
		return scope == LifecycleScope.ENGINE_SPECIFIC;
	}

	/**
	 * The scope of a lifecycle stage.
	 */
	public enum LifecycleScope {

		/**
		 * A stage every engine is expected to expose.
		 */
		COMMON_RUNTIME,

		/**
		 * A stage only a particular engine family can offer.
		 */
		ENGINE_SPECIFIC
	}

}
