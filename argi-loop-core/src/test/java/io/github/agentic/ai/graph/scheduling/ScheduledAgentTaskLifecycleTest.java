/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.AsyncNodeAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.scheduling.ScheduleLifecycleListener.ScheduleEvent.EXECUTION_COMPLETED;
import static io.github.agentic.ai.graph.scheduling.ScheduleLifecycleListener.ScheduleEvent.EXECUTION_FAILED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledAgentTaskLifecycleTest {

	private final RecordingScheduledAgentManager manager = new RecordingScheduledAgentManager();

	@AfterEach
	void resetSchedulerFactory() {
		ScheduledAgentManagerFactory.getInstance().registerProvider(DefaultScheduledAgentManager::getInstance);
	}

	@Test
	void scheduledExecutionRetriesTransientGraphFailureThenCompletes() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		CopyOnWriteArrayList<ScheduleLifecycleListener.ScheduleEvent> events = new CopyOnWriteArrayList<>();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, 1),
				ScheduleConfig.builder()
					.initialDelay(0)
					.maxRetries(2)
					.retryDelay(Duration.ZERO)
					.addListener(recordEvents(events))
					.build()).start();

		manager.scheduler.runNext();

		assertEquals(2, attempts.get());
		assertEquals(1, count(events, EXECUTION_FAILED));
		assertEquals(1, count(events, EXECUTION_COMPLETED));
		assertTrue(manager.getTask(task.getTaskId()).isEmpty());
	}

	@Test
	void scheduledExecutionHonorsMaxRetriesAsAdditionalAttempts() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		CopyOnWriteArrayList<ScheduleLifecycleListener.ScheduleEvent> events = new CopyOnWriteArrayList<>();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, Integer.MAX_VALUE),
				ScheduleConfig.builder()
					.initialDelay(0)
					.maxRetries(2)
					.retryDelay(Duration.ZERO)
					.addListener(recordEvents(events))
					.build()).start();

		manager.scheduler.runNext();

		assertEquals(3, attempts.get());
		assertEquals(3, count(events, EXECUTION_FAILED));
		assertTrue(manager.getTask(task.getTaskId()).isEmpty());
	}

	@Test
	void scheduledExecutionStopsRetryingWhenRetryPredicateRejectsFailure() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		AtomicInteger predicateCalls = new AtomicInteger();
		CopyOnWriteArrayList<ScheduleLifecycleListener.ScheduleEvent> events = new CopyOnWriteArrayList<>();
		new ScheduledAgentTask(graphFailingFirstAttempts(attempts, Integer.MAX_VALUE),
				ScheduleConfig.builder()
					.initialDelay(0)
					.maxRetries(3)
					.retryDelay(Duration.ZERO)
					.retryPredicate(ex -> {
						predicateCalls.incrementAndGet();
						return false;
					})
					.addListener(recordEvents(events))
					.build()).start();

		manager.scheduler.runNext();

		assertEquals(1, attempts.get());
		assertEquals(1, predicateCalls.get());
		assertEquals(1, count(events, EXECUTION_FAILED));
	}

	@Test
	void manualExecuteStillSwallowsGraphFailureAndNotifiesFailureListener() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		CopyOnWriteArrayList<ScheduleLifecycleListener.ScheduleEvent> events = new CopyOnWriteArrayList<>();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, Integer.MAX_VALUE),
				ScheduleConfig.builder().initialDelay(0).addListener(recordEvents(events)).build());

		assertDoesNotThrow(() -> task.execute(null, Map.of()));

		assertEquals(1, attempts.get());
		assertEquals(1, count(events, EXECUTION_FAILED));
		assertEquals(0, count(events, EXECUTION_COMPLETED));
	}

	@Test
	void interruptedRetryDelayStopsScheduledExecutionWithoutWaitingForRemainingRetries() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch firstFailure = new CountDownLatch(1);
		AtomicReference<Throwable> uncaught = new AtomicReference<>();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, Integer.MAX_VALUE),
				ScheduleConfig.builder()
					.initialDelay(0)
					.maxRetries(10)
					.retryDelay(Duration.ofMinutes(5))
					.addListener(new ScheduleLifecycleListener() {
						@Override
						public void onEvent(ScheduleEvent event, Object data) {
							if (event == EXECUTION_FAILED) {
								firstFailure.countDown();
							}
						}
					})
					.build()).start();
		Runnable scheduledRun = manager.scheduler.removeNext();
		assertNotNull(scheduledRun);
		Thread worker = new Thread(() -> {
			try {
				scheduledRun.run();
			}
			catch (Throwable throwable) {
				uncaught.set(throwable);
			}
		}, "scheduled-retry-interruption-test");

		worker.start();
		assertTrue(firstFailure.await(5, TimeUnit.SECONDS));
		worker.interrupt();
		worker.join(TimeUnit.SECONDS.toMillis(5));

		assertFalse(worker.isAlive());
		assertEquals(1, attempts.get());
		assertEquals(null, uncaught.get());
		assertTrue(manager.getTask(task.getTaskId()).isEmpty());
	}

	@Test
	void oneTimeTaskUnregistersExactlyOnceAfterSuccessfulExecution() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, 0),
				ScheduleConfig.builder().initialDelay(0).build()).start();

		manager.scheduler.runNext();

		assertEquals(1, attempts.get());
		assertTrue(manager.getTask(task.getTaskId()).isEmpty());
		assertEquals(1, manager.unregisterCount(task.getTaskId()));
		task.stop();
		assertEquals(1, manager.unregisterCount(task.getTaskId()));
	}

	@Test
	void oneTimeTaskUnregistersExactlyOnceAfterRetriesAreExhausted() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, Integer.MAX_VALUE),
				ScheduleConfig.builder().initialDelay(0).maxRetries(1).retryDelay(Duration.ZERO).build()).start();

		manager.scheduler.runNext();

		assertEquals(2, attempts.get());
		assertTrue(manager.getTask(task.getTaskId()).isEmpty());
		assertEquals(1, manager.unregisterCount(task.getTaskId()));
		task.stop();
		assertEquals(1, manager.unregisterCount(task.getTaskId()));
	}

	@Test
	void periodicTaskRemainsRegisteredAfterOneExecution() throws Exception {
		registerRecordingManager();
		AtomicInteger attempts = new AtomicInteger();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, 0),
				ScheduleConfig.builder().fixedDelay(1000).initialDelay(0).build()).start();

		manager.scheduler.runNext();

		assertEquals(1, attempts.get());
		assertTrue(manager.getTask(task.getTaskId()).isPresent());
		assertEquals(0, manager.unregisterCount(task.getTaskId()));
	}

	@Test
	void oneTimeTaskUnregistersWhenSchedulerRunsBeforeStartReturns() throws Exception {
		registerRecordingManager();
		manager.scheduler.runOneTimeTasksImmediately();
		AtomicInteger attempts = new AtomicInteger();
		ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(attempts, 0),
				ScheduleConfig.builder().initialDelay(0).build()).start();

		assertEquals(1, attempts.get());
		assertTrue(manager.getTask(task.getTaskId()).isEmpty());
		assertEquals(1, manager.unregisterCount(task.getTaskId()));
	}

	private void registerRecordingManager() {
		ScheduledAgentManagerFactory.getInstance().registerProvider(() -> manager);
	}

	private CompiledGraph graphFailingFirstAttempts(AtomicInteger attempts, int failures) throws Exception {
		Map<String, KeyStrategy> strategies = new HashMap<>();
		strategies.put("result", (left, right) -> right);
		return new StateGraph("schedule-lifecycle-test", () -> strategies)
			.addNode("attempt", AsyncNodeAction.node_async(state -> {
				int attempt = attempts.incrementAndGet();
				if (attempt <= failures) {
					throw new IllegalStateException("planned failure " + attempt);
				}
				return Map.of("result", "ok");
			}))
			.addEdge(START, "attempt")
			.addEdge("attempt", END)
			.compile();
	}

	private ScheduleLifecycleListener recordEvents(CopyOnWriteArrayList<ScheduleLifecycleListener.ScheduleEvent> events) {
		return new ScheduleLifecycleListener() {
			@Override
			public void onEvent(ScheduleEvent event, Object data) {
				events.add(event);
			}
		};
	}

	private long count(CopyOnWriteArrayList<ScheduleLifecycleListener.ScheduleEvent> events,
			ScheduleLifecycleListener.ScheduleEvent event) {
		return events.stream().filter(event::equals).count();
	}

	private static final class RecordingScheduledAgentManager implements ScheduledAgentManager {

		private final RecordingTaskScheduler scheduler = new RecordingTaskScheduler();

		private final Map<String, ScheduledAgentTask> tasks = new HashMap<>();

		private final Map<String, AtomicInteger> unregisterCounts = new HashMap<>();

		private final AtomicInteger sequence = new AtomicInteger();

		@Override
		public String registerTask(ScheduledAgentTask task) {
			String taskId = "test-task-" + sequence.incrementAndGet();
			tasks.put(taskId, task);
			return taskId;
		}

		@Override
		public boolean unregisterTask(String taskId) {
			unregisterCounts.computeIfAbsent(taskId, key -> new AtomicInteger()).incrementAndGet();
			return tasks.remove(taskId) != null;
		}

		@Override
		public Optional<ScheduledAgentTask> getTask(String taskId) {
			return Optional.ofNullable(tasks.get(taskId));
		}

		@Override
		public Set<String> getAllActiveTaskIds() {
			return Set.copyOf(tasks.keySet());
		}

		@Override
		public int getActiveTaskCount() {
			return tasks.size();
		}

		@Override
		public TaskScheduler getTaskScheduler() {
			return scheduler;
		}

		@Override
		public boolean isShutdown() {
			return false;
		}

		@Override
		public void shutdown() {
			tasks.clear();
		}

		int unregisterCount(String taskId) {
			AtomicInteger count = unregisterCounts.get(taskId);
			return count != null ? count.get() : 0;
		}

	}

	private static final class RecordingTaskScheduler implements TaskScheduler {

		private final Queue<Runnable> scheduledTasks = new ArrayDeque<>();

		private boolean runOneTimeTasksImmediately;

		@Override
		public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
			scheduledTasks.add(task);
			return new TestScheduledFuture();
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
			if (runOneTimeTasksImmediately) {
				task.run();
				return new TestScheduledFuture();
			}
			scheduledTasks.add(task);
			return new TestScheduledFuture();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
			scheduledTasks.add(task);
			return new TestScheduledFuture();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
			scheduledTasks.add(task);
			return new TestScheduledFuture();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
			scheduledTasks.add(task);
			return new TestScheduledFuture();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
			scheduledTasks.add(task);
			return new TestScheduledFuture();
		}

		void runNext() {
			Runnable task = removeNext();
			assertNotNull(task);
			task.run();
		}

		Runnable removeNext() {
			return scheduledTasks.poll();
		}

		void runOneTimeTasksImmediately() {
			runOneTimeTasksImmediately = true;
		}

	}

	private static final class TestScheduledFuture implements ScheduledFuture<Object> {

		private volatile boolean cancelled;

		@Override
		public long getDelay(TimeUnit unit) {
			return 0;
		}

		@Override
		public int compareTo(Delayed other) {
			return 0;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			cancelled = true;
			return true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isDone() {
			return false;
		}

		@Override
		public Object get() {
			return null;
		}

		@Override
		public Object get(long timeout, TimeUnit unit) {
			return null;
		}

	}

}
