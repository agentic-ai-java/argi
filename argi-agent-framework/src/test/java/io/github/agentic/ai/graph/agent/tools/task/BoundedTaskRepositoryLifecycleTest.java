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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Timeout(15)
class BoundedTaskRepositoryLifecycleTest {

	@Test
	void closeCancelsWaitingTasksAndTerminatesOwnedResources() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService closer = Executors.newSingleThreadExecutor();
		BoundedTaskRepository repository = new BoundedTaskRepository(1, 1, 2, Duration.ofMinutes(1));
		ExecutorService owned = executor(repository);
		AtomicInteger queuedCalls = new AtomicInteger();
		try {
			BackgroundTask running = repository.putTask("running", () -> blocked(entered, release));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			BackgroundTask waiting = repository.putTask("waiting", () -> "bad-" + queuedCalls.incrementAndGet());
			Future<?> closed = closer.submit(repository::close);
			assertThat(waiting.waitForCompletion(5000)).isTrue();
			assertThat(waiting.getError()).isInstanceOf(java.util.concurrent.CancellationException.class);
			assertThatThrownBy(() -> repository.putTask("late", () -> "late"))
				.isInstanceOf(RejectedExecutionException.class);
			release.countDown();
			closed.get(5, TimeUnit.SECONDS);
			assertThat(running.waitForCompletion(5000)).isTrue();
			assertThat(queuedCalls).hasValue(0);
			assertThat(owned.isShutdown()).isTrue();
			assertThat(owned.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			assertThat(cleaner(repository).awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			repository.close();
		}
		finally {
			release.countDown();
			repository.close();
			closer.shutdownNow();
		}
	}

	@Test
	void closeLeavesTheCallerExecutorAndItsRunningSupplierAlive() throws Exception {
		ExecutorService shared = Executors.newFixedThreadPool(2);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(shared, 1, 1, 1, Duration.ofMinutes(1))) {
			try {
				BackgroundTask running = repository.putTask("running", () -> blocked(entered, release));
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				BackgroundTask waiting = repository.putTask("waiting", () -> "must not execute");
				repository.close();
				assertThat(shared.isShutdown()).isFalse();
				assertThat(shared.submit(() -> "unrelated").get(5, TimeUnit.SECONDS)).isEqualTo("unrelated");
				assertThat(waiting.isCompleted()).isTrue();
				assertThat(running.isCompleted()).isFalse();
				assertThat(cleaner(repository).awaitTermination(5, TimeUnit.SECONDS)).isTrue();
				release.countDown();
				assertThat(running.waitForCompletion(5000)).isTrue();
				assertThat(running.getResult()).isEqualTo("done");
			}
			finally {
				release.countDown();
				shared.shutdownNow();
			}
		}
	}

	@Test
	void springContextDestructionClosesRepositoriesButNotTheBorrowedExecutor() throws Exception {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Beans.class);
		ExecutorService shared = context.getBean("sharedExecutor", ExecutorService.class);
		BoundedTaskRepository ownedRepository = context.getBean("ownedRepository", BoundedTaskRepository.class);
		BoundedTaskRepository borrowedRepository = context.getBean("borrowedRepository", BoundedTaskRepository.class);
		ExecutorService owned = executor(ownedRepository);
		try {
			assertThat(ownedRepository.putTask("owned", () -> "owned").waitForCompletion(5000)).isTrue();
			assertThat(borrowedRepository.putTask("borrowed", () -> "borrowed").waitForCompletion(5000)).isTrue();
			context.close();
			assertThat(owned.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			assertThat(cleaner(ownedRepository).awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			assertThat(cleaner(borrowedRepository).awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			assertThat(shared.isShutdown()).isFalse();
			assertThat(shared.submit(() -> "still usable").get(5, TimeUnit.SECONDS)).isEqualTo("still usable");
			assertThatThrownBy(() -> ownedRepository.putTask("late", () -> "late"))
				.isInstanceOf(RejectedExecutionException.class);
			assertThatThrownBy(() -> borrowedRepository.putTask("late", () -> "late"))
				.isInstanceOf(RejectedExecutionException.class);
		}
		finally {
			context.close();
			shared.shutdownNow();
		}
	}

	@Test
	void timeoutShutdownCompletesSubmittedWrappersWhichNeverStarted() throws Exception {
		ExecutorService owned = mock(ExecutorService.class);
		List<Runnable> submitted = new ArrayList<>();
		doAnswer(invocation -> {
			submitted.add(invocation.getArgument(0, Runnable.class));
			return null;
		}).when(owned).execute(any(Runnable.class));
		AtomicInteger calls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 1, 1, Duration.ofMinutes(1), owned, true,
				System::nanoTime, null)) {
			BackgroundTask submittedTask = repository.putTask("submitted", () -> "bad-" + calls.incrementAndGet());
			BackgroundTask queued = repository.putTask("queued", () -> "bad-" + calls.incrementAndGet());
			repository.close();
			verify(owned).shutdown();
			verify(owned).awaitTermination(5, TimeUnit.SECONDS);
			verify(owned).shutdownNow();
			assertThat(submittedTask.isCompleted()).isTrue();
			assertThat(queued.isCompleted()).isTrue();
			submitted.get(0).run();
			assertThat(calls).hasValue(0);
		}
	}

