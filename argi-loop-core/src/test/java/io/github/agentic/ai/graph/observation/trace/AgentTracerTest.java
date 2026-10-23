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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("AgentTracer span tests")
class AgentTracerTest {

	private final RecordingHandler handler = new RecordingHandler();

	private final ObservationRegistry registry = ObservationRegistry.create();

	@AfterEach
	void clearMdc() {
		MDC.remove(AgentTraceKeys.MDC_RUN_ID);
		MDC.remove(AgentTraceKeys.MDC_TENANT);
		MDC.remove(AgentTraceKeys.MDC_DELEGATION_ID);
	}

	private AgentTracer enabledTracer() {
		this.registry.observationConfig().observationHandler(this.handler);
		return new AgentTracer(this.registry);
	}

	@Test
	@DisplayName("a missing or NOOP registry disables tracing and preserves behavior")
	void disabledTracerRecordsNothing() {
		for (AgentTracer tracer : List.of(new AgentTracer(null), new AgentTracer(ObservationRegistry.NOOP))) {
			assertThat(tracer.isEnabled()).isFalse();

			AgentTraceSpan span = tracer.start(AgentTraceOperation.AGENT_DELEGATION,
					AgentTraceContext.builder().runId("run-1").build());
			assertThat(span.isDisabled()).isTrue();
			assertThatCode(() -> {
				span.success();
				span.phase("submit");
				span.outcome("timeout");
				span.linkToDelegation("other");
				span.attempt("1");
				span.taskId("task-1");
				span.error(new RuntimeException("boom"));
				span.close();
			}).doesNotThrowAnyException();
			assertThat(MDC.get(AgentTraceKeys.MDC_RUN_ID)).isNull();
		}
		assertThat(this.handler.stopped).isEmpty();
	}

