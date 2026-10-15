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

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.springframework.util.Assert;

/**
 * Opt-in local task repository with bounded execution, waiting and result retention.
 * Duplicate task IDs are rejected until their execution has exited and their retained
 * result has been removed or expired. Cancelling a running task does not release its
 * execution slot before its supplier exits. Execution slots include wrappers submitted to
 * a caller-owned executor but not yet started; its smaller pool cannot expand the
 * repository's admission capacity.
 * <p>
 * Explicit removal hides an active task without cancelling it or releasing its ID or
 * capacity. Automatic cleanup only removes tasks whose execution has exited. Executor
 * submission failures retire only the affected task; other accepted tasks continue to be
 * dispatched without retrying the failed task.
 * <p>
 * Register as a Spring bean with {@code @Bean(destroyMethod = "close")}, or close it
 * explicitly. An executor supplied by the caller is never shut down by this repository.
 * Rejection messages are diagnostic text, not reason codes. Once open, duplicate IDs are
 * checked before capacity; closed repositories reject admission first.
 */
public final class BoundedTaskRepository implements TaskRepository, AutoCloseable {

	// Configuration: immutable after construction.
	private final int maxConcurrentTasks;

	private final int queueCapacity;

	private final int maxRetainedResults;

	private final long retentionNanos;

	private final LongSupplier nanoTime;

	// Task index and queues: all access to their contents requires monitor.
	private final Object monitor = new Object();

	private final Map<String, Task> tasks = new HashMap<>();

	// Waiting tasks have no slot; ready tasks already count toward reservedSlots.
	private final Deque<Task> waiting = new ArrayDeque<>();

	// Separate dispatch from admission so executor.execute can run outside monitor.
	private final Deque<Task> ready = new ArrayDeque<>();

	private final LinkedHashMap<String, Task> completed = new LinkedHashMap<>();

	// Execution resources: submission and shutdown are performed outside monitor.
	private final ExecutorService executor;

	private final boolean ownsExecutor;

	private final ScheduledExecutorService cleanupExecutor;

	// Runtime state: all reads and writes require monitor.
	// Includes reserved and submitted wrappers whose suppliers have not started yet.
	private int reservedSlots;

	private boolean draining;

	private boolean closed;

	/**
	 * Create a repository which owns its execution pool and cleanup thread.
	 * @param maxConcurrentTasks positive number of execution slots
	 * @param queueCapacity nonnegative number of waiting tasks; zero disables waiting
	 * @param maxRetainedResults nonnegative number of retained terminal results
	 * @param resultRetention positive retention time measured from execution exit
	 */
	public BoundedTaskRepository(int maxConcurrentTasks, int queueCapacity, int maxRetainedResults,
			Duration resultRetention) {
		this(maxConcurrentTasks, queueCapacity, maxRetainedResults, resultRetention, null, true, System::nanoTime,
				null);
	}

	/**
	 * Create a repository using a caller-owned executor. Limits are enforced
	 * independently of that executor's pool size. The executor must run accepted commands
	 * or throw on rejection; silently discarding commands is not supported.
	 * @param executor caller-owned executor, which is never shut down by this repository
	 * @param maxConcurrentTasks positive number of execution slots
	 * @param queueCapacity nonnegative number of waiting tasks
	 * @param maxRetainedResults nonnegative number of retained terminal results
	 * @param resultRetention positive retention time measured from execution exit
	 */
	public BoundedTaskRepository(ExecutorService executor, int maxConcurrentTasks, int queueCapacity,
			int maxRetainedResults, Duration resultRetention) {
		this(maxConcurrentTasks, queueCapacity, maxRetainedResults, resultRetention,
				Objects.requireNonNull(executor, "executor must not be null"), false, System::nanoTime, null);
	}

