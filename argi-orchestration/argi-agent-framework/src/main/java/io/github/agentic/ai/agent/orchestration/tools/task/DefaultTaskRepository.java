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
package io.github.agentic.ai.agent.orchestration.tools.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

	private final List<TaskLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();

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
		if (this.backgroundTasks.remove(taskId) != null) {
			notifyRemoved(List.of(taskId));
		}
	}

	@Override
	public void clear() {
		List<String> removed = new ArrayList<>();
		for (String taskId : this.backgroundTasks.keySet()) {
			if (this.backgroundTasks.remove(taskId) != null) {
				removed.add(taskId);
			}
		}
		notifyRemoved(removed);
	}

	/**
	 * Remove completed tasks from the repository.
	 */
	public void clearCompletedTasks() {
		List<String> removed = new ArrayList<>();
		this.backgroundTasks.entrySet().removeIf(entry -> {
			if (entry.getValue().isCompleted()) {
				removed.add(entry.getKey());
				return true;
			}
			return false;
		});
		notifyRemoved(removed);
	}

	@Override
	public void addLifecycleListener(TaskLifecycleListener listener) {
		this.lifecycleListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
	}

	private void notifyRemoved(Collection<String> taskIds) {
		if (taskIds.isEmpty() || this.lifecycleListeners.isEmpty()) {
			return;
		}
		Collection<String> snapshot = List.copyOf(taskIds);
		this.lifecycleListeners.forEach(listener -> listener.onTasksRemoved(snapshot));
	}

	/**
	 * Shutdown the executor service if this repository owns it. The repository's lifetime
	 * ends here, so correlations of retained tasks are released even though their results
	 * stay readable.
	 */
	public void shutdown() {
		notifyRemoved(new ArrayList<>(this.backgroundTasks.keySet()));
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
