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

import java.util.HashMap;
import java.util.Map;

import io.github.agentic.ai.graph.observation.ObservationContentSanitizer;
import io.micrometer.observation.Observation;
import org.slf4j.MDC;

/**
 * A started cross-agent trace span.
 * <p>
 * A span opens an observation scope and mirrors its correlation identifiers into the SLF4J
 * MDC, so log statements issued inside the span carry run, task, attempt, delegation and
 * tenant identifiers. Previous MDC values are restored on {@link #close()}. Exception
 * content is redacted and truncated before it reaches the observation; the original
 * exception object never leaves this class.
 * <p>
 * The {@link #disabled() disabled} instance is returned whenever tracing is not enabled;
 * every method is a no-op and no MDC state is touched, preserving the historical behavior
 * exactly.
 */
public final class AgentTraceSpan implements AutoCloseable {

	private static final AgentTraceSpan DISABLED = new AgentTraceSpan(null, null);

	private final Observation observation;

	private final AgentTraceContext context;

	private Observation.Scope scope;

	private Map<String, String> previousMdc;

	AgentTraceSpan(Observation observation, AgentTraceContext context) {
		this.observation = observation;
		this.context = context;
	}

	/**
	 * A disabled span that records nothing and preserves historical behavior.
	 * @return the shared disabled span instance
	 */
	public static AgentTraceSpan disabled() {
		return DISABLED;
	}

	/** @return true when this span records nothing and preserves historical behavior */
	public boolean isDisabled() {
		return this.observation == null;
	}

	/** @return the correlation facts captured for this span */
	public AgentTraceContext context() {
		return this.context;
	}

	/**
	 * Mark the operation as successful.
	 * @return this span
	 */
	public AgentTraceSpan success() {
		if (this.observation != null) {
			this.observation.lowCardinalityKeyValue(AgentTraceKeys.SUCCESS, "true");
		}
		return this;
	}

	/**
	 * Set a low-cardinality role value, for example {@code producer} or {@code consumer}.
	 * @param role the role value
	 * @return this span
	 */
	public AgentTraceSpan role(String role) {
		if (this.observation != null) {
			this.observation.lowCardinalityKeyValue(AgentTraceKeys.ROLE, role);
		}
		return this;
	}

	/**
	 * Set a low-cardinality phase value, for example {@code submit} or {@code commit}.
	 * @param phase the phase value
	 * @return this span
	 */
	public AgentTraceSpan phase(String phase) {
		if (this.observation != null) {
			this.observation.lowCardinalityKeyValue(AgentTraceKeys.PHASE, phase);
		}
		return this;
	}

	/**
	 * Set a low-cardinality terminal outcome, for example {@code completed}, {@code timeout},
	 * {@code interrupted} or {@code failed}.
	 * @param outcome the outcome value
	 * @return this span
	 */
	public AgentTraceSpan outcome(String outcome) {
		if (this.observation != null) {
			this.observation.lowCardinalityKeyValue(AgentTraceKeys.OUTCOME, outcome);
		}
		return this;
	}

	/**
	 * Reference a producer span by its delegation identifier. Waiting, fan-in and recovery
	 * spans use this link key instead of a parent/child relationship because the producer
	 * span has usually already ended on another thread.
	 * @param delegationId the delegation identifier of the linked producer span
	 * @return this span
	 */
	public AgentTraceSpan linkToDelegation(String delegationId) {
		if (this.observation != null && delegationId != null) {
			this.observation.highCardinalityKeyValue(AgentTraceKeys.LINK_DELEGATION_ID, delegationId);
		}
		return this;
	}

	/**
	 * Attach an attempt identifier discovered while the operation ran, for example the lease
	 * fencing token of a run store acquisition.
	 * @param attemptId the attempt identifier
	 * @return this span
	 */
	public AgentTraceSpan attempt(String attemptId) {
		if (this.observation != null && attemptId != null) {
			this.observation.highCardinalityKeyValue(AgentTraceKeys.ATTEMPT_ID, attemptId);
		}
		return this;
	}

