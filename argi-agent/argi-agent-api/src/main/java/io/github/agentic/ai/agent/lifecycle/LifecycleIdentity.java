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

import java.util.Objects;

/**
 * The identity of the unit of work an extension is invoked for.
 *
 * <p>
 * The invocation identity is always present. The remaining identities are present only
 * when the bound stage actually produces them, which keeps the vocabulary explicit
 * instead of collapsing everything into one opaque identifier:
 *
 * <ul>
 * <li>an attempt exists while a unit of work is being retried;</li>
 * <li>a step exists while a unit of work is executed;</li>
 * <li>a model turn exists for one model interaction;</li>
 * <li>a tool call exists for one tool interaction;</li>
 * <li>a delegation exists for one hand-over to another agent.</li>
 * </ul>
 *
 * <p>
 * The identity is read-only for extensions: no plugin may rewrite it, and no plugin may
 * derive a stronger identity than the one it was given.
 *
 * @param invocation the invocation identity, never null
 * @param attempt the attempt identity, nullable
 * @param step the step identity, nullable
 * @param modelTurn the model turn identity, nullable
 * @param toolCall the tool call identity, nullable
 * @param delegation the delegation identity, nullable
 */
public record LifecycleIdentity(InvocationId invocation, AttemptId attempt, StepId step, ModelTurnId modelTurn,
		ToolCallId toolCall, DelegationId delegation) {

	/**
	 * Creates an identity.
	 * @param invocation the invocation identity, must not be null
	 * @param attempt the attempt identity, nullable
	 * @param step the step identity, nullable
	 * @param modelTurn the model turn identity, nullable
	 * @param toolCall the tool call identity, nullable
	 * @param delegation the delegation identity, nullable
	 */
	public LifecycleIdentity {
		Objects.requireNonNull(invocation, "invocation must not be null");
	}

	/**
	 * Starts a builder for an identity rooted at the given invocation.
	 * @param invocation the invocation identity, must not be null
	 * @return a new builder
	 */
	public static Builder builder(InvocationId invocation) {
		return new Builder(invocation);
	}

	/**
	 * Builder for {@link LifecycleIdentity}.
	 */
	public static final class Builder {

		private final InvocationId invocation;

		private AttemptId attempt;

		private StepId step;

		private ModelTurnId modelTurn;

		private ToolCallId toolCall;

		private DelegationId delegation;

		private Builder(InvocationId invocation) {
			this.invocation = Objects.requireNonNull(invocation, "invocation must not be null");
		}

		/**
		 * Sets the attempt identity.
		 * @param attempt the attempt identity, nullable
		 * @return this builder
		 */
		public Builder attempt(AttemptId attempt) {
			this.attempt = attempt;
			return this;
		}

		/**
		 * Sets the step identity.
		 * @param step the step identity, nullable
		 * @return this builder
		 */
		public Builder step(StepId step) {
			this.step = step;
			return this;
		}

		/**
		 * Sets the model turn identity.
		 * @param modelTurn the model turn identity, nullable
		 * @return this builder
		 */
		public Builder modelTurn(ModelTurnId modelTurn) {
			this.modelTurn = modelTurn;
			return this;
		}

		/**
		 * Sets the tool call identity.
		 * @param toolCall the tool call identity, nullable
		 * @return this builder
		 */
		public Builder toolCall(ToolCallId toolCall) {
			this.toolCall = toolCall;
			return this;
		}

		/**
		 * Sets the delegation identity.
		 * @param delegation the delegation identity, nullable
		 * @return this builder
		 */
		public Builder delegation(DelegationId delegation) {
			this.delegation = delegation;
			return this;
		}

		/**
		 * Builds the identity.
		 * @return the immutable identity
		 */
		public LifecycleIdentity build() {
			return new LifecycleIdentity(invocation, attempt, step, modelTurn, toolCall, delegation);
		}

	}

}
