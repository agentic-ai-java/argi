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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Timeout(15)
class BoundedTaskRepositoryTest {

	@Test
	@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void rejectsBeforeCallingSupplierWhenRunningAndWaitingCapacityAreFull() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger queuedCalls = new AtomicInteger();
		AtomicInteger rejectedCalls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 1, 2, Duration.ofMinutes(1))) {
			try {
				BackgroundTask running = repository.putTask("running", () -> blocked(entered, release));
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				BackgroundTask queued = repository.putTask("queued", () -> "queued-" + queuedCalls.incrementAndGet());
				Deque<?> waiting = (Deque<?>) ReflectionTestUtils.getField(repository, "waiting");
				assertThat(waiting).hasSize(1);
				assertThat(waiting.peekFirst()).isSameAs(trackedTasks(repository).get("queued"));
				assertThat(repository.getTask("queued")).isSameAs(queued);
				assertThat(queued.isCompleted()).isFalse();
				assertThat(running.isCompleted()).isFalse();
				assertPromptlyRejected(repository, "rejected", () -> "bad-" + rejectedCalls.incrementAndGet());
				assertThat(release.getCount()).isEqualTo(1);
				assertThat(running.isCompleted()).isFalse();
				assertThat(waiting).hasSize(1);
				assertThat(queuedCalls).hasValue(0);
				assertThat(rejectedCalls).hasValue(0);
				release.countDown();
				assertThat(running.waitForCompletion(5000)).isTrue();
				assertThat(queued.waitForCompletion(5000)).isTrue();
				assertThat(queued.getResult()).isEqualTo("queued-1");
				assertThat(repository.getTask("rejected")).isNull();
			}
			finally {
				release.countDown();
			}
		}
	}

	@Test
	void acceptsExactlyOneConcurrentSubmissionForTheSameTaskId() throws Exception {
		ExecutorService callers = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 8, 2, Duration.ofMinutes(1))) {
			try {
				List<Future<Boolean>> submissions = new ArrayList<>();
				for (int i = 0; i < 8; i++) {
					submissions.add(callers.submit(() -> {
						assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
						try {
							repository.putTask("same", () -> {
								calls.incrementAndGet();
								return blocked(entered, release);
							});
							return true;
						}
						catch (RejectedExecutionException expected) {
							return false;
						}
					}));
				}
				start.countDown();
				int accepted = 0;
				for (Future<Boolean> submission : submissions) {
					if (submission.get(5, TimeUnit.SECONDS)) {
						accepted++;
					}
				}
				assertThat(accepted).isEqualTo(1);
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(calls).hasValue(1);
			}
			finally {
				start.countDown();
				release.countDown();
				callers.shutdownNow();
			}
		}
	}

	@Test
	void cancellationDoesNotReleaseTheSlotWhileTheSupplierIsStillRunning() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger completedCalls = new AtomicInteger();
		AtomicLong time = new AtomicLong();
		ScheduledExecutorService cleaner = mock(ScheduledExecutorService.class);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 0, 1, Duration.ofNanos(100), null, true,
				time::get, cleaner)) {
			try {
				BackgroundTask running = repository.putTask("running", () -> {
					String result = blocked(entered, release);
					completedCalls.incrementAndGet();
					return result;
				});
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(running.cancel(true)).isTrue();
				assertThat(running.isCompleted()).isTrue();
				ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
				verify(cleaner).scheduleWithFixedDelay(tick.capture(), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS));
				time.set(10000);
				tick.getValue().run();
				assertThatThrownBy(() -> repository.putTask("another", () -> "must not execute"))
					.isInstanceOf(RejectedExecutionException.class);
				assertThatThrownBy(() -> repository.putTask("running", () -> "must not execute"))
					.isInstanceOf(RejectedExecutionException.class);
				assertThat(repository.getTask("running")).isSameAs(running);
				assertThat(completedCalls).hasValue(0);
				release.countDown();
				ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(repository, "executor");
				executor.submit(() -> "execution exited").get(5, TimeUnit.SECONDS);
				assertThat(completedCalls).hasValue(1);
				assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(0);
				BackgroundTask next = repository.putTask("after-exit", () -> "accepted");
				assertThat(next.waitForCompletion(5000)).isTrue();
				assertThat(next.getResult()).isEqualTo("accepted");
			}
			finally {
				release.countDown();
			}
		}
	}

	private static String blocked(CountDownLatch entered, CountDownLatch release) {
		entered.countDown();
		try {
			if (!release.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Task was not released");
			}
			return "done";
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void validatesConfigurationAndInputsBeforeAllocatingWork() {
		Duration ttl = Duration.ofMinutes(1);
		assertThatThrownBy(() -> new BoundedTaskRepository(0, 1, 1, ttl)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(-1, 1, 1, ttl)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(1, -1, 1, ttl)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(1, 1, -1, ttl)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(1, 1, 1, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(1, 1, 1, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(1, 1, 1, Duration.ofNanos(-1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(1, 1, 1, Duration.ofSeconds(Long.MAX_VALUE)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BoundedTaskRepository(null, 1, 0, 0, ttl))
			.isInstanceOf(NullPointerException.class);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(inlineExecutor(), 1, 0, 0, ttl)) {
			assertThatThrownBy(() -> repository.putTask(" ", () -> "unused"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> repository.putTask("null", null)).isInstanceOf(IllegalArgumentException.class);
			assertThat(repository.putTask("valid", () -> "valid").getResult()).isEqualTo("valid");
		}
	}

	@Test
	void boundsConcurrencyEvenWhenTheCallerExecutorHasMoreThreads() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 2, 100, 102,
				Duration.ofMinutes(1))) {
			try {
				List<BackgroundTask> tasks = new ArrayList<>();
				for (int i = 0; i < 102; i++) {
					tasks.add(repository.putTask("task-" + i, () -> {
						maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
						try {
							return blocked(entered, release);
						}
						finally {
							running.decrementAndGet();
						}
					}));
				}
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(running).hasValue(2);
				synchronized (ReflectionTestUtils.getField(repository, "monitor")) {
					assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "waiting")).hasSize(100);
					assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(2);
				}
				release.countDown();
				for (BackgroundTask task : tasks) {
					assertThat(task.waitForCompletion(5000)).isTrue();
					assertThat(task.getResult()).isEqualTo("done");
				}
				assertThat(maximum).hasValue(2);
				synchronized (ReflectionTestUtils.getField(repository, "monitor")) {
					assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "waiting")).isEmpty();
					assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "ready")).isEmpty();
					assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(0);
				}
			}
			finally {
				release.countDown();
				executor.shutdownNow();
			}
		}
	}

	@Test
	@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void submittedWrappersUseSlotsWhenTheExternalExecutorHasFewerThreads() throws Exception {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>());
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger rejectedCalls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 4, 1, 5, Duration.ofMinutes(1))) {
			try {
				List<BackgroundTask> accepted = new ArrayList<>();
				for (int i = 0; i < 4; i++) {
					accepted.add(repository.putTask("slot-" + i, () -> {
						calls.incrementAndGet();
						return blocked(entered, release);
					}));
				}
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(calls).hasValue(1);
				assertThat(executor.getQueue()).hasSize(3);
				assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(4);
				assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "waiting")).isEmpty();
				accepted.add(repository.putTask("waiting", () -> "waiting-" + calls.incrementAndGet()));
				assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "waiting")).hasSize(1);
				assertThat(executor.getQueue()).hasSize(3);
				assertPromptlyRejected(repository, "rejected", () -> "bad-" + rejectedCalls.incrementAndGet());
				assertThat(rejectedCalls).hasValue(0);
				assertThat(calls).hasValue(1);
				assertThat(executor.getQueue()).hasSize(3);
				release.countDown();
				for (BackgroundTask task : accepted) {
					assertThat(task.waitForCompletion(5000)).isTrue();
					assertThat(task.getError()).isNull();
				}
				assertThat(calls).hasValue(5);
			}
			finally {
				release.countDown();
				executor.shutdownNow();
			}
		}
	}

	@Test
	void rejectsDuplicateWaitingAndRetainedIdsWithoutReplacingTheirHandles() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		ExecutorService executor = collectingExecutor(commands);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 1, 1, 2, Duration.ofMinutes(1))) {
			BackgroundTask first = repository.putTask("first", () -> "first");
			BackgroundTask queued = repository.putTask("queued", () -> "queued");
			assertThatThrownBy(() -> repository.putTask("queued", () -> "replacement"))
				.isInstanceOf(RejectedExecutionException.class);
			assertThat(repository.getTask("queued")).isSameAs(queued);
			commands.get(0).run();
			commands.get(1).run();
			assertThat(first.getResult()).isEqualTo("first");
			assertThat(queued.getResult()).isEqualTo("queued");
			assertThatThrownBy(() -> repository.putTask("queued", () -> "replacement"))
				.isInstanceOf(RejectedExecutionException.class);
		}
	}

	@Test
	void evictsByCompletionOrderInsteadOfSubmissionOrder() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(collectingExecutor(commands), 2, 0, 1,
				Duration.ofMinutes(1))) {
			BackgroundTask first = repository.putTask("first", () -> "first");
			BackgroundTask second = repository.putTask("second", () -> "second");
			commands.get(1).run();
			assertThat(repository.getTask("first")).isSameAs(first);
			commands.get(0).run();
			assertThat(repository.getTask("first")).isSameAs(first);
			assertThat(repository.getTask("second")).isNull();
			assertThat(second.getResult()).isEqualTo("second");
		}
	}

	@Test
	void zeroRetentionStillCompletesSuccessfulNullAndFailedHandlesAndReleasesCapacity() {
		try (BoundedTaskRepository repository = new BoundedTaskRepository(inlineExecutor(), 1, 0, 0,
				Duration.ofMinutes(1))) {
			BackgroundTask success = repository.putTask("same", () -> "result");
			assertThat(success.getResult()).isEqualTo("result");
			assertThat(repository.getTask("same")).isNull();
			BackgroundTask empty = repository.putTask("same", () -> null);
			assertThat(empty.isCompleted()).isTrue();
			assertThat(empty.getResult()).isNull();
			assertThat(empty.getError()).isNull();
			BackgroundTask failed = repository.putTask("same", () -> {
				throw new IllegalStateException("failure");
			});
			assertThat(failed.getError()).isInstanceOf(IllegalStateException.class).hasMessage("failure");
			assertThat(repository.getTask("same")).isNull();
			assertThat(repository.putTask("same", () -> "recovered").getResult()).isEqualTo("recovered");
		}
	}

	@Test
	void retentionStartsAtExecutionExitAndExpiresAtTheExactBoundary() throws Exception {
		AtomicLong time = new AtomicLong();
		List<Runnable> commands = new ArrayList<>();
		ScheduledExecutorService cleaner = mock(ScheduledExecutorService.class);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 0, 1, Duration.ofNanos(100),
				collectingExecutor(commands), false, time::get, cleaner)) {
			BackgroundTask first = repository.putTask("same", () -> "first");
			time.set(1000);
			assertThat(repository.getTask("same")).isSameAs(first);
			commands.get(0).run();
			time.set(1099);
			assertThat(repository.getTask("same")).isSameAs(first);
			time.set(1100);
			assertThat(repository.getTask("same")).isNull();
			BackgroundTask replacement = repository.putTask("same", () -> "replacement");
			commands.get(1).run();
			assertThat(repository.getTask("same")).isSameAs(replacement);
			assertThat(first.getResult()).isEqualTo("first");
		}
	}

	@Test
	void scheduledCleanupReclaimsIdleResultsAndDiscardsObsoleteIndexEntries() {
		AtomicLong time = new AtomicLong();
		ScheduledExecutorService cleaner = mock(ScheduledExecutorService.class);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 1, 2, Duration.ofNanos(100),
				inlineExecutor(), false, time::get, cleaner)) {
			ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
			verify(cleaner).scheduleWithFixedDelay(tick.capture(), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS));
			repository.putTask("completed", () -> "done");
			assertThat(trackedTasks(repository)).hasSize(1);
			time.set(100);
			tick.getValue().run();
			assertThat(trackedTasks(repository)).isEmpty();
			for (int i = 0; i < 100; i++) {
				repository.putTask("same", () -> "done");
				repository.removeTask("same");
			}
			assertThat(trackedTasks(repository)).isEmpty();
			assertThat((Map<?, ?>) ReflectionTestUtils.getField(repository, "completed")).isEmpty();
			repository.putTask("same", () -> "replacement");
			tick.getValue().run();
			assertThat(repository.getTask("same").getResult()).isEqualTo("replacement");
		}
	}

	@Test
	void cleanupProtectsWaitingTasksAndCancelledWrappersWhichHaveNotExited() throws Exception {
		AtomicLong time = new AtomicLong();
		List<Runnable> commands = new ArrayList<>();
		ScheduledExecutorService cleaner = mock(ScheduledExecutorService.class);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 1, 1, Duration.ofNanos(100),
				collectingExecutor(commands), false, time::get, cleaner)) {
			ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
			verify(cleaner).scheduleWithFixedDelay(tick.capture(), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS));
			BackgroundTask submitted = repository.putTask("submitted", () -> "must not execute");
			BackgroundTask waiting = repository.putTask("waiting", () -> "waiting");
			assertThat(submitted.cancel(true)).isTrue();
			time.set(10000);
			tick.getValue().run();
			assertThat(repository.getTask("submitted")).isSameAs(submitted);
			assertThat(repository.getTask("waiting")).isSameAs(waiting);
			assertThatThrownBy(() -> repository.putTask("third", () -> "over capacity"))
				.isInstanceOf(RejectedExecutionException.class);
			commands.get(0).run();
			commands.get(1).run();
			assertThat(waiting.getResult()).isEqualTo("waiting");
		}
	}

	@Test
	void largeCapacitiesDoNotOverflowAdmissionArithmetic() {
		try (BoundedTaskRepository repository = new BoundedTaskRepository(inlineExecutor(), Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofMinutes(1))) {
			assertThat(repository.putTask("valid", () -> "valid").getResult()).isEqualTo("valid");
		}
	}

	@Test
	void explicitRemovalHidesActiveTasksButPreservesTheirIdsAndCapacity() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(collectingExecutor(commands), 1, 1, 2,
				Duration.ofMinutes(1))) {
			BackgroundTask first = repository.putTask("first", () -> "first");
			BackgroundTask queued = repository.putTask("queued", () -> "queued");
			repository.removeTask("first");
			assertThat(repository.getTask("first")).isNull();
			assertThatThrownBy(() -> repository.putTask("first", () -> "duplicate"))
				.isInstanceOf(RejectedExecutionException.class);
			repository.clear();
			assertThat(repository.getTask("queued")).isNull();
			assertThatThrownBy(() -> repository.putTask("third", () -> "over capacity"))
				.isInstanceOf(RejectedExecutionException.class);
			commands.get(0).run();
			commands.get(1).run();
			assertThat(first.getResult()).isEqualTo("first");
			assertThat(queued.getResult()).isEqualTo("queued");
			assertThat(repository.getTask("first")).isNull();
			assertThat(repository.getTask("queued")).isNull();
			BackgroundTask replacement = repository.putTask("first", () -> "replacement");
			commands.get(2).run();
			assertThat(repository.getTask("first")).isSameAs(replacement);
		}
	}

	@Test
	void removingARunningTaskOnlyAllowsIdReuseAfterItsSupplierExits() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(1, 1, 2, Duration.ofMinutes(1))) {
			try {
				BackgroundTask running = repository.putTask("same", () -> blocked(entered, release));
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				repository.removeTask("same");
				assertThat(repository.getTask("same")).isNull();
				assertThatThrownBy(() -> repository.putTask("same", () -> "duplicate"))
					.isInstanceOf(RejectedExecutionException.class);
				BackgroundTask waiting = repository.putTask("distinct", () -> "distinct");
				release.countDown();
				assertThat(running.waitForCompletion(5000)).isTrue();
				assertThat(waiting.waitForCompletion(5000)).isTrue();
				assertThat(repository.getTask("same")).isNull();
				BackgroundTask replacement = repository.putTask("same", () -> "replacement");
				assertThat(replacement.waitForCompletion(5000)).isTrue();
				assertThat(replacement.getResult()).isEqualTo("replacement");
			}
			finally {
				release.countDown();
			}
		}
	}

	@Test
	void removingACompletedResultAllowsImmediateIdReuse() {
		try (BoundedTaskRepository repository = new BoundedTaskRepository(inlineExecutor(), 1, 0, 1,
				Duration.ofMinutes(1))) {
			BackgroundTask original = repository.putTask("same", () -> "original");
			assertThatThrownBy(() -> repository.putTask("same", () -> "duplicate"))
				.isInstanceOf(RejectedExecutionException.class);
			repository.removeTask("same");
			assertThat(repository.getTask("same")).isNull();
			BackgroundTask replacement = repository.putTask("same", () -> "replacement");
			assertThat(replacement).isNotSameAs(original);
			assertThat(repository.getTask("same")).isSameAs(replacement);
			assertThat(original.getResult()).isEqualTo("original");
			assertThat(replacement.getResult()).isEqualTo("replacement");
		}
	}

	@Test
	void cancellingAWaitingTaskFreesQueueCapacityWithoutCallingItsSupplier() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		AtomicInteger calls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(collectingExecutor(commands), 1, 1, 3,
				Duration.ofMinutes(1))) {
			repository.putTask("first", () -> "first");
			BackgroundTask cancelled = repository.putTask("cancelled", () -> "bad-" + calls.incrementAndGet());
			assertThat(cancelled.cancel(true)).isTrue();
			BackgroundTask replacement = repository.putTask("replacement", () -> "replacement");
			commands.get(0).run();
			commands.get(1).run();
			assertThat(calls).hasValue(0);
			assertThat(replacement.getResult()).isEqualTo("replacement");
		}
	}

	@Test
	void cancellingAReadyTaskReleasesItsSlotWithoutSubmittingItsSupplier() {
		AtomicInteger cancelledCalls = new AtomicInteger();
		List<BackgroundTask> replacements = new ArrayList<>();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(inlineExecutor(), 2, 0, 0,
				Duration.ofMinutes(1))) {
			BackgroundTask root = repository.putTask("root", () -> {
				BackgroundTask ready = repository.putTask("ready", () -> "bad-" + cancelledCalls.incrementAndGet());
				synchronized (ReflectionTestUtils.getField(repository, "monitor")) {
					Deque<?> readyQueue = (Deque<?>) ReflectionTestUtils.getField(repository, "ready");
					assertThat(readyQueue).hasSize(1);
					assertThat(readyQueue.peekFirst()).isSameAs(trackedTasks(repository).get("ready"));
					assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(2);
				}
				assertThat(ready.cancel(true)).isTrue();
				assertThat(ready.isCompleted()).isTrue();
				assertThat(ready.getError()).isInstanceOf(java.util.concurrent.CancellationException.class);
				synchronized (ReflectionTestUtils.getField(repository, "monitor")) {
					assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "ready")).isEmpty();
					assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(1);
				}
				replacements.add(repository.putTask("replacement", () -> "replacement"));
				return "root";
			});
			assertThat(root.getResult()).isEqualTo("root");
			assertThat(cancelledCalls).hasValue(0);
			assertThat(replacements).hasSize(1);
			assertThat(replacements.get(0).getResult()).isEqualTo("replacement");
		}
	}

	@Test
	void cancellingASubmittedWrapperDoesNotFloodTheExternalExecutor() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		AtomicInteger calls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(collectingExecutor(commands), 1, 0, 1,
				Duration.ofMinutes(1))) {
			BackgroundTask cancelled = repository.putTask("cancelled", () -> "bad-" + calls.incrementAndGet());
			assertThat(cancelled.cancel(true)).isTrue();
			assertThatThrownBy(() -> repository.putTask("other", () -> "other"))
				.isInstanceOf(RejectedExecutionException.class);
			assertThat(commands).hasSize(1);
			commands.get(0).run();
			assertThat(calls).hasValue(0);
			BackgroundTask other = repository.putTask("other", () -> "other");
			commands.get(1).run();
			assertThat(other.getResult()).isEqualTo("other");
		}
	}

	@Test
	void executorRejectionRollsBackBothTheTaskIdAndItsSlot() throws Exception {
		ExecutorService executor = inlineExecutor();
		RejectedExecutionException rejection = new RejectedExecutionException("executor full");
		doThrow(rejection).doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		AtomicInteger calls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 1, 0, 1, Duration.ofMinutes(1))) {
			assertThatThrownBy(() -> repository.putTask("same", () -> "bad-" + calls.incrementAndGet()))
				.isSameAs(rejection);
			assertThat(calls).hasValue(0);
			assertThat(repository.getTask("same")).isNull();
			assertThat(repository.putTask("same", () -> "retry").getResult()).isEqualTo("retry");
		}
	}

	@Test
	void executorErrorRollsBackAdmissionAndAllowsLaterSubmission() {
		ExecutorService executor = inlineExecutor();
		AssertionError failure = new AssertionError("executor failed");
		doThrow(failure).doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		AtomicInteger calls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 1, 0, 1, Duration.ofMinutes(1))) {
			assertThatThrownBy(() -> repository.putTask("same", () -> "bad-" + calls.incrementAndGet()))
				.isSameAs(failure);
			assertThat(calls).hasValue(0);
			assertThat(repository.getTask("same")).isNull();
			assertThat(ReflectionTestUtils.getField(repository, "draining")).isEqualTo(false);
			assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(0);
			assertThat(repository.putTask("same", () -> "retry").getResult()).isEqualTo("retry");
		}
	}

	@Test
	void executorErrorDuringPromotionFailsOnlyThatTaskAndContinuesDispatch() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		ExecutorService executor = collectingExecutor(commands);
		AtomicInteger calls = new AtomicInteger();
		AssertionError failure = new AssertionError("executor failed during promotion");
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 1, 3, 4, Duration.ofMinutes(1))) {
			BackgroundTask first = repository.putTask("first", () -> "first");
			List<BackgroundTask> waiting = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				waiting.add(repository.putTask("queued-" + i, () -> "queued-" + calls.incrementAndGet()));
			}
			doThrow(failure).doAnswer(invocation -> {
				invocation.getArgument(0, Runnable.class).run();
				return null;
			}).when(executor).execute(any(Runnable.class));
			commands.get(0).run();
			assertThat(first.getResult()).isEqualTo("first");
			assertThat(waiting.get(0).isCompleted()).isTrue();
			assertThat(waiting.get(0).getError()).hasCause(failure);
			assertThat(waiting.get(1).getResult()).isEqualTo("queued-1");
			assertThat(waiting.get(2).getResult()).isEqualTo("queued-2");
			assertThat(calls).hasValue(2);
			verify(executor, times(4)).execute(any(Runnable.class));
			assertThat(ReflectionTestUtils.getField(repository, "draining")).isEqualTo(false);
			assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(0);
			assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "waiting")).isEmpty();
			assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "ready")).isEmpty();
			assertThat(repository.putTask("after-error", () -> "accepted").getResult()).isEqualTo("accepted");
		}
	}

	@Test
	void executorErrorLeavesOtherReadyWaitingAndSubmittedTasksRunnable() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		ExecutorService executor = collectingExecutor(commands);
		AssertionError failure = new AssertionError("executor failed with ready work");
		AtomicInteger calls = new AtomicInteger();
		List<BackgroundTask> pending = new ArrayList<>();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 3, 1, 0, Duration.ofMinutes(1))) {
			BackgroundTask submitted = repository.putTask("submitted", () -> "submitted");
			doAnswer(invocation -> {
				pending.add(repository.putTask("ready", () -> "ready-" + calls.incrementAndGet()));
				pending.add(repository.putTask("waiting", () -> "waiting-" + calls.incrementAndGet()));
				synchronized (ReflectionTestUtils.getField(repository, "monitor")) {
					assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "ready")).hasSize(1);
					assertThat((Deque<?>) ReflectionTestUtils.getField(repository, "waiting")).hasSize(1);
				}
				throw failure;
			}).doAnswer(invocation -> {
				commands.add(invocation.getArgument(0, Runnable.class));
				return null;
			}).when(executor).execute(any(Runnable.class));
			assertThatThrownBy(() -> repository.putTask("failed", () -> "bad-" + calls.incrementAndGet()))
				.isSameAs(failure);
			assertThat(repository.getTask("failed")).isNull();
			assertThat(pending).hasSize(2);
			for (BackgroundTask task : pending) {
				assertThat(task.isCompleted()).isFalse();
			}
			assertThat(calls).hasValue(0);
			assertThat(ReflectionTestUtils.getField(repository, "draining")).isEqualTo(false);
			assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(3);
			assertThat(submitted.isCompleted()).isFalse();
			assertThat(commands).hasSize(3);
			commands.get(0).run();
			commands.get(1).run();
			commands.get(2).run();
			assertThat(submitted.getResult()).isEqualTo("submitted");
			assertThat(pending.get(0).getResult()).isEqualTo("ready-1");
			assertThat(pending.get(1).getResult()).isEqualTo("waiting-2");
			assertThat(calls).hasValue(2);
			assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(0);
		}
	}

	@Test
	void executorErrorIsDeliveredToItsHandleInsteadOfAnotherSubmittingThread() throws Exception {
		ExecutorService executor = mock(ExecutorService.class);
		ExecutorService submitter = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Runnable> commands = new ArrayList<>();
		AtomicInteger rejectedCalls = new AtomicInteger();
		AssertionError failure = new AssertionError("another caller's submission failed");
		doAnswer(invocation -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			commands.add(invocation.getArgument(0, Runnable.class));
			return null;
		}).doThrow(failure).doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 2, 1, 3, Duration.ofMinutes(1))) {
			try {
				Future<BackgroundTask> submission = submitter.submit(() -> repository.putTask("owner", () -> "owner"));
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				BackgroundTask failed = repository.putTask("failed", () -> "bad-" + rejectedCalls.incrementAndGet());
				BackgroundTask queued = repository.putTask("queued", () -> "queued");
				release.countDown();
				BackgroundTask owner = submission.get(5, TimeUnit.SECONDS);
				assertThat(failed.isCompleted()).isTrue();
				assertThat(failed.getError()).hasCause(failure);
				assertThat(rejectedCalls).hasValue(0);
				assertThat(queued.getResult()).isEqualTo("queued");
				assertThat(owner.isCompleted()).isFalse();
				assertThat(commands).hasSize(1);
				commands.get(0).run();
				assertThat(owner.getResult()).isEqualTo("owner");
				assertThat(ReflectionTestUtils.getField(repository, "draining")).isEqualTo(false);
				assertThat(ReflectionTestUtils.getField(repository, "reservedSlots")).isEqualTo(0);
			}
			finally {
				release.countDown();
				submitter.shutdownNow();
			}
		}
	}

	@Test
	void executorRejectionDuringPromotionCompletesTheAlreadyReturnedHandle() throws Exception {
		List<Runnable> commands = new ArrayList<>();
		ExecutorService executor = collectingExecutor(commands);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(executor, 1, 1, 2, Duration.ofMinutes(1))) {
			repository.putTask("first", () -> "first");
			BackgroundTask queued = repository.putTask("queued", () -> "never executed");
			RejectedExecutionException rejection = new RejectedExecutionException("executor stopped");
			doThrow(rejection).when(executor).execute(any(Runnable.class));
			commands.get(0).run();
			assertThat(queued.isCompleted()).isTrue();
			assertThat(queued.getError()).isSameAs(rejection);
			assertThat(repository.getTask("queued")).isSameAs(queued);
			doAnswer(invocation -> {
				invocation.getArgument(0, Runnable.class).run();
				return null;
			}).when(executor).execute(any(Runnable.class));
			assertThat(repository.putTask("third", () -> "recovered").getResult()).isEqualTo("recovered");
		}
	}

	@Test
	void inlineExecutionDrainsAQueueWithoutRecursiveStackGrowth() {
		AtomicInteger calls = new AtomicInteger();
		try (BoundedTaskRepository repository = new BoundedTaskRepository(inlineExecutor(), 1, 2000, 0,
				Duration.ofMinutes(1))) {
			BackgroundTask root = repository.putTask("root", () -> {
				for (int i = 0; i < 2000; i++) {
					repository.putTask("child-" + i, () -> "child-" + calls.incrementAndGet());
				}
				return "root";
			});
			assertThat(root.getResult()).isEqualTo("root");
			assertThat(calls).hasValue(2000);
		}
	}

	private static Map<?, ?> trackedTasks(BoundedTaskRepository repository) {
		return (Map<?, ?>) ReflectionTestUtils.getField(repository, "tasks");
	}

	private static void assertPromptlyRejected(BoundedTaskRepository repository, String id, Supplier<String> supplier)
			throws Exception {
		CountDownLatch attempting = new CountDownLatch(1);
		ExecutorService caller = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "task-rejection-test");
			thread.setDaemon(true);
			return thread;
		});
		try {
			Future<?> attempt = caller.submit(() -> {
				attempting.countDown();
				assertThatThrownBy(() -> repository.putTask(id, supplier))
					.isInstanceOf(RejectedExecutionException.class);
			});
			assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
			attempt.get(1, TimeUnit.SECONDS);
		}
		finally {
			caller.shutdownNow();
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

	private static ExecutorService collectingExecutor(List<Runnable> commands) {
		ExecutorService executor = mock(ExecutorService.class);
		doAnswer(invocation -> {
			commands.add(invocation.getArgument(0, Runnable.class));
			return null;
		}).when(executor).execute(any(Runnable.class));
		return executor;
	}

}
