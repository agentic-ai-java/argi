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

package io.github.agentic.ai.graph.execution.run;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.observation.trace.AgentTraceKeys;
import io.github.agentic.ai.graph.observation.trace.AgentTracer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ObservedRunStore span tests")
class ObservedRunStoreTest {

	private final RecordingHandler handler = new RecordingHandler();

	private ObservedRunStore store;

	@BeforeEach
	void setUp() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(this.handler);
		this.store = new ObservedRunStore(new MemoryRunStore(Clock.systemUTC()), new AgentTracer(registry));
	}

	private RunSpec spec(String runId) {
		return RunSpec.from(context(runId), "app", "session", "key", "graph", "1", new RunPayload(1, "test.input", "{}"));
	}

	private static TrustedExecutionContext context(String runId) {
		return TrustedExecutionContext.builder()
			.tenant(new io.github.agentic.ai.graph.execution.TenantReference("iam", "tenant-a"))
			.principal(new io.github.agentic.ai.graph.execution.PrincipalReference("iam", "user"))
			.runId(runId)
			.correlationId("trace")
			.budget(new io.github.agentic.ai.graph.execution.ExecutionBudget(java.util.Map.of("tokens", 100L)))
			.delegationScope(new io.github.agentic.ai.graph.execution.DelegationScope(Set.of("read")))
			.build();
	}

	@Test
	@DisplayName("submit, acquire and commit emit correlated run store spans")
	void lifecycleEmitsCorrelatedSpans() {
		RunSpec run = spec("run-1");
		this.store.submit(run, context("run-1"));
		RunLease lease = this.store.acquire(run.namespace(), "run-1", UUID.randomUUID(), Duration.ofSeconds(30));
		this.store.commit(run.namespace(), "run-1", lease,
				new RunCommit(0, RunState.RUNNING, null, List.of(), List.of(), List.of()));

		assertThat(this.handler.stopped).hasSize(3);
		assertThat(this.handler.stopped).allSatisfy(stopped -> {
			assertThat(stopped.getName()).isEqualTo("argi.run.store");
			assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.RUN_ID).getValue()).isEqualTo("run-1");
			assertThat(stopped.getHighCardinalityKeyValue(AgentTraceKeys.TENANT).getValue()).isEqualTo("tenant-a");
			assertThat(stopped.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("true");
		});
		assertThat(this.handler.stopped.get(0).getLowCardinalityKeyValue(AgentTraceKeys.PHASE).getValue())
			.isEqualTo("submit");
		Observation.Context acquire = this.handler.stopped.get(1);
		assertThat(acquire.getLowCardinalityKeyValue(AgentTraceKeys.PHASE).getValue()).isEqualTo("acquire");
		assertThat(acquire.getHighCardinalityKeyValue(AgentTraceKeys.ATTEMPT_ID).getValue())
			.isEqualTo(String.valueOf(lease.fencingToken()));
		assertThat(this.handler.stopped.get(2).getLowCardinalityKeyValue(AgentTraceKeys.PHASE).getValue())
			.isEqualTo("commit");
	}

	@Test
	@DisplayName("commit spans carry the committed task identifiers")
	void commitSpansCarryTaskIds() {
		RunSpec run = spec("run-1");
		this.store.submit(run, context("run-1"));
		RunLease lease = this.store.acquire(run.namespace(), "run-1", UUID.randomUUID(), Duration.ofSeconds(30));
		TaskSpec child = new TaskSpec(1, "task-1", run.runId(), "task", "1", new RunPayload(1, "test.input", "{}"),
				context("run-1").delegate("task-1", Set.of("read"), null, null).toReference());
		this.store.commit(run.namespace(), "run-1", lease,
				new RunCommit(0, RunState.RUNNING, null, List.of(new RunCommit.TaskUpdate(child, -1, RunState.PENDING)),
						List.of(), List.of()));

		Observation.Context commit = this.handler.stopped.get(2);
		assertThat(commit.getHighCardinalityKeyValue(AgentTraceKeys.TASK_ID).getValue()).isEqualTo("task-1");
	}

	@Test
	@DisplayName("failures are recorded on the span and rethrown unchanged")
	void failuresAreRecordedAndRethrown() {
		RunSpec first = spec("run-1");
		this.store.submit(first, context("run-1"));
		RunSpec conflicting = RunSpec.from(context("run-2"), "app", "session", "key", "graph", "1",
				new RunPayload(1, "test.input", "{\"a\":2}"));

		assertThatThrownBy(() -> this.store.submit(conflicting, context("run-2")))
			.isInstanceOf(RunStoreConflictException.class);

		Observation.Context failed = this.handler.stopped.get(1);
		assertThat(failed.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("false");
		assertThat(failed.getLowCardinalityKeyValue(AgentTraceKeys.ERROR_TYPE).getValue())
			.isEqualTo(RunStoreConflictException.class.getName());
	}

	@Test
	@DisplayName("read-only lookups and capabilities emit no spans")
	void readOnlyLookupsEmitNoSpans() {
		RunSpec run = spec("run-1");
		this.store.submit(run, context("run-1"));
		this.handler.stopped.clear();

		this.store.capabilities();
		this.store.find(run.namespace(), "run-1");
		this.store.activeRun(run.namespace());

		assertThat(this.handler.stopped).isEmpty();
	}

	@Test
	@DisplayName("the decorator requires a delegate and a tracer")
	void requiresDelegateAndTracer() {
		assertThatThrownBy(() -> new ObservedRunStore(null, new AgentTracer(null)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObservedRunStore(new MemoryRunStore(), null))
			.isInstanceOf(IllegalArgumentException.class);
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
