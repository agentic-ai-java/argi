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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Default implementation of TaskRepository using a thread pool for background execution.
 *
 */
public class DefaultTaskRepository implements TaskRepository {

	private final Map<String, BackgroundTask> backgroundTasks = new ConcurrentHashMap<>();

	private final ExecutorService executor;

	private final boolean ownsExecutor;

	/**
	 * Create a repository with a default cached thread pool executor.
	 */
	public DefaultTaskRepository() {
		this(Executors.newCachedThreadPool(r -> {
			Thread thread = new Thread(r);
			thread.setDaemon(true);
			thread.setName("background-task-" + thread.getId());
			return thread;
		}), true);
	}

	/**
	 * Create a repository with a custom executor service.
	 */
	public DefaultTaskRepository(ExecutorService executor) {
		this(executor, false);
	}

	/**
	 * Internal constructor for specifying executor ownership.
	 */
	public DefaultTaskRepository(ExecutorService executor, boolean ownsExecutor) {
		this.executor = executor;
		this.ownsExecutor = ownsExecutor;
	}

	@Override
	public BackgroundTask getTask(String taskId) {
		return this.backgroundTasks.get(taskId);
	}

	@Override
	public BackgroundTask getTask(String taskId, String callerTenantId) {
		BackgroundTask task = this.backgroundTasks.get(taskId);
		if (task == null) {
			return null;
		}
		// Tenant isolation: allow access only when caller tenant matches task tenant,
		// or when caller tenant is null (unrestricted access, e.g. admin or no trusted context)
		String taskTenant = task.getTenantId();
		if (callerTenantId != null && taskTenant != null && !taskTenant.equals(callerTenantId)) {
			return null;
		}
		return task;
	}

	@Override
	public BackgroundTask putTask(String taskId, Supplier<String> taskExecution) {
		return putTask(taskId, taskExecution, null);
	}

	/**
	 * Add a new background task with tenant ownership.
	 * @param taskId the task identifier
	 * @param taskExecution the supplier that executes the task and returns its output
	 * @param tenantId the tenant identifier of the creating principal, or null
	 * @return the created background task
	 */
	public BackgroundTask putTask(String taskId, Supplier<String> taskExecution, String tenantId) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(taskExecution, this.executor);
		BackgroundTask backgroundTask = new BackgroundTask(taskId, future, tenantId);
		this.backgroundTasks.put(taskId, backgroundTask);
		return backgroundTask;
	}

	@Override
	public void removeTask(String taskId) {
		this.backgroundTasks.remove(taskId);
	}

	@Override
	public void clear() {
		this.backgroundTasks.clear();
	}

	/**
	 * Remove completed tasks from the repository.
	 */
	public void clearCompletedTasks() {
		this.backgroundTasks.entrySet().removeIf(entry -> entry.getValue().isCompleted());
	}

	/**
	 * Shutdown the executor service if this repository owns it.
	 */
	public void shutdown() {
		if (this.ownsExecutor && this.executor != null) {
			this.executor.shutdown();
			try {
				if (!this.executor.awaitTermination(60, TimeUnit.SECONDS)) {
					this.executor.shutdownNow();
				}
			}
			catch (InterruptedException e) {
				this.executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

}
