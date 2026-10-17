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
package io.github.agentic.ai.graph.agent.tools.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies tenant isolation in {@link DefaultTaskRepository}.
 */
class DefaultTaskRepositoryTenantIsolationTest {

	private DefaultTaskRepository repository;

	@BeforeEach
	void setUp() {
		repository = new DefaultTaskRepository();
	}

	@AfterEach
	void tearDown() {
		repository.shutdown();
	}

	@Test
	void shouldReturnTaskWhenCallerTenantMatches() {
		repository.putTask("task-1", () -> "result", "tenant-a");

		BackgroundTask task = repository.getTask("task-1", "tenant-a");

		assertThat(task).isNotNull();
		assertThat(task.getTaskId()).isEqualTo("task-1");
	}

	@Test
	void shouldHideTaskWhenCallerTenantDiffers() {
		repository.putTask("task-1", () -> "result", "tenant-a");

		BackgroundTask task = repository.getTask("task-1", "tenant-b");

		assertThat(task).isNull();
	}

	@Test
	void shouldReturnTaskWhenCallerTenantIsNullAndTaskHasTenant() {
		// Null caller means unrestricted access (e.g. admin or no trusted context)
		repository.putTask("task-1", () -> "result", "tenant-a");

		BackgroundTask task = repository.getTask("task-1", null);

		assertThat(task).isNotNull();
	}

	@Test
	void shouldReturnTaskWhenTaskHasNoTenantAndCallerHasTenant() {
		// Task created before tenant isolation was active
		repository.putTask("task-1", () -> "result");

		BackgroundTask task = repository.getTask("task-1", "tenant-a");

		assertThat(task).isNotNull();
	}

	@Test
	void shouldReturnNullForMissingTask() {
		BackgroundTask task = repository.getTask("nonexistent", "tenant-a");

		assertThat(task).isNull();
	}

	@Test
	void shouldPreserveTenantIdOnBackgroundTask() {
		repository.putTask("task-1", () -> "result", "tenant-42");

		BackgroundTask task = repository.getTask("task-1");

		assertThat(task).isNotNull();
		assertThat(task.getTenantId()).isEqualTo("tenant-42");
	}

	@Test
	void shouldHaveNullTenantOnTaskCreatedWithoutTenant() {
		repository.putTask("task-1", () -> "result");

		BackgroundTask task = repository.getTask("task-1");

		assertThat(task).isNotNull();
		assertThat(task.getTenantId()).isNull();
	}

}
