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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Entry point for cross-agent trace correlation. An {@code AgentTracer} is an explicit
 * opt-in: callers construct one around a real {@link ObservationRegistry} and hand it to
 * {@code TaskToolsBuilder}, {@code A2aRemoteAgent.Builder} or the run store decorator.
 * Without it, or with {@link ObservationRegistry#NOOP}, every span is disabled and the
 * historical behavior is preserved unchanged.
 * <p>
 * When enabled, spans created on a thread that already runs an observation nest under it
 * automatically; spans created on worker threads carry their correlation facts as
 * attributes and link to their producer through delegation identifiers, because the
 * producer span has usually already ended.
 */
public final class AgentTracer {

	private final ObservationRegistry registry;

	/**
	 * Create a tracer. A null registry behaves like {@link ObservationRegistry#NOOP}.
	 * @param registry the registry to emit observations to, may be null
	 */
	public AgentTracer(ObservationRegistry registry) {
		this.registry = registry;
	}

	/** @return true when spans will actually be recorded */
	public boolean isEnabled() {
		return this.registry != null && !this.registry.isNoop();
	}

	/** @return the wrapped registry, may be null */
	public ObservationRegistry registry() {
		return this.registry;
	}

	/**
	 * Start a span for the given operation. The returned span must be closed on the same
	 * thread; use it in a try-with-resources block.
	 * @param operation the operation to record
	 * @param context the correlation facts, may be null
	 * @return a started span, or a disabled span when tracing is not enabled
	 */
	public AgentTraceSpan start(AgentTraceOperation operation, AgentTraceContext context) {
		if (!isEnabled()) {
			return AgentTraceSpan.disabled();
		}
		Observation observation = Observation.createNotStarted(operation.operationName(), this.registry);
		observation.lowCardinalityKeyValue(AgentTraceKeys.OPERATION, operation.kindValue());
		AgentTraceSpan span = new AgentTraceSpan(observation, context);
		span.applyContext();
		observation.start();
		span.start();
		return span;
	}

}