	BoundedTaskRepository(int maxConcurrentTasks, int queueCapacity, int maxRetainedResults, Duration resultRetention,
			ExecutorService executor, boolean ownsExecutor, LongSupplier nanoTime,
			ScheduledExecutorService cleanupExecutor) {
		Assert.isTrue(maxConcurrentTasks > 0, "maxConcurrentTasks must be greater than zero");
		Assert.isTrue(queueCapacity >= 0, "queueCapacity must not be negative");
		Assert.isTrue(maxRetainedResults >= 0, "maxRetainedResults must not be negative");
		Assert.notNull(resultRetention, "resultRetention must not be null");
		Assert.isTrue(!resultRetention.isNegative() && !resultRetention.isZero(), "resultRetention must be positive");
		try {
			this.retentionNanos = resultRetention.toNanos();
		}
		catch (ArithmeticException ex) {
			throw new IllegalArgumentException("resultRetention is too large to represent in nanoseconds", ex);
		}
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
		this.maxConcurrentTasks = maxConcurrentTasks;
		this.queueCapacity = queueCapacity;
		this.maxRetainedResults = maxRetainedResults;
		this.ownsExecutor = ownsExecutor;
		this.executor = executor != null ? executor
				: Executors.newFixedThreadPool(maxConcurrentTasks, runnable -> daemonThread(runnable, "bounded-task"));
		this.cleanupExecutor = cleanupExecutor != null ? cleanupExecutor : Executors
			.newSingleThreadScheduledExecutor(runnable -> daemonThread(runnable, "bounded-task-cleanup"));
		if (maxRetainedResults > 0) {
			long interval = Math.max(TimeUnit.MILLISECONDS.toNanos(1),
					Math.min(this.retentionNanos, TimeUnit.SECONDS.toNanos(1)));
			this.cleanupExecutor.scheduleWithFixedDelay(this::cleanUp, interval, interval, TimeUnit.NANOSECONDS);
		}
	}

	@Override
	public BackgroundTask getTask(String taskId) {
		synchronized (this.monitor) {
			cleanUp();
			Task task = this.tasks.get(taskId);
			return task != null && task.visible ? task.handle : null;
		}
	}

	@Override
	public BackgroundTask putTask(String taskId, Supplier<String> taskExecution) {
		Assert.hasText(taskId, "taskId must not be empty");
		Assert.notNull(taskExecution, "taskExecution must not be null");
		Task task;
		synchronized (this.monitor) {
			cleanUp();
			if (this.closed) {
				throw new RejectedExecutionException("Task repository is closed");
			}
			if (this.tasks.containsKey(taskId)) {
				throw new RejectedExecutionException("Task ID already exists: " + taskId);
			}
			if (this.reservedSlots >= this.maxConcurrentTasks && this.waiting.size() >= this.queueCapacity) {
				throw new RejectedExecutionException("Task repository capacity is full");
			}
			task = new Task(taskId, taskExecution);
			this.tasks.put(taskId, task);
			if (this.reservedSlots < this.maxConcurrentTasks) {
				reserveSlot(task);
			}
			else {
				this.waiting.addLast(task);
			}
		}
		drain();
		synchronized (this.monitor) {
			if (task.submissionFailure instanceof RuntimeException exception) {
				throw exception;
			}
			if (task.submissionFailure instanceof Error error) {
				throw error;
			}
			// After handle publication, later submission failures complete its future
			// instead of being thrown to the original caller.
			task.returned = true;
			return task.handle;
		}
	}

	@Override
	public void removeTask(String taskId) {
		synchronized (this.monitor) {
			Task task = this.tasks.get(taskId);
			if (task != null) {
				task.visible = false;
				if (task.finished) {
					this.tasks.remove(taskId, task);
					this.completed.remove(taskId, task);
				}
			}
		}
	}

	@Override
	public void clear() {
		synchronized (this.monitor) {
			this.tasks.values().forEach(task -> task.visible = false);
			this.tasks.values().removeIf(task -> task.finished);
			this.completed.clear();
		}
	}

	// Requires the caller to hold monitor.
	private void reserveSlot(Task task) {
		task.slot = true;
		this.reservedSlots++;
		this.ready.addLast(task);
	}

	// A single drain loop also supports inline executors without recursive dispatch.
	// Acquires monitor internally; callers must not hold it during executor submission.
	private void drain() {
		synchronized (this.monitor) {
			if (this.draining) {
				return;
			}
			this.draining = true;
		}
		while (true) {
			Task task;
			synchronized (this.monitor) {
				task = this.ready.pollFirst();
				if (task == null) {
					this.draining = false;
					return;
				}
			}
			try {
				this.executor.execute(task);
			}
			catch (RuntimeException | Error ex) {
				synchronized (this.monitor) {
					// Preserve the failure for this task's original submitting call.
					task.submissionFailure = ex;
					finish(task);
					if (!task.returned) {
						this.tasks.remove(task.id, task);
						this.completed.remove(task.id, task);
					}
				}
				task.future.completeExceptionally(ex);
			}
		}
	}

