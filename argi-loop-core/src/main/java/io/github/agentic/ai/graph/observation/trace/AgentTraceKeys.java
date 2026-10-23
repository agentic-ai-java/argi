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

/**
 * Attribute and MDC key names for cross-agent trace correlation.
 * <p>
 * Low-cardinality keys are safe to use as metric tags; identifiers (run, task, attempt,
 * delegation) are deliberately high-cardinality so they appear on spans and in logs but
 * never explode metric tag cardinality. All keys are new {@code argi.agent.*} or
 * {@code argi.*} names; historical metric and config keys are untouched.
 */
public final class AgentTraceKeys {

	/** Low-cardinality key carrying the {@link AgentTraceOperation} kind value. */
	public static final String OPERATION = "argi.agent.operation";

	/** Low-cardinality key carrying {@code producer} or {@code consumer}. */
	public static final String ROLE = "argi.agent.role";

	/** Low-cardinality key carrying the durable run store phase. */
	public static final String PHASE = "argi.agent.phase";

	/** Low-cardinality key carrying {@code true} or {@code false}. */
	public static final String SUCCESS = "argi.agent.success";

	/** Low-cardinality key carrying the terminal outcome, for example {@code timeout}. */
	public static final String OUTCOME = "argi.agent.outcome";

	/** Low-cardinality key carrying the sanitized exception type. */
	public static final String ERROR_TYPE = "argi.agent.error.type";

	/** High-cardinality key carrying the tenant identifier. */
	public static final String TENANT = "argi.agent.tenant";

	/** High-cardinality key carrying the durable run identifier. */
	public static final String RUN_ID = "argi.agent.run.id";

	/** High-cardinality key carrying the background task identifier. */
	public static final String TASK_ID = "argi.agent.task.id";

	/** High-cardinality key carrying the per-execution attempt identifier. */
	public static final String ATTEMPT_ID = "argi.agent.attempt.id";

	/** High-cardinality key carrying the delegation identifier of this span. */
	public static final String DELEGATION_ID = "argi.agent.delegation.id";

	/**
	 * High-cardinality key carrying the delegation identifier of a linked producer span.
	 * Producers and consumers run on different threads (or processes) and are rarely in a
	 * parent/child position, so waiting, fan-in and recovery spans reference their producer
	 * through this link key instead of nesting.
	 */
	public static final String LINK_DELEGATION_ID = "argi.agent.link.delegation.id";

	/** High-cardinality key carrying the thread (conversation) identifier. */
	public static final String THREAD_ID = "argi.agent.thread.id";

	/** High-cardinality key carrying the sanitized exception message. */
	public static final String ERROR = "argi.agent.error";

	/** SLF4J MDC key carrying the tenant identifier. */
	public static final String MDC_TENANT = "argi.tenant";

	/** SLF4J MDC key carrying the durable run identifier. */
	public static final String MDC_RUN_ID = "argi.run.id";

	/** SLF4J MDC key carrying the background task identifier. */
	public static final String MDC_TASK_ID = "argi.task.id";

	/** SLF4J MDC key carrying the per-execution attempt identifier. */
	public static final String MDC_ATTEMPT_ID = "argi.attempt.id";

	/** SLF4J MDC key carrying the delegation identifier. */
	public static final String MDC_DELEGATION_ID = "argi.delegation.id";

	private AgentTraceKeys() {
	}

}
