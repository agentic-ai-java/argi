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
package io.github.agentic.ai.runtime.execution;

import java.util.concurrent.CancellationException;

/** A structured local stop signal, not proof of a fenced storage commit. */
public final class ExecutionStoppedException extends CancellationException {

	public enum Reason {

		CANCELLED, DEADLINE_EXCEEDED, OWNERSHIP_LOST

	}

	private final Reason reason;

	ExecutionStoppedException(Reason reason) {
		super("Execution stopped: " + reason);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

}
