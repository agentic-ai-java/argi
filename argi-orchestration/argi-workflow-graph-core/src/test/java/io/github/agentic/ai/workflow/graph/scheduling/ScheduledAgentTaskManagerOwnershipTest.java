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

import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.ai.workflow.graph.scheduling.ScheduledAgentTaskLifecycleTest.RecordingScheduledAgentManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.workflow.graph.scheduling.ScheduleLifecycleListener.ScheduleEvent.EXECUTION_STARTED;
import static io.github.agentic.ai.workflow.graph.scheduling.ScheduledAgentTaskLifecycleTest.graphFailingFirstAttempts;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复现 #214：切换全局管理器后，任务仍应向注册它的管理器注销。 */
class ScheduledAgentTaskManagerOwnershipTest {

	private final ScheduledAgentManagerFactory factory = ScheduledAgentManagerFactory.getInstance();

	private final ScheduledAgentManager originalManager = factory.getManager();

	private final RecordingScheduledAgentManager owner = new RecordingScheduledAgentManager();

	private final RecordingScheduledAgentManager replacement = new RecordingScheduledAgentManager();

	/** 恢复测试前的管理器，避免全局 provider 影响其他测试。 */
	@AfterEach
	void restoreOriginalManager() {
		factory.registerProvider(() -> originalManager);
	}

	/** 验证切换管理器后重复停止任务，只向原管理器注销一次。 */
	@Test
	void repeatedStopAfterProviderSwitchUnregistersOnlyFromOwner() throws Exception {
		factory.registerProvider(() -> owner);
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().fixedDelay(1000).initialDelay(0).build()).start();
		assertSame(task, owner.getTask(task.getTaskId()).orElseThrow());
		factory.registerProvider(() -> replacement);

		task.stop();
		task.stop();

		assertTrue(task.isStopped());
		assertUnregisteredOnlyFromOwner(task);
	}

	/** 验证一次性任务完成以及随后重复停止，都不会向新管理器注销。 */
	@Test
	void oneTimeCompletionAfterProviderSwitchUnregistersOnlyFromOwner() throws Exception {
		factory.registerProvider(() -> owner);
		final AtomicInteger executions = new AtomicInteger();
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(executions, 0),
				ScheduleConfig.builder().initialDelay(0).build()).start();
		factory.registerProvider(() -> replacement);

		owner.scheduler.runNext();
		task.stop();
		task.stop();

		assertEquals(1, executions.get());
		assertUnregisteredOnlyFromOwner(task);
	}

	/** 验证任务在 start 返回前立即执行并切换 provider 时，仍保留原管理器归属。 */
	@Test
	void immediateCompletionDuringProviderSwitchUnregistersOnlyFromOwner() throws Exception {
		factory.registerProvider(() -> owner);
		owner.scheduler.runOneTimeTasksImmediately();
		final AtomicInteger executions = new AtomicInteger();
		final ScheduledAgentTask task = new ScheduledAgentTask(graphFailingFirstAttempts(executions, 0),
				ScheduleConfig.builder().initialDelay(0).addListener(new ScheduleLifecycleListener() {
					@Override
					public void onEvent(ScheduleEvent event, Object data) {
						if (event == EXECUTION_STARTED) {
							factory.registerProvider(() -> replacement);
						}
					}
				}).build()).start();

		task.stop();

		assertEquals(1, executions.get());
		assertSame(replacement, factory.getManager());
		assertUnregisteredOnlyFromOwner(task);
	}

	/** 验证两个管理器分配相同 taskId 时，停止旧任务不会误删新管理器中的任务。 */
	@Test
	void stoppingOldTaskDoesNotRemoveReplacementTaskWithSameId() throws Exception {
		factory.registerProvider(() -> owner);
		final ScheduledAgentTask oldTask = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		factory.registerProvider(() -> replacement);
		final ScheduledAgentTask newTask = new ScheduledAgentTask(graphFailingFirstAttempts(new AtomicInteger(), 0),
				ScheduleConfig.builder().initialDelay(0).build());
		assertEquals(oldTask.getTaskId(), newTask.getTaskId());
		assertSame(newTask, replacement.getTask(newTask.getTaskId()).orElseThrow());

		oldTask.stop();
		oldTask.stop();

		assertAll(() -> assertUnregisteredOnlyFromOwner(oldTask),
				() -> assertSame(newTask, replacement.getTask(newTask.getTaskId()).orElse(null),
						"Stopping the old task must preserve the replacement manager's task"));
	}

	/** 验证注册失败不会遗留任务、关闭管理器，或影响新管理器后续注册和停止任务。 */
	@Test
	void registrationFailureDoesNotAffectReplacementManager() throws Exception {
		factory.registerProvider(() -> owner);
		owner.rejectRegistrations();
		final var graph = graphFailingFirstAttempts(new AtomicInteger(), 0);
		assertThrows(IllegalStateException.class,
				() -> new ScheduledAgentTask(graph, ScheduleConfig.builder().initialDelay(0).build()));
		assertEquals(0, owner.getActiveTaskCount());
		factory.registerProvider(() -> replacement);
		final ScheduledAgentTask task = new ScheduledAgentTask(graph,
				ScheduleConfig.builder().initialDelay(0).build());
		assertSame(task, replacement.getTask(task.getTaskId()).orElseThrow());

		task.stop();
		task.stop();

		assertAll(() -> assertEquals(0, owner.getActiveTaskCount()),
				() -> assertEquals(0, owner.unregisterCount(task.getTaskId())),
				() -> assertEquals(0, replacement.getActiveTaskCount()),
				() -> assertEquals(1, replacement.unregisterCount(task.getTaskId())),
				() -> assertEquals(0, owner.shutdownCount()), () -> assertEquals(0, replacement.shutdownCount()));
	}

	/** 检查原管理器已清理任务、新管理器没有收到注销请求，且外部管理器未被关闭。 */
	private void assertUnregisteredOnlyFromOwner(ScheduledAgentTask task) {
		assertAll(() -> assertTrue(owner.getTask(task.getTaskId()).isEmpty(), "Owner must release the task"),
				() -> assertEquals(1, owner.unregisterCount(task.getTaskId()), "Owner must unregister exactly once"),
				() -> assertEquals(0, replacement.unregisterCount(task.getTaskId()),
						"Replacement manager must not receive the old task's unregister request"),
				() -> assertEquals(0, owner.shutdownCount()), () -> assertEquals(0, replacement.shutdownCount()));
	}

}
