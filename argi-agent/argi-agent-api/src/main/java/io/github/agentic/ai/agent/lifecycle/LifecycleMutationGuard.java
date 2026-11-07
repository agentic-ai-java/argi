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

import java.util.Optional;

/**
 * Decides whether a restricted transformation stays inside its boundary.
 *
 * <p>
 * The guard is the executable form of the "restricted transformer" rule. It compares
 * the original event with the rewritten one and reports the first violation:
 *
 * <ul>
 * <li>the event type must not change;</li>
 * <li>the identity must not change;</li>
 * <li>a model call may replace its request, but not its model turn identity;</li>
 * <li>a tool call may replace its arguments, but not its tool name or its tool call
 * identity;</li>
 * <li>every other event type is immutable.</li>
 * </ul>
 *
 * <p>
 * Renaming a tool is deliberately not a transformation: it would silently redirect the
 * call to a different binding, which is an admission decision.
 */
public final class LifecycleMutationGuard {

	private LifecycleMutationGuard() {
	}

	/**
	 * Checks a rewritten event against the original one.
	 * @param original the original event, must not be null
	 * @param rewritten the rewritten event, must not be null
	 * @return the violation description, or empty when the rewrite is allowed
	 */
	public static Optional<String> check(LifecycleEvent original, LifecycleEvent rewritten) {
		if (original == null || rewritten == null) {
			return Optional.of("event must not be null");
		}
		if (original.getClass() != rewritten.getClass()) {
			return Optional.of("event type must not change");
		}
		if (!original.identity().equals(rewritten.identity())) {
			return Optional.of("identity must not change");
		}
		if (original instanceof LifecycleEvent.ModelCallRequested before) {
			LifecycleEvent.ModelCallRequested after = (LifecycleEvent.ModelCallRequested) rewritten;
			if (!before.invocation().modelTurn().equals(after.invocation().modelTurn())) {
				return Optional.of("model turn identity must not change");
			}
			// The trusted execution context is run identity: a replacement that swaps
			// the tenant, principal or budget (or nulls it out) must be rejected even
			// when the lifecycle IDs are preserved.
			if (!java.util.Objects.equals(before.invocation().request().getTrustedExecutionContext(),
					after.invocation().request().getTrustedExecutionContext())) {
				return Optional.of("trusted execution context must not change");
			}
			return Optional.empty();
		}
		if (original instanceof LifecycleEvent.ToolCallRequested before) {
			LifecycleEvent.ToolCallRequested after = (LifecycleEvent.ToolCallRequested) rewritten;
			if (!before.invocation().toolCall().equals(after.invocation().toolCall())) {
				return Optional.of("tool call identity must not change");
			}
			if (!before.invocation().toolName().equals(after.invocation().toolName())) {
				return Optional.of("tool name must not change");
			}
			return Optional.empty();
		}
		if (!original.equals(rewritten)) {
			return Optional.of(original.getClass().getSimpleName() + " does not allow transformation");
		}
		return Optional.empty();
	}

}