	@Test
	void interruptedCloseRequestsOwnedExecutorShutdownAndRestoresTheInterruptFlag() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		BoundedTaskRepository repository = new BoundedTaskRepository(1, 0, 1, Duration.ofMinutes(1));
		try {
			BackgroundTask task = repository.putTask("running", () -> blocked(entered, release));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			Thread.currentThread().interrupt();
			repository.close();
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			Thread.interrupted();
			assertThat(executor(repository).awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			assertThat(task.waitForCompletion(5000)).isTrue();
			assertThat(task.getError()).isInstanceOf(IllegalStateException.class);
		}
		finally {
			Thread.interrupted();
			release.countDown();
			repository.close();
		}
	}

	@Test
	void concurrentSubmissionCompletionAndRepeatedCloseLeaveNoPendingHandles() throws Exception {
		ExecutorService shared = Executors.newFixedThreadPool(2);
		ExecutorService callers = Executors.newFixedThreadPool(4);
		try {
			for (int round = 0; round < 10; round++) {
				try (BoundedTaskRepository repository = new BoundedTaskRepository(shared, 2, 8, 4,
						Duration.ofMinutes(1))) {
					List<BackgroundTask> accepted = Collections.synchronizedList(new ArrayList<>());
					CountDownLatch start = new CountDownLatch(1);
					List<Future<?>> operations = new ArrayList<>();
					accepted.add(repository.putTask("before", () -> "before"));
					for (int caller = 0; caller < 3; caller++) {
						int prefix = caller;
						operations.add(callers.submit(() -> {
							await(start);
							for (int id = 0; id < 20; id++) {
								try {
									accepted.add(repository.putTask(prefix + "-" + id, () -> "done"));
								}
								catch (RejectedExecutionException expected) {
									// Capacity or close won the admission race.
								}
							}
						}));
					}
					operations.add(callers.submit(() -> {
						await(start);
						repository.close();
						repository.close();
					}));
					start.countDown();
					for (Future<?> operation : operations) {
						operation.get(5, TimeUnit.SECONDS);
					}
					for (BackgroundTask task : accepted) {
						assertThat(task.waitForCompletion(5000)).isTrue();
					}
					assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(0);
					assertThat(shared.isShutdown()).isFalse();
				}
			}
		}
		finally {
			callers.shutdownNow();
			shared.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Latch was not released");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static String blocked(CountDownLatch entered, CountDownLatch release) {
		entered.countDown();
		await(release);
		return "done";
	}

	private static ExecutorService executor(BoundedTaskRepository repository) {
		return (ExecutorService) ReflectionTestUtils.getField(repository, "executor");
	}

	private static ExecutorService cleaner(BoundedTaskRepository repository) {
		return (ExecutorService) ReflectionTestUtils.getField(repository, "cleanupExecutor");
	}

	@Configuration(proxyBeanMethods = false)
	static class Beans {

		@Bean(destroyMethod = "")
		ExecutorService sharedExecutor() {
			return Executors.newSingleThreadExecutor();
		}

		@Bean(destroyMethod = "close")
		BoundedTaskRepository ownedRepository() {
			return new BoundedTaskRepository(1, 1, 2, Duration.ofMinutes(1));
		}

		@Bean(destroyMethod = "close")
		BoundedTaskRepository borrowedRepository(ExecutorService sharedExecutor) {
			return new BoundedTaskRepository(sharedExecutor, 1, 1, 2, Duration.ofMinutes(1));
		}

	}

}
