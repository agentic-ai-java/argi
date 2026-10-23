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

package io.github.agentic.ai.graph.execution.run;

import static java.util.Objects.requireNonNull;

/** Shared lifecycle for stored runs and tasks; terminal states are immutable. */
public enum RunState {

	PENDING, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT;

	public boolean terminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
	}

	/**
	 * Same-state writes allow journal/checkpoint progress only for nonterminal states.
	 */
	public void requireTransitionTo(RunState next) {
		requireNonNull(next, "next state cannot be null");
		boolean allowed = !terminal() && (this == next || switch (this) {
			case PENDING -> next == RUNNING || next == CANCELLED || next == TIMED_OUT;
			case RUNNING -> next != PENDING;
			case WAITING -> next == RUNNING || next == FAILED || next == CANCELLED || next == TIMED_OUT;
			default -> false;
		});
		if (!allowed) {
			throw new IllegalArgumentException("Illegal state transition: " + this + " -> " + next);
		}
	}

}
