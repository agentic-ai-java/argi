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
package io.github.agentic.ai.agent.orchestration.workflow;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.agent.lifecycle.LifecycleObserver;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.workflow.WorkflowAgent;
import io.github.agentic.ai.workflow.WorkflowDefinition;
import io.github.agentic.ai.workflow.WorkflowStep;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceKeys;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTracer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #322: the workflow step lifecycle events must surface as real micrometer spans. */
class WorkflowStepObservationObserverTest {

	private final RecordingHandler handler = new RecordingHandler();

	private final AgentTracer tracer;

	WorkflowStepObservationObserverTest() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(this.handler);
		this.tracer = new AgentTracer(registry);
	}

	@Test
	void workflowStepEventsSurfaceAsWorkflowStepSpans() throws Exception {
		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", "v"), new Usage(1L, 1L, 2L)));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", agent))
			.observers(observer())
			.build();

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
			assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.COMPLETED);
		}

		assertThat(this.handler.stopped).hasSize(1);
		Observation.Context span = this.handler.stopped.get(0);
		assertThat(span.getName()).isEqualTo("argi.agent.workflow.step");
		assertThat(span.getHighCardinalityKeyValue(AgentTraceKeys.STEP_ID).getValue()).isEqualTo("answer");
		assertThat(span.getHighCardinalityKeyValue(AgentTraceKeys.RUN_ID).getValue()).isEqualTo("run");
		assertThat(span.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("true");
	}

	@Test
	void aFailingWorkflowStepEndsItsSpanUnsuccessfully() throws Exception {
		Agent failing = agent(request -> {
			throw new IllegalStateException("boom");
		});
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", failing))
			.observers(observer())
			.build();

		try (AgentExecutionContext context = context()) {
			workflow.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
		}
		catch (Exception pending) {
			// The workflow surfaces the error to its subscriber; the span is the point.
		}

		assertThat(this.handler.stopped).hasSize(1);
		Observation.Context span = this.handler.stopped.get(0);
		assertThat(span.getName()).isEqualTo("argi.agent.workflow.step");
		assertThat(span.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("false");
		assertThat(span.getLowCardinalityKeyValue(AgentTraceKeys.OUTCOME).getValue()).isEqualTo("failed");
	}

	private LifecycleObserver observer() {
		return new WorkflowStepObservationObserver(this.tracer);
	}

	private static Agent agent(java.util.function.Function<AgentRequest, AgentOutcome> function) {
		return (request, context) -> subscriber -> {
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					if (demand > 0) {
						subscriber.onNext(new AgentEvent.Finished(function.apply(request)));
						subscriber.onComplete();
					}
				}

				public void cancel() {
				}
			});
		};
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run")
			.correlationId("correlation")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
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