	// Acquires monitor internally; completed-task callbacks may reenter and return early.
	// Dispatch after queue cancellation must happen without monitor held.
	private void cancelWaiting(Task task) {
		synchronized (this.monitor) {
			if (task.finished || task.started) {
				return;
			}
			if (!this.waiting.remove(task) && !this.ready.remove(task)) {
				// Already submitted: keep its slot until the wrapper actually runs.
				return;
			}
			finish(task);
		}
		drain();
	}

	// Requires the caller to hold monitor, only after execution exits or before it can
	// start.
	private void finish(Task task) {
		if (task.finished) {
			return;
		}
		task.finished = true;
		task.execution = null;
		this.waiting.remove(task);
		this.ready.remove(task);
		if (task.slot) {
			this.reservedSlots--;
		}
		if (task.visible && !this.closed && this.maxRetainedResults > 0) {
			task.finishedAt = this.nanoTime.getAsLong();
			this.completed.put(task.id, task);
			cleanUp();
		}
		else {
			this.tasks.remove(task.id, task);
		}
		while (!this.closed && this.reservedSlots < this.maxConcurrentTasks && !this.waiting.isEmpty()) {
			reserveSlot(this.waiting.removeFirst());
		}
	}

	// Acquires monitor internally and also supports callers which already hold it.
	private void cleanUp() {
		synchronized (this.monitor) {
			long now = this.nanoTime.getAsLong();
			var iterator = this.completed.entrySet().iterator();
			while (iterator.hasNext()) {
				Task task = iterator.next().getValue();
				// Once this result fits both limits, completion order lets cleanup stop.
				if (this.completed.size() <= this.maxRetainedResults && now - task.finishedAt < this.retentionNanos) {
					break;
				}
				iterator.remove();
				this.tasks.remove(task.id, task);
			}
		}
	}

	/**
	 * Stop admission and cleanup, and cancel work which has not started. An owned
	 * executor is given up to five seconds to finish, then interrupted. Running suppliers
	 * on a caller-owned executor may finish normally; that executor is never shut down.
	 */
	@Override
	public void close() {
		synchronized (this.monitor) {
			if (this.closed) {
				return;
			}
			this.closed = true;
			for (Task task : new ArrayList<>(this.tasks.values())) {
				if (!task.started && !task.finished) {
					// Finish first so the synchronous cancellation callback returns
					// early.
					finish(task);
					task.future.cancel(false);
				}
			}
			this.completed.clear();
			this.tasks.values().removeIf(task -> task.finished);
		}
		this.cleanupExecutor.shutdownNow();
		if (this.ownsExecutor) {
			this.executor.shutdown();
			try {
				if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
					this.executor.shutdownNow();
				}
			}
			catch (InterruptedException ex) {
				this.executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	private static Thread daemonThread(Runnable runnable, String prefix) {
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		thread.setName(prefix + "-" + thread.getId());
		return thread;
	}

	private final class Task implements Runnable {

		private final String id;

		private final CompletableFuture<String> future = new CompletableFuture<>();

		private final BackgroundTask handle;

		// Mutable task state: all access after publication requires the enclosing
		// monitor.
		// The final future is thread-safe and may be accessed without monitor.
		private Supplier<String> execution;

		private boolean slot;

		private boolean started;

		private boolean finished;

		private boolean visible = true;

		private boolean returned;

		private long finishedAt;

		private Throwable submissionFailure;

		private Task(String id, Supplier<String> execution) {
			this.id = id;
			this.execution = execution;
			this.handle = new BackgroundTask(id, this.future);
			this.future.whenComplete((result, error) -> {
				if (this.future.isCancelled()) {
					cancelWaiting(this);
				}
			});
		}

		// Acquires monitor for state transitions; invokes the supplier and completes the
		// future outside monitor, so executor callers must not hold it when invoking run.
		@Override
		public void run() {
			Supplier<String> supplier;
			synchronized (monitor) {
				if (this.finished) {
					return;
				}
				this.started = !closed && !this.future.isCancelled();
				supplier = this.started ? this.execution : null;
			}
			String result = null;
			Throwable failure = null;
			try {
				if (supplier != null) {
					result = supplier.get();
				}
			}
			catch (Throwable ex) {
				failure = ex;
			}
			finally {
				// Release bookkeeping before completing the future: callbacks may submit
				// work.
				synchronized (monitor) {
					finish(this);
				}
				if (failure != null) {
					this.future.completeExceptionally(failure);
				}
				else {
					this.future.complete(result);
				}
				drain();
			}
		}

	}

}
