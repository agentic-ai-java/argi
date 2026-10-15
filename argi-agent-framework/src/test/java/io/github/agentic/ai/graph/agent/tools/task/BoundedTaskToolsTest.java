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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.graph.agent.ReactAgent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(15)
class BoundedTaskToolsTest {

	private static final ToolContext CONTEXT = new ToolContext(Map.of());

	@Test
	void existingBuilderSharesTheExplicitRepositoryBetweenTaskAndTaskOutput() throws Exception {
		ReactAgent agent = mock(ReactAgent.class);
		when(agent.call(anyString())).thenReturn(ChatMessage.assistant("done"));
		try (BoundedTaskRepository repository = new BoundedTaskRepository(inlineExecutor(), 1, 0, 2,
				Duration.ofMinutes(1))) {
			List<Tool> tools = TaskToolsBuilder.builder().taskRepository(repository).subAgent("Explore", agent).build();
			ToolCall launch = new ToolCall("launch", "Task",
					"{\"description\":\"test\",\"prompt\":\"test\",\"subagent_type\":\"Explore\",\"run_in_background\":true}");
			String started = tools.get(0)
				.execute(launch, CONTEXT)
				.toCompletableFuture()
				.join()
				.toMessage(launch)
				.text();
			String id = started.substring("task_id: ".length(), started.indexOf(System.lineSeparator()));
			assertThat(repository.getTask(id).getResult()).isEqualTo("done");
			ToolCall query = new ToolCall("query", "TaskOutput", "{\"task_id\":\"" + id + "\",\"block\":false}");
			assertThat(tools.get(1).execute(query, CONTEXT).toCompletableFuture().join().toMessage(query).text())
				.isEqualTo("Task ID: " + id + "\nStatus: Completed\n\nResult:\ndone");
		}
	}

	@Test
	void capacityRejectionUsesTheExistingFailedStageWithoutExecutingTheAgent() throws Exception {
		ExecutorService stalled = mock(ExecutorService.class);
		AtomicInteger calls = new AtomicInteger();
		ReactAgent agent = mock(ReactAgent.class);
		when(agent.call(anyString())).thenAnswer(invocation -> {
			calls.incrementAndGet();
			return ChatMessage.assistant("must not execute");
		});
		try (BoundedTaskRepository repository = new BoundedTaskRepository(stalled, 1, 0, 1, Duration.ofMinutes(1))) {
			repository.putTask("occupy", () -> "occupy");
			Tool task = TaskToolsBuilder.builder().taskRepository(repository).subAgent("Explore", agent).build().get(0);
			ToolCall call = new ToolCall("launch", "Task",
					"{\"description\":\"test\",\"prompt\":\"test\",\"subagent_type\":\"Explore\",\"run_in_background\":true}");
			assertThatThrownBy(() -> task.execute(call, CONTEXT).toCompletableFuture().join())
				.isInstanceOf(CompletionException.class)
				.hasCauseInstanceOf(RejectedExecutionException.class);
			assertThat(calls).hasValue(0);
		}
	}

	@Test
	void oldAndNewRepositoriesProduceTheSameExistingOutputFormats() throws Exception {
		DefaultTaskRepository legacy = new DefaultTaskRepository(inlineExecutor());
		try (BoundedTaskRepository bounded = new BoundedTaskRepository(inlineExecutor(), 1, 0, 3,
				Duration.ofMinutes(1))) {
			for (TaskRepository repository : List.of(legacy, bounded)) {
				repository.putTask("success", () -> "result");
				repository.putTask("failure", () -> {
					throw new IllegalStateException("failed");
				});
				TaskOutputTool output = new TaskOutputTool(repository);
				assertThat(output.apply(new TaskOutputTool.Request("success", false, 1L), CONTEXT))
					.isEqualTo("Task ID: success\nStatus: Completed\n\nResult:\nresult");
				assertThat(output.apply(new TaskOutputTool.Request("failure", false, 1L), CONTEXT))
					.isEqualTo("Task ID: failure\nStatus: Failed: failed\n\nError:\nfailed");
				assertThat(output.apply(new TaskOutputTool.Request("missing", false, 1L), CONTEXT))
					.isEqualTo("Error: No background task found with ID: missing");
			}
		}
	}

