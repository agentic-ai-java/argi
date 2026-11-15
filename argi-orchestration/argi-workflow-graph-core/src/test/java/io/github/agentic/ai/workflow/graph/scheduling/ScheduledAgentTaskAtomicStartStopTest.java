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
package io.github.agentic.ai.workflow.graph.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;


import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import static io.github.agentic.ai.workflow.graph.scheduling.ScheduledAgentTaskLifecycleTest.graphFailingFirstAttempts;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复现 #215：start/stop 必须是原子状态转换，双 start 只留一份调度，stop 后不得复活。 */
class ScheduledAgentTaskAtomicStartStopTest {

	private final ScheduledAgentManagerFactory factory = ScheduledAgentManagerFactory.getInstance();

	private final ScheduledAgentManager originalManager = factory.getManager();

	private final TestManager manager = new TestManager();

	/** 恢复测试前的管理器，避免全局 provider 影响其他测试。 */
	@AfterEach
	void restoreOriginalManager() {
		factory.registerProvider(() -> originalManager);
	}

	/** 验证第一个 start 卡在调度发布窗口时，并发的第二个 start 被拒绝且只产生一份调度。 */
	@Test
	void aConcurrentSecondStartWhileTheFirstIsPublishingIsRejectedAndSchedulesOnce() throws Exception {
		factory.registerProvider(() -> manager);
		CountDownLatch scheduleEntered = new CountDownLatch(1);
		CountDownLatch releaseSchedule = new CountDownLatch(1);
		manager.scheduler.gateNextSchedule(scheduleEntered, releaseSchedule);
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		AtomicReference<Throwable> firstFailure = new AtomicReference<>();
		AtomicReference<Throwable> secondFailure = new AtomicReference<>();
		Thread first = startInBackground(task, firstFailure, "first-start");
		Thread second = startInBackground(task, secondFailure, "second-start");
		try {
			first.start();
			// 第一个 start 已进入调度发布（旧代码此刻 started 仍为 false）。
			assertTrue(scheduleEntered.await(5, TimeUnit.SECONDS), "first start must be inside schedule");
			second.start();
			second.join(TimeUnit.SECONDS.toMillis(5));
			releaseSchedule.countDown();
			first.join(TimeUnit.SECONDS.toMillis(5));

			assertAll(() -> assertFalse(first.isAlive(), "first start must finish"),
					() -> assertFalse(second.isAlive(), "second start must finish"),
					() -> assertNull(firstFailure.get(), "The winning start must succeed"),
					() -> assertTrue(secondFailure.get() instanceof IllegalStateException,
							"The losing concurrent start must be rejected, got: " + secondFailure.get()),
					() -> assertEquals(1, manager.scheduler.scheduleCount(),
							"A double start must not publish two schedules"));
		}
		finally {
			releaseSchedule.countDown();
			first.join(TimeUnit.SECONDS.toMillis(5));
			second.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	/** 验证 stop 之后再 start 被拒绝：已停止的任务不得重新进入调度。 */
	@Test
	void aStartAfterStopIsRejectedAndPublishesNoSchedule() throws Exception {
		factory.registerProvider(() -> manager);
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		task.stop();
		assertTrue(task.isStopped());

		assertThrows(IllegalStateException.class, task::start);

		assertEquals(0, manager.scheduler.scheduleCount(), "A stopped task must not be scheduled again");
	}

	/** 验证 start 调度失败时不遗留注册，异常原样传播。 */
	@Test
	void aFailedStartUnregistersTheTaskAndPropagatesTheCause() throws Exception {
		factory.registerProvider(() -> manager);
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		assertEquals(1, manager.getActiveTaskCount());
		RuntimeException schedulerBroken = new IllegalStateException("scheduler broken");
		manager.scheduler.throwOnSchedule(schedulerBroken);

		RuntimeException thrown = assertThrows(IllegalStateException.class, task::start);

		assertAll(() -> assertEquals(schedulerBroken.getMessage(), thrown.getMessage(), "cause must propagate"),
				() -> assertEquals(0, manager.getActiveTaskCount(),
						"A failed start must not leave the task registered"),
				() -> assertFalse(task.isStarted(), "A failed start is not started"),
				() -> assertFalse(task.isStopped(), "A failed start is not stopped either"));
	}

	/** 验证 STARTING 窗口内的 stop 取消迟到发布的 future，start 以异常结束且终态停止。 */
	@Test
	void aStopDuringStartCancelsTheLateFutureAndFinishesStopped() throws Exception {
		factory.registerProvider(() -> manager);
		CountDownLatch scheduleEntered = new CountDownLatch(1);
		CountDownLatch releaseSchedule = new CountDownLatch(1);
		manager.scheduler.gateNextSchedule(scheduleEntered, releaseSchedule);
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		Thread starter = startInBackground(task, startFailure, "racing-start");
		try {
			starter.start();
			assertTrue(scheduleEntered.await(5, TimeUnit.SECONDS), "start must be inside schedule");

			task.stop();
			releaseSchedule.countDown();
			starter.join(TimeUnit.SECONDS.toMillis(5));

			assertAll(() -> assertFalse(starter.isAlive(), "start must finish"),
					() -> assertTrue(startFailure.get() instanceof IllegalStateException,
							"A start losing the race to stop must fail, got: " + startFailure.get()),
					() -> assertTrue(task.isStopped(), "The task must end stopped"),
					() -> assertEquals(0, manager.getActiveTaskCount(), "The task must be unregistered"),
					() -> assertTrue(manager.scheduler.lastFuture().isCancelled(),
							"The late-published future must be cancelled, not left live"));
		}
		finally {
			releaseSchedule.countDown();
			starter.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	/** 验证状态机全链转移：NEW→STARTING→RUNNING→STOPPING→STOPPED，started 历史在停止后保留。 */
	@Test
	void theLifecycleWalksNewStartingRunningStoppingStopped() throws Exception {
		factory.registerProvider(() -> manager);
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		assertEquals(ScheduledAgentTask.ScheduleState.NEW, task.state());

		task.start();
		assertAll(() -> assertEquals(ScheduledAgentTask.ScheduleState.RUNNING, task.state()),
				() -> assertTrue(task.isStarted()), () -> assertFalse(task.isStopped()));

		task.stop();
		assertAll(() -> assertEquals(ScheduledAgentTask.ScheduleState.STOPPED, task.state()),
				() -> assertTrue(task.isStarted(), "started history is retained after stop"),
				() -> assertTrue(task.isStopped()));
	}

	private Thread startInBackground(ScheduledAgentTask task, AtomicReference<Throwable> failure, String name) {
		Thread worker = new Thread(() -> {
			try {
				task.start();
			}
			catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, name);
		return worker;
	}

	/** 测试管理器：自带可控 scheduler，任务注册/注销全部记录。 */
	private static final class TestManager implements ScheduledAgentManager {

		final GatedTaskScheduler scheduler = new GatedTaskScheduler();

		private final Map<String, ScheduledAgentTask> tasks = new HashMap<>();

		private final AtomicInteger sequence = new AtomicInteger();

		@Override
		public String registerTask(ScheduledAgentTask task) {
			String taskId = "test-task-" + sequence.incrementAndGet();
			tasks.put(taskId, task);
			return taskId;
		}

		@Override
		public boolean unregisterTask(String taskId) {
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

	}

	/** 可控 TaskScheduler：可阻塞 gate、可注入失败，仅统计调度次数。 */
	private static final class GatedTaskScheduler implements TaskScheduler {

		private final AtomicInteger scheduleCount = new AtomicInteger();

		private final AtomicReference<RuntimeException> plannedFailure = new AtomicReference<>();

		private volatile CountDownLatch scheduleEntered;

		private volatile CountDownLatch releaseSchedule;

		private volatile ScheduledFuture<?> lastFuture;

		private void gateNextSchedule(CountDownLatch entered, CountDownLatch release) {
			this.scheduleEntered = entered;
			this.releaseSchedule = release;
		}

		private void throwOnSchedule(RuntimeException cause) {
			plannedFailure.set(cause);
		}

		private int scheduleCount() {
			return scheduleCount.get();
		}

		private ScheduledFuture<?> lastFuture() {
			return lastFuture;
		}

		private ScheduledFuture<?> submit() {
			scheduleCount.incrementAndGet();
			RuntimeException planned = plannedFailure.get();
			if (planned != null) {
				throw planned;
			}
			CountDownLatch entered = this.scheduleEntered;
			if (entered != null) {
				entered.countDown();
				try {
					releaseSchedule.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			ScheduledFuture<?> future = new StubScheduledFuture();
			lastFuture = future;
			return future;
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
			return submit();
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
			return submit();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
			return submit();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
			return submit();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
			return submit();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
			return submit();
		}

	}

	private static final class StubScheduledFuture implements ScheduledFuture<Object> {

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
			return cancelled;
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
