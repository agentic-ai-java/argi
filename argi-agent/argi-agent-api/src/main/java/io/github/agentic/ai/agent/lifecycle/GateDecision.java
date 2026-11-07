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
 * The outcome of an admission gate.
 *
 * <p>
 * Three outcomes exist, and they are not interchangeable:
 *
 * <ul>
 * <li>{@link Outcome#ALLOW} — the stage proceeds.</li>
 * <li>{@link Outcome#DENY} — the stage is short-circuited with a reason.</li>
 * <li>{@link Outcome#REQUIRE_APPROVAL} — the stage needs an approval decision. An
 * engine without an approval channel must degrade this to {@link Outcome#DENY} and say
 * so, never to {@link Outcome#ALLOW}.</li>
 * </ul>
 *
 * @param outcome the gate outcome, never null
 * @param reason the reason of a denial or an approval request, nullable
 */
public record GateDecision(Outcome outcome, String reason) {

	/**
	 * Creates a gate decision.
	 * @param outcome the gate outcome, must not be null
	 * @param reason the reason of a denial or an approval request, nullable
	 */
	public GateDecision {
		Objects.requireNonNull(outcome, "outcome must not be null");
		if (outcome != Outcome.ALLOW && (reason == null || reason.isBlank())) {
			throw new IllegalArgumentException("a reason is required for " + outcome);
		}
	}

	/**
	 * Allows the stage.
	 * @return an allowing decision
	 */
	public static GateDecision allow() {
		return new GateDecision(Outcome.ALLOW, null);
	}

	/**
	 * Denies the stage with a reason.
	 * @param reason the denial reason, must not be blank
	 * @return a denying decision
	 */
	public static GateDecision deny(String reason) {
		return new GateDecision(Outcome.DENY, reason);
	}

	/**
	 * Requests an approval decision for the stage.
	 * @param reason the approval reason, must not be blank
	 * @return an approval request
	 */
	public static GateDecision requireApproval(String reason) {
		return new GateDecision(Outcome.REQUIRE_APPROVAL, reason);
	}

	/**
	 * Whether the stage may proceed.
	 * @return true for an allowing decision
	 */
	public boolean isAllowed() {
		return outcome == Outcome.ALLOW;
	}

	/**
	 * Whether the stage is short-circuited.
	 * @return true for a denying decision
	 */
	public boolean isDenied() {
		return outcome == Outcome.DENY;
	}

	/**
	 * Whether the stage waits for an approval decision.
	 * @return true for an approval request
	 */
	public boolean requiresApproval() {
		return outcome == Outcome.REQUIRE_APPROVAL;
	}

	/**
	 * The outcome of an admission gate.
	 */
	public enum Outcome {

		/**
		 * The stage proceeds.
		 */
		ALLOW,

		/**
		 * The stage is short-circuited.
		 */
		DENY,

		/**
		 * The stage needs an approval decision.
		 */
		REQUIRE_APPROVAL
	}

}