	@Test
	void existingConstructorsAndBuildersStillSelectTheLegacyRepository() {
		DefaultTaskRepository noArgs = new DefaultTaskRepository();
		DefaultTaskRepository borrowed = new DefaultTaskRepository(mock(ExecutorService.class));
		DefaultTaskRepository explicitOwnership = new DefaultTaskRepository(mock(ExecutorService.class), false);
		TaskRepository builderDefault = (TaskRepository) ReflectionTestUtils.getField(TaskToolsBuilder.builder(),
				"taskRepository");
		TaskRepository toolDefault = (TaskRepository) ReflectionTestUtils.getField(TaskTool.builder(),
				"taskRepository");
		try {
			for (TaskRepository repository : List.of(noArgs, builderDefault, toolDefault)) {
				assertThat(repository).isExactlyInstanceOf(DefaultTaskRepository.class);
				ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(repository, "executor");
				assertThat(executor.getCorePoolSize()).isZero();
				assertThat(executor.getMaximumPoolSize()).isEqualTo(Integer.MAX_VALUE);
				assertThat(executor.getQueue()).isInstanceOf(SynchronousQueue.class);
				assertThat(ReflectionTestUtils.getField(repository, "ownsExecutor")).isEqualTo(true);
			}
			assertThat(ReflectionTestUtils.getField(borrowed, "ownsExecutor")).isEqualTo(false);
			assertThat(ReflectionTestUtils.getField(explicitOwnership, "ownsExecutor")).isEqualTo(false);
		}
		finally {
			noArgs.shutdown();
			borrowed.shutdown();
			explicitOwnership.shutdown();
			((DefaultTaskRepository) builderDefault).shutdown();
			((DefaultTaskRepository) toolDefault).shutdown();
		}
	}

	@Test
	void legacyDefaultStillExecutesDuplicateIdsAndClearDoesNotCancelTheirHandles() throws Exception {
		DefaultTaskRepository repository = new DefaultTaskRepository();
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		try {
			BackgroundTask first = repository.putTask("same", () -> legacyBlocked(entered, release, "first"));
			BackgroundTask second = repository.putTask("same", () -> legacyBlocked(entered, release, "second"));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(repository.getTask("same")).isSameAs(second);
			assertThat(first.isCompleted()).isFalse();
			assertThat(second.isCompleted()).isFalse();
			repository.clear();
			assertThat(repository.getTask("same")).isNull();
			assertThat(first.isCompleted()).isFalse();
			assertThat(second.isCompleted()).isFalse();
			release.countDown();
			assertThat(first.waitForCompletion(5000)).isTrue();
			assertThat(second.waitForCompletion(5000)).isTrue();
			assertThat(first.getResult()).isEqualTo("first");
			assertThat(second.getResult()).isEqualTo("second");
		}
		finally {
			release.countDown();
			repository.shutdown();
		}
	}

	@Test
	void runningTaskOutputKeepsItsExistingFormat() {
		ExecutorService stalled = mock(ExecutorService.class);
		DefaultTaskRepository legacy = new DefaultTaskRepository(stalled);
		try (BoundedTaskRepository bounded = new BoundedTaskRepository(stalled, 1, 0, 1, Duration.ofMinutes(1))) {
			for (TaskRepository repository : List.of(legacy, bounded)) {
				repository.putTask("running", () -> "not yet executed");
				assertThat(
						new TaskOutputTool(repository).apply(new TaskOutputTool.Request("running", false, 1L), CONTEXT))
					.isEqualTo("Task ID: running\nStatus: Running\n\nTask still running...");
			}
		}
	}

	private static ExecutorService inlineExecutor() {
		ExecutorService executor = mock(ExecutorService.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		return executor;
	}

	private static String legacyBlocked(CountDownLatch entered, CountDownLatch release, String result) {
		entered.countDown();
		try {
			if (!release.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Task was not released");
			}
			return result;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
