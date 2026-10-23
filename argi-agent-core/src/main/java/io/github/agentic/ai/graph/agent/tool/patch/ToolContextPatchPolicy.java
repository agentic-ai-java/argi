/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.tool.patch;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in policy that gates tool context patches at the merge boundary of
 * {@code AgentToolNode}.
 *
 * <p>
 * When enabled, a tool's patch reaches the next turn's state only if the tool call
 * succeeded (unless {@link #discardPatchOnFailedResult()} is turned off) and the patch
 * passes {@link ToolContextPatchValidator}: reserved keys stay unwritable, tools stick
 * to their declared {@link ToolContextPatchSpec}, and values match declared types.
 * Without a policy the historical merge behavior — including merging the patches of
 * calls that returned an error result — is preserved unchanged.
 * </p>
 *
 * <p>
 * The policy applies identically to sequential and parallel tool execution: rejection
 * discards the whole call's patch, so a partial write never lands half-merged. Failed
 * calls are discarded before validation, so an aborted tool's stray writes are dropped
 * without escalating to {@link ViolationPolicy#ABORT_RUN}.
 * </p>
 */
public final class ToolContextPatchPolicy {

	private final Map<String, ToolContextPatchSpec> specs;

	private final boolean discardPatchOnFailedResult;

	private final ViolationPolicy violationPolicy;

	private ToolContextPatchPolicy(Builder builder) {
		this.specs = Collections.unmodifiableMap(new HashMap<>(builder.specs));
		this.discardPatchOnFailedResult = builder.discardPatchOnFailedResult;
		this.violationPolicy = builder.violationPolicy;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The spec declared for a tool.
	 * @param toolName the tool whose spec is requested
	 * @return the declared spec, or {@code null} when the tool declared none
	 */
	public ToolContextPatchSpec specFor(String toolName) {
		return specs.get(toolName);
	}

	/**
	 * Whether patches of failed calls (error results, guard rejections, cancellations,
	 * timeouts) are discarded before merging.
	 * @return {@code true} when failed calls must not contribute state
	 */
	public boolean discardPatchOnFailedResult() {
		return discardPatchOnFailedResult;
	}

	/**
	 * What happens to a patch that violates validation.
	 * @return the configured violation policy
	 */
	public ViolationPolicy violationPolicy() {
		return violationPolicy;
	}

	/**
	 * Behavior when a patch fails validation.
	 */
	public enum ViolationPolicy {

		/**
		 * Discard the whole violating patch and continue the run; the rejection is logged.
		 */
		DISCARD_PATCH,

		/**
		 * Abort the run with {@link ToolContextPatchViolationException} before the patch
		 * reaches the state.
		 */
		ABORT_RUN

	}

	public static final class Builder {

		private final Map<String, ToolContextPatchSpec> specs = new HashMap<>();

		private boolean discardPatchOnFailedResult = true;

		private ViolationPolicy violationPolicy = ViolationPolicy.DISCARD_PATCH;

		private Builder() {
		}

		/**
		 * Declares the patch spec for one tool.
		 * @param toolName the tool the spec applies to
		 * @param spec the declared writable keys and types
		 * @return this builder
		 */
		public Builder specFor(String toolName, ToolContextPatchSpec spec) {
			if (toolName == null || toolName.isBlank()) {
				throw new IllegalArgumentException("toolName must not be blank");
			}
			this.specs.put(toolName, Objects.requireNonNull(spec, "spec must not be null"));
			return this;
		}

		/**
		 * Whether patches of failed calls are discarded before merging. Default
		 * {@code true}.
		 * @param discard {@code true} to drop the patches of failed calls
		 * @return this builder
		 */
		public Builder discardPatchOnFailedResult(boolean discard) {
			this.discardPatchOnFailedResult = discard;
			return this;
		}

		/**
		 * What happens when a patch fails validation. Default
		 * {@link ViolationPolicy#DISCARD_PATCH}.
		 * @param violationPolicy the violation policy to apply
		 * @return this builder
		 */
		public Builder onViolation(ViolationPolicy violationPolicy) {
			this.violationPolicy = Objects.requireNonNull(violationPolicy, "violationPolicy must not be null");
			return this;
		}

		public ToolContextPatchPolicy build() {
			return new ToolContextPatchPolicy(this);
		}

	}

}