	@Test
	@DisplayName("spans carry the operation name, correlation ids and MDC values")
	void spanCarriesCorrelationIdsAndMdc() {
		AgentTracer tracer = enabledTracer();
		AgentTraceContext context = AgentTraceContext.builder()
			.tenant("tenant-a")
			.runId("run-1")
			.threadId("thread-1")
			.taskId("task-1")
			.attemptId("attempt-1")
			.delegationId("delegation-1")
			.build();

		try (AgentTraceSpan span = tracer.start(AgentTraceOperation.AGENT_DELEGATION, context)) {
			assertThat(span.isDisabled()).isFalse();
			assertThat(MDC.get(AgentTraceKeys.MDC_TENANT)).isEqualTo("tenant-a");
			assertThat(MDC.get(AgentTraceKeys.MDC_RUN_ID)).isEqualTo("run-1");
			assertThat(MDC.get(AgentTraceKeys.MDC_TASK_ID)).isEqualTo("task-1");
			assertThat(MDC.get(AgentTraceKeys.MDC_ATTEMPT_ID)).isEqualTo("attempt-1");
			assertThat(MDC.get(AgentTraceKeys.MDC_DELEGATION_ID)).isEqualTo("delegation-1");
			assertThat(this.registry.getCurrentObservation()).isNotNull();
			span.success();
		}

		assertThat(MDC.get(AgentTraceKeys.MDC_RUN_ID)).isNull();
		assertThat(this.handler.stopped).hasSize(1);
		Observation.Context stopped = this.handler.stopped.get(0);
		assertThat(stopped.getName()).isEqualTo("argi.agent.delegation");
		assertThat(stopped.getLowCardinalityKeyValue(AgentTraceKeys.OPERATION).getValue()).isEqualTo("agent_delegation");
		assertThat(stopped.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("true");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.TENANT).getValue()).isEqualTo("tenant-a");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.RUN_ID).getValue()).isEqualTo("run-1");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.TASK_ID).getValue()).isEqualTo("task-1");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.ATTEMPT_ID).getValue()).isEqualTo("attempt-1");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.DELEGATION_ID).getValue())
			.isEqualTo("delegation-1");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.THREAD_ID).getValue()).isEqualTo("thread-1");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.LINK_DELEGATION_ID)).isNull();
	}

	@Test
	@DisplayName("role, phase, outcome and link attributes are recorded")
	void spanRecordsRolePhaseOutcomeAndLink() {
		AgentTracer tracer = enabledTracer();
		AgentTraceContext context = AgentTraceContext.builder().runId("run-1").taskId("task-1").build();

		try (AgentTraceSpan span = tracer.start(AgentTraceOperation.TASK_RECEIVE, context).role("consumer")) {
			span.linkToDelegation("delegation-1").outcome("timeout");
		}

		Observation.Context stopped = this.handler.stopped.get(0);
		assertThat(stopped.getName()).isEqualTo("argi.agent.task.receive");
		assertThat(stopped.getLowCardinalityKeyValue(AgentTraceKeys.ROLE).getValue()).isEqualTo("consumer");
		assertThat(stopped.getLowCardinalityKeyValue(AgentTraceKeys.OUTCOME).getValue()).isEqualTo("timeout");
		assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.LINK_DELEGATION_ID).getValue())
			.isEqualTo("delegation-1");
	}

	@Test
	@DisplayName("failure content is redacted before it reaches the observation")
	void errorContentIsRedacted() {
		AgentTracer tracer = enabledTracer();
		AgentTraceContext context = AgentTraceContext.builder().runId("run-1").build();

		try (AgentTraceSpan span = tracer.start(AgentTraceOperation.AGENT_DELEGATION, context)) {
			span.error(new IllegalStateException("model call failed with password=hunter2 and Authorization: Bearer abc.def.ghi"));
		}

		Observation.Context stopped = this.handler.stopped.get(0);
		assertThat(stopped.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("false");
		assertThat(stopped.getLowCardinalityKeyValue(AgentTraceKeys.ERROR_TYPE).getValue())
			.isEqualTo(IllegalStateException.class.getName());
		String recorded = stopped.getHighCardinalityKeyValue(AgentTraceKeys.ERROR).getValue();
		assertThat(recorded).doesNotContain("hunter2");
		assertThat(recorded).doesNotContain("abc.def.ghi");
		assertThat(recorded).contains("<redacted>");
		assertThat(stopped.getError()).isNotNull();
		assertThat(stopped.getError().getMessage()).doesNotContain("hunter2").doesNotContain("abc.def.ghi");
	}

	@Test
	@DisplayName("previous MDC values are restored when the span closes")
	void mdcRestoresPreviousValues() {
		AgentTracer tracer = enabledTracer();
		MDC.put(AgentTraceKeys.MDC_RUN_ID, "outer-run");
		try {
			try (AgentTraceSpan span = tracer.start(AgentTraceOperation.AGENT_DELEGATION,
					AgentTraceContext.builder().runId("inner-run").build())) {
				assertThat(MDC.get(AgentTraceKeys.MDC_RUN_ID)).isEqualTo("inner-run");
			}
			assertThat(MDC.get(AgentTraceKeys.MDC_RUN_ID)).isEqualTo("outer-run");
		}
		finally {
			MDC.remove(AgentTraceKeys.MDC_RUN_ID);
		}
	}

	@Test
	@DisplayName("parallel chains keep their own tenant correlation")
	void parallelChainsDoNotMixTenants() throws Exception {
		AgentTracer tracer = enabledTracer();
		int threads = 4;
		int spansPerThread = 50;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (int t = 0; t < threads; t++) {
				String tenant = "tenant-" + t;
				futures.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < spansPerThread; i++) {
						String delegationId = tenant + "-delegation-" + i;
						try (AgentTraceSpan span = tracer.start(AgentTraceOperation.AGENT_DELEGATION,
								AgentTraceContext.builder().tenant(tenant).runId("run-" + i).delegationId(delegationId)
									.build())) {
							if (!tenant.equals(MDC.get(AgentTraceKeys.MDC_TENANT))) {
								throw new AssertionError("tenant leaked into MDC of " + tenant);
							}
							span.success();
						}
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<Void> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(this.handler.stopped).hasSize(threads * spansPerThread);
		Map<String, String> tenantByDelegation = new ConcurrentHashMap<>();
		for (Observation.Context stopped : this.handler.stopped) {
			String delegationId = stopped.getHighCardinalityKeyValue(AgentTraceKeys.DELEGATION_ID).getValue();
			String tenant = stopped.getHighCardinalityKeyValue(AgentTraceKeys.TENANT).getValue();
			String previous = tenantByDelegation.putIfAbsent(delegationId, tenant);
			assertThat(previous).as("delegation id %s seen twice", delegationId).isNull();
			assertThat(delegationId).as("delegation %s must belong to its own tenant", delegationId)
				.startsWith(tenant + "-delegation-");
		}
	}

	static final class RecordingHandler implements ObservationHandler<Observation.Context> {

		final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

		@Override
		public boolean supportsContext(Observation.Context context) {
			return true;
		}

		@Override
		public void onStop(Observation.Context context) {
			this.stopped.add(context);
		}

	}

}
