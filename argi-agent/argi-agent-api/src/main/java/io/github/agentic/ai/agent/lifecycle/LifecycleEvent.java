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
 * An immutable notification about one lifecycle stage.
 *
 * <p>
 * The event set is closed on purpose: a plugin never receives a free-form payload map
 * it has to interpret, and it never receives a mutable object it could corrupt for
 * everybody else. Every event carries the stage it belongs to and the identity of the
 * work it describes.
 *
 * <p>
 * Events are observations, not commands. Mutating an event is not possible; the only
 * way to influence a stage is the transformer, the around interceptor or the admission
 * gate.
 */
public sealed interface LifecycleEvent permits LifecycleEvent.StageEntered, LifecycleEvent.StageExited,
		LifecycleEvent.ModelCallRequested, LifecycleEvent.ModelCallCompleted, LifecycleEvent.ToolCallRequested,
		LifecycleEvent.ToolCallCompleted, LifecycleEvent.ToolCallRejected, LifecycleEvent.ExtensionFailed {

	/**
	 * The stage this event belongs to.
	 * @return the stage, never null
	 */
	LifecycleStage stage();

	/**
	 * The identity of the work this event describes.
	 * @return the identity, never null
	 */
	LifecycleIdentity identity();

	/**
	 * A stage was entered.
	 *
	 * @param stage the stage, never null
	 * @param identity the identity of the work, never null
	 */
	record StageEntered(LifecycleStage stage, LifecycleIdentity identity) implements LifecycleEvent {

		/**
		 * Creates a stage-entered event.
		 * @param stage the stage, must not be null
		 * @param identity the identity of the work, must not be null
		 */
		public StageEntered {
			java.util.Objects.requireNonNull(stage, "stage must not be null");
			java.util.Objects.requireNonNull(identity, "identity must not be null");
		}
	}

	/**
	 * A stage was left.
	 *
	 * @param stage the stage, never null
	 * @param identity the identity of the work, never null
	 * @param successful whether the stage completed successfully
	 * @param failureReason the failure description, nullable
	 */
	record StageExited(LifecycleStage stage, LifecycleIdentity identity, boolean successful, String failureReason)
			implements LifecycleEvent {

		/**
		 * Creates a stage-exited event.
		 * @param stage the stage, must not be null
		 * @param identity the identity of the work, must not be null
		 * @param successful whether the stage completed successfully
		 * @param failureReason the failure description, nullable
		 */
		public StageExited {
			java.util.Objects.requireNonNull(stage, "stage must not be null");
			java.util.Objects.requireNonNull(identity, "identity must not be null");
		}
	}

	/**
	 * A model call was requested.
	 *
	 * @param invocation the model call, never null
	 */
	record ModelCallRequested(ModelCallInvocation invocation) implements LifecycleEvent {

		/**
		 * Creates a model-call-requested event.
		 * @param invocation the model call, must not be null
		 */
		public ModelCallRequested {
			java.util.Objects.requireNonNull(invocation, "invocation must not be null");
		}

		@Override
		public LifecycleStage stage() {
			return LifecycleStage.MODEL_CALL;
		}

		@Override
		public LifecycleIdentity identity() {
			return invocation.identity();
		}
	}

	/**
	 * A model call was completed.
	 *
	 * @param invocation the model call, never null
	 * @param outcome the outcome of the call, never null
	 */
	record ModelCallCompleted(ModelCallInvocation invocation, ModelCallOutcome outcome) implements LifecycleEvent {

		/**
		 * Creates a model-call-completed event.
		 * @param invocation the model call, must not be null
		 * @param outcome the outcome of the call, must not be null
		 */
		public ModelCallCompleted {
			java.util.Objects.requireNonNull(invocation, "invocation must not be null");
			java.util.Objects.requireNonNull(outcome, "outcome must not be null");
		}

		@Override
		public LifecycleStage stage() {
			return LifecycleStage.MODEL_CALL;
		}

		@Override
		public LifecycleIdentity identity() {
			return invocation.identity();
		}
	}

	/**
	 * A tool call was requested.
	 *
	 * @param invocation the tool call, never null
	 */
	record ToolCallRequested(ToolCallInvocation invocation) implements LifecycleEvent {

		/**
		 * Creates a tool-call-requested event.
		 * @param invocation the tool call, must not be null
		 */
		public ToolCallRequested {
			java.util.Objects.requireNonNull(invocation, "invocation must not be null");
		}

		@Override
		public LifecycleStage stage() {
			return LifecycleStage.TOOL_CALL;
		}

		@Override
		public LifecycleIdentity identity() {
			return invocation.identity();
		}
	}

	/**
	 * A tool call was completed.
	 *
	 * @param invocation the tool call, never null
	 * @param outcome the outcome of the call, never null
	 */
	record ToolCallCompleted(ToolCallInvocation invocation, ToolCallOutcome outcome) implements LifecycleEvent {

		/**
		 * Creates a tool-call-completed event.
		 * @param invocation the tool call, must not be null
		 * @param outcome the outcome of the call, must not be null
		 */
		public ToolCallCompleted {
			java.util.Objects.requireNonNull(invocation, "invocation must not be null");
			java.util.Objects.requireNonNull(outcome, "outcome must not be null");
		}

		@Override
		public LifecycleStage stage() {
			return LifecycleStage.TOOL_CALL;
		}

		@Override
		public LifecycleIdentity identity() {
			return invocation.identity();
		}
	}

	/**
	 * A tool call was rejected before execution.
	 *
	 * @param invocation the rejected tool call, never null
	 * @param reason the rejection reason, never null
	 */
	record ToolCallRejected(ToolCallInvocation invocation, String reason) implements LifecycleEvent {

		/**
		 * Creates a tool-call-rejected event.
		 * @param invocation the rejected tool call, must not be null
		 * @param reason the rejection reason, must not be null
		 */
		public ToolCallRejected {
			java.util.Objects.requireNonNull(invocation, "invocation must not be null");
			java.util.Objects.requireNonNull(reason, "reason must not be null");
		}

		@Override
		public LifecycleStage stage() {
			return LifecycleStage.TOOL_CALL;
		}

		@Override
		public LifecycleIdentity identity() {
			return invocation.identity();
		}
	}

	/**
	 * An extension failed while it was invoked.
	 *
	 * <p>
	 * The event is the explicit failure semantics of the contract: an observer failure
	 * is reported instead of being swallowed, and an around interceptor failure is
	 * surfaced as an event rather than as a silently degraded stage.
	 *
	 * @param stage the stage the extension was bound to, never null
	 * @param identity the identity of the work, never null
	 * @param extensionId the identifier of the failing extension, never null
	 * @param message the failure description, never null
	 */
	record ExtensionFailed(LifecycleStage stage, LifecycleIdentity identity, String extensionId, String message)
			implements LifecycleEvent {

		/**
		 * Creates an extension-failed event.
		 * @param stage the stage the extension was bound to, must not be null
		 * @param identity the identity of the work, must not be null
		 * @param extensionId the identifier of the failing extension, must not be null
		 * @param message the failure description, must not be null
		 */
		public ExtensionFailed {
			java.util.Objects.requireNonNull(stage, "stage must not be null");
			java.util.Objects.requireNonNull(identity, "identity must not be null");
			java.util.Objects.requireNonNull(extensionId, "extensionId must not be null");
			java.util.Objects.requireNonNull(message, "message must not be null");
		}
	}

}