	/**
	 * Attach one or more task identifiers discovered while the operation ran. Multiple
	 * identifiers are joined with a comma.
	 * @param taskId the task identifier or comma-joined identifier list
	 * @return this span
	 */
	public AgentTraceSpan taskId(String taskId) {
		if (this.observation != null && taskId != null) {
			this.observation.highCardinalityKeyValue(AgentTraceKeys.TASK_ID, taskId);
		}
		return this;
	}

	/**
	 * Record a failure. The exception type is kept as a low-cardinality attribute, its
	 * message is redacted and truncated before it is attached, and only a sanitized wrapper
	 * exception is handed to the observation so no sensitive content can leak into exporters.
	 * @param error the failure
	 * @return this span
	 */
	public AgentTraceSpan error(Throwable error) {
		if (this.observation == null || error == null) {
			return this;
		}
		this.observation.lowCardinalityKeyValue(AgentTraceKeys.SUCCESS, "false");
		this.observation.lowCardinalityKeyValue(AgentTraceKeys.ERROR_TYPE, error.getClass().getName());
		String message = error.getMessage() != null ? error.getMessage() : error.toString();
		String redacted = ObservationContentSanitizer.sanitizeText(message,
				ObservationContentSanitizer.DEFAULT_MAX_CONTENT_LENGTH);
		this.observation.highCardinalityKeyValue(AgentTraceKeys.ERROR, redacted);
		Throwable sanitized = new RuntimeException(error.getClass().getSimpleName() + ": " + redacted);
		sanitized.setStackTrace(error.getStackTrace());
		this.observation.error(sanitized);
		return this;
	}

	/**
	 * Close the scope, restore the previous MDC values and stop the observation. Must be
	 * called on the thread that started the span.
	 */
	@Override
	public void close() {
		if (this.observation == null) {
			return;
		}
		try {
			if (this.scope != null) {
				this.scope.close();
			}
		}
		finally {
			restoreMdc();
			this.observation.stop();
		}
	}

	void applyContext() {
		if (this.observation == null || this.context == null) {
			return;
		}
		putHighCardinality(AgentTraceKeys.TENANT, this.context.tenant());
		putHighCardinality(AgentTraceKeys.RUN_ID, this.context.runId());
		putHighCardinality(AgentTraceKeys.TASK_ID, this.context.taskId());
		putHighCardinality(AgentTraceKeys.ATTEMPT_ID, this.context.attemptId());
		putHighCardinality(AgentTraceKeys.DELEGATION_ID, this.context.delegationId());
		putHighCardinality(AgentTraceKeys.THREAD_ID, this.context.threadId());
	}

	void start() {
		if (this.observation == null) {
			return;
		}
		this.scope = this.observation.openScope();
		this.previousMdc = pushMdc(this.context);
	}

	private void putHighCardinality(String key, String value) {
		if (value != null) {
			this.observation.highCardinalityKeyValue(key, value);
		}
	}

	private static Map<String, String> pushMdc(AgentTraceContext context) {
		if (context == null) {
			return Map.of();
		}
		Map<String, String> previous = new HashMap<>();
		pushMdcValue(previous, AgentTraceKeys.MDC_TENANT, context.tenant());
		pushMdcValue(previous, AgentTraceKeys.MDC_RUN_ID, context.runId());
		pushMdcValue(previous, AgentTraceKeys.MDC_TASK_ID, context.taskId());
		pushMdcValue(previous, AgentTraceKeys.MDC_ATTEMPT_ID, context.attemptId());
		pushMdcValue(previous, AgentTraceKeys.MDC_DELEGATION_ID, context.delegationId());
		return previous;
	}

	private static void pushMdcValue(Map<String, String> previous, String key, String value) {
		if (value == null) {
			return;
		}
		previous.put(key, MDC.get(key));
		MDC.put(key, value);
	}

	private void restoreMdc() {
		if (this.previousMdc == null) {
			return;
		}
		for (Map.Entry<String, String> entry : this.previousMdc.entrySet()) {
			if (entry.getValue() == null) {
				MDC.remove(entry.getKey());
			}
			else {
				MDC.put(entry.getKey(), entry.getValue());
			}
		}
		this.previousMdc = null;
	}

}
