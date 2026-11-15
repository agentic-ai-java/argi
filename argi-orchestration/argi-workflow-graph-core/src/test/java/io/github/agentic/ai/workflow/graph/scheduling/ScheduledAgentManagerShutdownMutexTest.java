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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.workflow.graph.CompiledGraph;
import io.github.agentic.ai.workflow.graph.KeyStrategy;
import io.github.agentic.ai.workflow.graph.StateGraph;
import io.github.agentic.ai.workflow.graph.action.AsyncNodeAction;
import io.github.agentic.ai.workflow.graph.scheduling.ScheduledAgentTaskLifecycleTest.RecordingTaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.workflow.graph.StateGraph.END;
import static io.github.agentic.ai.workflow.graph.StateGraph.START;
import static io.github.agentic.ai.workflow.graph.scheduling.ScheduleLifecycleListener.ScheduleEvent.STOPPED;
import static io.github.agentic.ai.workflow.graph.scheduling.ScheduledAgentTaskLifecycleTest.graphFailingFirstAttempts;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复现 #216：注册与 shutdown 必须原子互斥，通过锁外检查的注册不得滞留进已关闭的管理器。 */
class ScheduledAgentManagerShutdownMutexTest {

	private final ScheduledAgentManagerFactory factory = ScheduledAgentManagerFactory.getInstance();

	private final ScheduledAgentManager originalManager = factory.getManager();

	/** 恢复测试前的管理器，避免全局 provider 影响其他测试。 */
	@AfterEach
	void restoreOriginalManager() {
		factory.registerProvider(() -> originalManager);
	}

	/** 验证 shutdown 期间通过锁外检查的注册，在关闭完成后被锁内复查拒绝且不产生滞留记录。 */
	@Test
	void aRegistrationPassingTheCheckDuringShutdownCannotEnterActiveTasks() throws Exception {
		DefaultScheduledAgentManager manager = new DefaultScheduledAgentManager(new RecordingTaskScheduler());
		factory.registerProvider(() -> manager);
		CountDownLatch stopCallbackEntered = new CountDownLatch(1);
		CountDownLatch releaseStopCallback = new CountDownLatch(1);
		new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).addListener(new ScheduleLifecycleListener() {
					@Override
					public void onEvent(ScheduleEvent event, Object data) {
						if (event == STOPPED) {
							stopCallbackEntered.countDown();
							try {
								releaseStopCallback.await(10, TimeUnit.SECONDS);
							}
							catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
						}
					}
				}).build());
		assertEquals(1, manager.getActiveTaskCount());

		CountDownLatch nameRead = new CountDownLatch(1);
		CountDownLatch releaseName = new CountDownLatch(1);
		AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
		Thread shutdownThread = new Thread(manager::shutdown, "shutdown-thread");
		try {
			shutdownThread.start();
			// shutdown 持有写锁停在 stop 回调里，此刻 shutdown 标志仍为 false。
			assertTrue(stopCallbackEntered.await(5, TimeUnit.SECONDS),
					"shutdown must hold the write lock inside the stop callback");
			// registerTask 在锁外检查与加锁之间调用 task.getName()，用它做可控缝隙。
			Thread registrationThread = new Thread(() -> {
				try {
					new ScheduledAgentTask(graphWithNameGatedBy(nameRead, releaseName),
							ScheduleConfig.builder().initialDelay(0).build());
				}
				catch (Throwable throwable) {
					registrationFailure.set(throwable);
				}
			}, "registration-thread");
			registrationThread.start();
			assertTrue(nameRead.await(5, TimeUnit.SECONDS),
					"registration must pass the pre-lock check while shutdown is in progress");

			releaseStopCallback.countDown();
			shutdownThread.join(TimeUnit.SECONDS.toMillis(5));
			releaseName.countDown();
			registrationThread.join(TimeUnit.SECONDS.toMillis(5));

			assertAll(() -> assertFalse(shutdownThread.isAlive(), "shutdown must finish"),
					() -> assertFalse(registrationThread.isAlive(), "registration must finish"),
					() -> assertTrue(registrationFailure.get() instanceof IllegalStateException,
							"A registration racing with shutdown must be rejected, got: " + registrationFailure.get()),
					() -> assertEquals(0, manager.getActiveTaskCount(),
							"No task may be stranded in a shut-down manager's activeTasks"),
					() -> assertTrue(manager.isShutdown()));
		}
		finally {
			releaseStopCallback.countDown();
			releaseName.countDown();
			shutdownThread.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	/** 验证关闭完成后的注册被明确拒绝，且不留任何任务记录。 */
	@Test
	void aRegistrationAfterShutdownIsRejectedAndLeavesNoResidue() throws Exception {
		DefaultScheduledAgentManager manager = new DefaultScheduledAgentManager(new RecordingTaskScheduler());
		factory.registerProvider(() -> manager);
		new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		assertEquals(1, manager.getActiveTaskCount());

		manager.shutdown();
		assertThrows(IllegalStateException.class, () -> new ScheduledAgentTask(
				graphFailingFirstAttempts(new AtomicInteger(), 0), ScheduleConfig.builder().initialDelay(0).build()));

		assertAll(() -> assertTrue(manager.isShutdown()), () -> assertEquals(0, manager.getActiveTaskCount()));
	}

	/** 验证并发 stop 与 shutdown 保持幂等：结束后无残留记录，所有任务都已停止。 */
	@Test
	void concurrentStopsAndShutdownsRemainIdempotentWithoutResidue() throws Exception {
		DefaultScheduledAgentManager manager = new DefaultScheduledAgentManager(new RecordingTaskScheduler());
		factory.registerProvider(() -> manager);
		ScheduledAgentTask first = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		ScheduledAgentTask second = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		assertEquals(2, manager.getActiveTaskCount());

		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread[] workers = new Thread[] { new Thread(manager::shutdown, "shutdown-1"),
				new Thread(manager::shutdown, "shutdown-2"), new Thread(first::stop, "stop-1"),
				new Thread(first::stop, "stop-1-again"), new Thread(second::stop, "stop-2"),
				new Thread(second::stop, "stop-2-again") };
		for (Thread worker : workers) {
			worker.setUncaughtExceptionHandler((thread, throwable) -> failure.compareAndSet(null, throwable));
			worker.start();
		}
		for (Thread worker : workers) {
			worker.join(TimeUnit.SECONDS.toMillis(5));
		}

		assertAll(() -> assertEquals(null, failure.get(), "concurrent stop and shutdown must not throw"),
				() -> assertTrue(manager.isShutdown()),
				() -> assertEquals(0, manager.getActiveTaskCount(), "no task may survive shutdown"),
				() -> assertTrue(first.isStopped()), () -> assertTrue(second.isStopped()));
	}

	/**
	 * Builds a graph whose {@code getName()} is gated on the given latches, hooking the
	 * gap between the shutdown check and the write lock inside {@code registerTask}.
	 */
	private static CompiledGraph graphWithNameGatedBy(CountDownLatch entered, CountDownLatch release) throws Exception {
		Map<String, KeyStrategy> strategies = new HashMap<>();
		strategies.put("result", (left, right) -> right);
		StateGraph graph = new StateGraph("name-gated-registration", () -> strategies) {

			@Override
			public String getName() {
				entered.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "name-gated-registration";
			}

		};
		graph.addNode("attempt", AsyncNodeAction.node_async(state -> Map.of("result", "ok")))
			.addEdge(START, "attempt")
			.addEdge("attempt", END);
		return graph.compile();
	}

}
