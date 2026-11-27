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
package io.github.agentic.ai.agent.orchestration.tools.task;

import java.time.Duration;
import java.util.Map;

import io.github.agentic.ai.agent.orchestration.ReactAgent;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Issue #370 review gap 2: repository removals release the shared correlations without
 * waiting for another poll or submission, so an idle store cannot retain tasks that the
 * repository no longer serves.
 */
class TaskTraceCorrelationRepositoryLifecycleTest {

	private final TaskTraceCorrelation correlation = TaskTraceCorrelation.empty();

	@Test
	void removingATaskReleasesItsCorrelationWithoutAPoll() {
		DefaultTaskRepository repository = new DefaultTaskRepository();
		new TaskOutputTool(repository, null, correlation);
		repository.putTask("task-1", () -> "done");
		correlation.record("task-1", context("task-1"));

		repository.removeTask("task-1");

		assertThat(correlation.retainedCount()).as("removal must not wait for the next poll").isZero();
		assertThat(correlation.correlation("task-1")).isEmpty();
	}

	@Test
	void clearingTheRepositoryReleasesEveryCorrelationWithoutAPoll() {
		DefaultTaskRepository repository = new DefaultTaskRepository();
		new TaskOutputTool(repository, null, correlation);
		repository.putTask("task-1", () -> "done");
		repository.putTask("task-2", () -> "done");
		correlation.record("task-1", context("task-1"));
		correlation.record("task-2", context("task-2"));

		repository.clear();

		assertThat(correlation.retainedCount()).as("clear must release every cleared task").isZero();
	}

	@Test
	void shuttingDownTheRepositoryReleasesRetainedCorrelations() {
		DefaultTaskRepository repository = new DefaultTaskRepository();
		new TaskOutputTool(repository, null, correlation);
		repository.putTask("task-1", () -> "done");
		correlation.record("task-1", context("task-1"));

		repository.shutdown();

		assertThat(correlation.retainedCount()).as("the repository lifetime ended").isZero();
		assertThat(correlation.correlation("task-1")).isEmpty();
	}

	@Test
	void theDelegationToolRegistersTheSameLifecycleCleanup() {
		DefaultTaskRepository repository = new DefaultTaskRepository();
		new TaskTool(Map.of("child_agent", mock(ReactAgent.class)), repository, null, correlation);
		repository.putTask("task-1", () -> "done");
		correlation.record("task-1", context("task-1"));

		repository.clear();

		assertThat(correlation.retainedCount()).as("TaskTool must wire the cleanup as well").isZero();
	}

	@Test
	void boundedRepositoryRemovalClearAndCloseReleaseCorrelationsWithoutPolls() {
		BoundedTaskRepository repository = new BoundedTaskRepository(4, 4, 10, Duration.ofMinutes(5));
		try {
			new TaskOutputTool(repository, null, correlation);

			repository.putTask("task-1", () -> "done");
			correlation.record("task-1", context("task-1"));
			repository.removeTask("task-1");
			assertThat(correlation.retainedCount()).as("remove releases the correlation").isZero();

			repository.putTask("task-2", () -> "done");
			correlation.record("task-2", context("task-2"));
			repository.clear();
			assertThat(correlation.retainedCount()).as("clear releases the correlation").isZero();

			repository.putTask("task-3", () -> "done");
			correlation.record("task-3", context("task-3"));
			repository.close();
			assertThat(correlation.retainedCount()).as("close releases the correlation").isZero();
		}
		finally {
			repository.close();
		}
	}

	private AgentTraceContext context(String taskId) {
		return AgentTraceContext.capture(null, null).withTaskId(taskId);
	}

}
