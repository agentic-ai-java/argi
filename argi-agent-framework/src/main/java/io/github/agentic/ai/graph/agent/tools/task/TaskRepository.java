/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.tools.task;

import java.util.function.Supplier;

/**
 * Repository for managing background tasks.
 * <p>
 * Inspired by spring ai TaskRepository, enables the main agent to
 * launch sub-agents in the background and retrieve results later via TaskOutputTool.
 *
 */
public interface TaskRepository {

	/**
	 * Get a background task by its ID.
	 * @param taskId the task identifier
	 * @return the background task, or null if not found
	 */
	BackgroundTask getTask(String taskId);

	/**
	 * Get a background task by its ID with tenant isolation.
	 * <p>
	 * Implementations may enforce tenant-scoped access: a task is returned only if its
	 * {@code tenantId} matches the supplied {@code callerTenantId}, or when the caller
	 * tenant is null (unrestricted access). When the stored task has a non-null tenant ID
	 * that differs from {@code callerTenantId}, the implementation may return {@code null}
	 * to hide the task's existence from callers of other tenants.
	 * <p>
	 * Default implementations delegate to {@link #getTask(String)} without isolation. To
	 * enforce tenant boundaries, implementations must override this method.
	 * @param taskId the task identifier
	 * @param callerTenantId the tenant identifier of the caller, or null for unrestricted
	 * access
	 * @return the background task, or null if not found or not accessible by the caller
	 */
	default BackgroundTask getTask(String taskId, String callerTenantId) {
		return getTask(taskId);
	}

	/**
	 * Add a new background task to the repository.
	 * @param taskId the task identifier
	 * @param taskExecution the supplier that executes the task and returns its output
	 * @return the created background task
	 */
	BackgroundTask putTask(String taskId, Supplier<String> taskExecution);

	/**
	 * Remove a background task from the repository.
	 * @param taskId the task identifier
	 */
	void removeTask(String taskId);

	/**
	 * Clear all tasks from the repository.
	 */
	void clear();

}
