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
package io.github.agentic.ai.agent.studio.service;

import io.github.agentic.ai.agent.studio.dto.Thread;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadServiceImplTest {

	@Test
	void createThreadReturnsValuesWithMessagesFromInitialState() {
		ThreadServiceImpl service = new ThreadServiceImpl();
		Map<String, Object> firstMessage = Map.of("messageType", "user", "content", "hello");

		Thread thread = service.createThread("assistant", "alice",
				Map.of("messages", List.of(firstMessage), "topic", "demo"), "thread-1").block();

		assertThat(thread).isNotNull();
		Map<?, ?> values = thread.values();
		assertThat(values.get("messages")).isEqualTo(List.of(firstMessage));
		assertThat(values.get("topic")).isEqualTo("demo");
	}

	@Test
	void concurrentCreateThreadWithSameIdAllowsOnlyOneCreator() throws Exception {
		ThreadServiceImpl service = new ThreadServiceImpl();
		int workers = 32;
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		CountDownLatch ready = new CountDownLatch(workers);
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Boolean>> tasks = new ArrayList<>();

		for (int i = 0; i < workers; i++) {
			tasks.add(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				try {
					service.createThread("assistant", "alice", Map.of(), "shared-thread").block();
					return true;
				}
				catch (RuntimeException ex) {
					return false;
				}
			});
		}

		List<Future<Boolean>> results = tasks.stream().map(executor::submit).toList();
		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		long created = results.stream().filter(future -> {
			try {
				return future.get();
			}
			catch (Exception ex) {
				throw new AssertionError(ex);
			}
		}).count();
		executor.shutdownNow();

		assertThat(created).isEqualTo(1);
	}

	@Test
	void threadKeysCannotCollideWhenAppOrUserContainsSeparators() {
		ThreadServiceImpl service = new ThreadServiceImpl();

		service.createThread("a:b", "c", Map.of("owner", "first"), "shared-thread").block();
		service.createThread("a", "b:c", Map.of("owner", "second"), "shared-thread").block();

		Thread first = service.getThread("a:b", "c", "shared-thread", java.util.Optional.empty())
				.block()
				.orElseThrow();
		Thread second = service.getThread("a", "b:c", "shared-thread", java.util.Optional.empty())
				.block()
				.orElseThrow();

		assertThat(first.values()).containsEntry("owner", "first");
		assertThat(second.values()).containsEntry("owner", "second");
	}

	@Test
	void updateBlockedBeforeDeleteDoesNotLeaveOrphanState() throws Exception {
		ThreadServiceImpl service = new ThreadServiceImpl();
		service.createThread("assistant", "alice", Map.of("v", "initial"), "t").block();
		BlockingState update = new BlockingState(Map.of("v", "stale"));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> pending = executor.submit(() -> service.updateThreadState("assistant", "alice", "t", update));
			update.awaitCopyStarted();

			service.deleteThread("assistant", "alice", "t").block();
			update.release();
			pending.get(5, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(service.getThread("assistant", "alice", "t", Optional.empty()).block()).isEmpty();
		assertThat(service.getThreadState("assistant", "alice", "t")).isEmpty();
	}

	@Test
	void updateFromDeletedThreadDoesNotPolluteRecreatedThread() throws Exception {
		ThreadServiceImpl service = new ThreadServiceImpl();
		service.createThread("assistant", "alice", Map.of("v", "old"), "t").block();
		BlockingState update = new BlockingState(Map.of("v", "stale"));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> pending = executor.submit(() -> service.updateThreadState("assistant", "alice", "t", update));
			update.awaitCopyStarted();

			service.deleteThread("assistant", "alice", "t").block();
			service.createThread("assistant", "alice", Map.of("v", "new"), "t").block();
			update.release();
			pending.get(5, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		Thread recreated = service.getThread("assistant", "alice", "t", Optional.empty()).block().orElseThrow();
		assertThat(recreated.values()).containsEntry("v", "new");
		assertThat(service.getThreadState("assistant", "alice", "t")).containsEntry("v", "new");
	}

	@Test
	void updateAppliesToMetadataAndStateTogether() {
		ThreadServiceImpl service = new ThreadServiceImpl();
		service.createThread("assistant", "alice", Map.of("v", "initial"), "t").block();

		service.updateThreadState("assistant", "alice", "t", Map.of("v", "updated"));

		assertThat(service.getThreadState("assistant", "alice", "t")).containsEntry("v", "updated");
		assertThat(service.getThread("assistant", "alice", "t", Optional.empty()).block().orElseThrow().values())
			.containsEntry("v", "updated");
		assertThat(service.listThreads("assistant", "alice").block().threads())
			.singleElement()
			.satisfies(thread -> assertThat(thread.values()).containsEntry("v", "updated"));
	}

	@Test
	void updateOfMissingThreadDoesNotCreateState() {
		ThreadServiceImpl service = new ThreadServiceImpl();

		service.updateThreadState("assistant", "alice", "missing", Map.of("v", "x"));

		assertThat(service.getThread("assistant", "alice", "missing", Optional.empty()).block()).isEmpty();
		assertThat(service.getThreadState("assistant", "alice", "missing")).isEmpty();
	}

	@Test
	void concurrentCreateAndDeleteNeverLeaveStateWithoutThread() throws Exception {
		ThreadServiceImpl service = new ThreadServiceImpl();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 500; round++) {
				String id = "t-" + round;
				CyclicBarrier barrier = new CyclicBarrier(2);
				Future<?> create = executor.submit(() -> {
					barrier.await(5, TimeUnit.SECONDS);
					service.createThread("assistant", "alice", Map.of("v", "x"), id).block();
					return null;
				});
				Future<?> delete = executor.submit(() -> {
					barrier.await(5, TimeUnit.SECONDS);
					service.deleteThread("assistant", "alice", id).block();
					return null;
				});
				create.get(5, TimeUnit.SECONDS);
				delete.get(5, TimeUnit.SECONDS);

				boolean exists = service.getThread("assistant", "alice", id, Optional.empty()).block().isPresent();
				Map<String, Object> state = service.getThreadState("assistant", "alice", id);
				assertThat(state.isEmpty()).as("round %d", round).isEqualTo(!exists);
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void duplicateCreateIsRejectedAndKeepsOriginalState() {
		ThreadServiceImpl service = new ThreadServiceImpl();
		service.createThread("assistant", "alice", Map.of("v", "first"), "t").block();

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> service.createThread("assistant", "alice", Map.of("v", "second"), "t").block())
			.isInstanceOf(IllegalStateException.class);

		assertThat(service.getThreadState("assistant", "alice", "t")).containsEntry("v", "first");
	}

	/** State map whose copy blocks so a test can interleave other operations. */
	private static final class BlockingState extends AbstractMap<String, Object> {

		private final Map<String, Object> delegate;

		private final CountDownLatch copyStarted = new CountDownLatch(1);

		private final CountDownLatch released = new CountDownLatch(1);

		BlockingState(Map<String, Object> delegate) {
			this.delegate = delegate;
		}

		@Override
		public Set<Entry<String, Object>> entrySet() {
			copyStarted.countDown();
			try {
				assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
			}
			catch (InterruptedException ex) {
				java.lang.Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			return delegate.entrySet();
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public boolean isEmpty() {
			return delegate.isEmpty();
		}

		void awaitCopyStarted() throws InterruptedException {
			assertThat(copyStarted.await(5, TimeUnit.SECONDS)).isTrue();
		}

		void release() {
			released.countDown();
		}

	}

}
