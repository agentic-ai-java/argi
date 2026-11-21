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

import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.agentic.ai.agent.lifecycle.LifecycleEvent;
import io.github.agentic.ai.agent.lifecycle.LifecycleObserver;
import io.github.agentic.ai.agent.lifecycle.LifecycleStage;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceContext;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceOperation;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceSpan;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTracer;

/**
 * Bridges the workflow engine's {@link LifecycleStage#WORKFLOW_STEP} lifecycle events
 * (issue #322) into micrometer {@link AgentTracer} spans, following the cross-agent
 * trace conventions of #131: one {@code argi.agent.workflow.step} span per executed
 * step, attributed with the run identity and the step id, ended exactly once on the
 * thread that started it.
 * <p>
 * The workflow engine emits enter/exit pairs on the executing thread (parallel branch
 * workers included), so enter/exit pairing is a per-thread LIFO stack here. The
 * workflow run identity is carried as the span's run id; with a disabled tracer the
 * observer still receives events but creates no observation, so leaving the workflow
 * unobserved preserves the historical behavior exactly.
 */
public final class WorkflowStepObservationObserver implements LifecycleObserver {

	private final AgentTracer tracer;

	private final ThreadLocal<Deque<AgentTraceSpan>> spans = ThreadLocal.withInitial(ConcurrentLinkedDeque::new);

	public WorkflowStepObservationObserver(AgentTracer tracer) {
		this.tracer = tracer;
	}

	@Override
	public Set<LifecycleStage> stages() {
		return Set.of(LifecycleStage.WORKFLOW_STEP);
	}

	@Override
	public String id() {
		return "workflow-step-observation";
	}

	@Override
	public void onEvent(LifecycleEvent event) {
		if (event instanceof LifecycleEvent.StageEntered entered) {
			AgentTraceSpan span = startSpan(entered);
			this.spans.get().push(span);
		}
		else if (event instanceof LifecycleEvent.StageExited exited) {
			AgentTraceSpan span = this.spans.get().poll();
			if (span == null) {
				return;
			}
			if (exited.successful()) {
				span.success();
			}
			else {
				span.outcome("failed").failure();
			}
			span.close();
		}
	}

	private AgentTraceSpan startSpan(LifecycleEvent.StageEntered entered) {
		if (this.tracer == null || !this.tracer.isEnabled()) {
			return AgentTraceSpan.disabled();
		}
		// The workflow run identity travels as the invocation id of the event.
		AgentTraceContext context = AgentTraceContext.builder()
			.runId(entered.identity().invocation() == null ? null : entered.identity().invocation().value())
			.build();
		AgentTraceSpan span = this.tracer.start(AgentTraceOperation.WORKFLOW_STEP, context);
		if (span.isDisabled()) {
			return span;
		}
		return entered.identity().step() == null ? span : span.stepId(entered.identity().step().value());
	}

}
