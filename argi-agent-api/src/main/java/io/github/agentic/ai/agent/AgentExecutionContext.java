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
package io.github.agentic.ai.agent;

import io.github.agentic.ai.graph.checkpoint.lease.ExecutionGuard;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import java.time.Clock;
import java.util.Objects;

/**
 * Caller-owned authenticated invocation scope. The control is always constructed from
 * this exact identity and deadline; a different control cannot weaken them. No state,
 * checkpoint saver, graph configuration or ambient authentication is held. Executors own
 * budget accounting, timers and side-effect cancellation. Closing the scope cancels
 * remaining work and detaches the optional external ownership guard.
 */
public final class AgentExecutionContext implements AutoCloseable {

	private final TrustedExecutionContext trusted;

	private final ExecutionControl control;

	public AgentExecutionContext(TrustedExecutionContext trusted, Clock clock) {
		this(trusted, clock, null);
	}

	public AgentExecutionContext(TrustedExecutionContext trusted, Clock clock, ExecutionGuard ownership) {
		this.trusted = Objects.requireNonNull(trusted, "trusted must not be null");
		this.control = new ExecutionControl(trusted, clock, ownership);
	}

	public TrustedExecutionContext trusted() {
		return trusted;
	}

	public ExecutionControl control() {
		return control;
	}

	@Override
	public void close() {
		control.close();
	}

}
