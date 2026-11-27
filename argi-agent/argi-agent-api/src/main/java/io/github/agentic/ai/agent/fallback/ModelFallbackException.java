/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.agent.fallback;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** A stopped candidate sequence, with the number of dispatches and client switches. */
public final class ModelFallbackException extends RuntimeException {

	public enum Reason {
		CANDIDATES_EXHAUSTED, SWITCH_LIMIT, ATTEMPT_LIMIT, NON_SWITCHABLE
	}

	private final Reason reason;

	private final int attempts;

	private final int switches;

	public ModelFallbackException(Reason reason, int attempts, int switches, Throwable cause) {
		super((reason == Reason.CANDIDATES_EXHAUSTED ? "All clients failed" : "Model fallback stopped: " + reason)
				+ " after " + attempts + " attempts (" + IntStream.rangeClosed(0, switches)
			.mapToObj(i -> i == 0 ? "primary" : "fallback-" + i).collect(Collectors.joining(", ")) + ")", cause);
		this.reason = reason;
		this.attempts = attempts;
		this.switches = switches;
	}

	public Reason reason() {
		return reason;
	}

	public int attempts() {
		return attempts;
	}

	public int switches() {
		return switches;
	}

}
