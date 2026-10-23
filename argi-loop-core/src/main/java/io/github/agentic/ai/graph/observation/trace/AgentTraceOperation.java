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

package io.github.agentic.ai.graph.observation.trace;

import java.util.Locale;

/**
 * Cross-agent trace operations emitted when an {@link AgentTracer} is explicitly
 * configured. Every operation name is a new {@code argi.agent.*} or {@code argi.run.*}
 * metric name; the historical {@code argi.graph.*} names are never reused.
 */
public enum AgentTraceOperation {

	/** A parent agent delegated work to a sub-agent (TaskTool execution). */
	AGENT_DELEGATION("argi.agent.delegation"),

	/** A producer span for a remote A2A JSON-RPC request. */
	A2A_SEND("argi.agent.a2a.send"),

	/** A consumer span for a remote A2A response. */
	A2A_RECEIVE("argi.agent.a2a.receive"),

	/** A consumer span that receives or waits for a background task result. */
	TASK_RECEIVE("argi.agent.task.receive"),

	/** A durable run store operation (submit, acquire, commit). */
	RUN_STORE("argi.run.store");

	private final String operationName;

	AgentTraceOperation(String operationName) {
		this.operationName = operationName;
	}

	/**
	 * The observation/metric name for this operation.
	 * @return the operation name, for example {@code argi.agent.delegation}
	 */
	public String operationName() {
		return this.operationName;
	}

	/**
	 * The low-cardinality {@code argi.agent.operation} value for this operation.
	 * @return the snake_case kind value, for example {@code agent_delegation}
	 */
	public String kindValue() {
		return name().toLowerCase(Locale.ROOT);
	}

}
