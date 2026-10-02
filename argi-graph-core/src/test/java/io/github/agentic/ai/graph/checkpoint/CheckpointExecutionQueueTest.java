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
package io.github.agentic.ai.graph.checkpoint;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.context.ContextView;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class CheckpointExecutionQueueTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final RunnableConfig CONFIG = RunnableConfig.builder().threadId("execution-queue-test").build();

	@Test
	void supplierFailureReleasesLeaseAndStartsNextWaitingOperation() throws Exception {
		final MemorySaver saver = new MemorySaver();
		final Sinks.One<String> gate = Sinks.one();
		final CompletableFuture<List<String>> active = CheckpointExecutionQueue.serialize(saver, CONFIG,
				() -> gate.asMono().flux()).collectList().toFuture();
		final IllegalStateException failure = new IllegalStateException("supplier failed");
		final CompletableFuture<List<String>> failed = CheckpointExecutionQueue.<String>serialize(saver, CONFIG,
				() -> { throw failure; }).collectList().toFuture();
		final CompletableFuture<List<String>> following = CheckpointExecutionQueue.serialize(saver, CONFIG,
				() -> Flux.just("following")).collectList().toFuture();

		try {
			assertFalse(failed.isDone());
			assertFalse(following.isDone());
			assertEquals(Sinks.EmitResult.OK, gate.tryEmitValue("active"));
			assertEquals(List.of("active"), active.get(5, TimeUnit.SECONDS));
			assertSame(failure, assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS)).getCause());
			assertEquals(List.of("following"), following.get(5, TimeUnit.SECONDS));
		}
		finally {
			following.cancel(true);
			failed.cancel(true);
			active.cancel(true);
		}
	}

	@Test
	void publisherInitializationFailureDoesNotKeepLease() {
		final MemorySaver saver = new MemorySaver();
		final IllegalArgumentException failure = new IllegalArgumentException("initialization failed");
		final Flux<String> execution = CheckpointExecutionQueue.serialize(saver, CONFIG,
				() -> Flux.defer(() -> { throw failure; }));

		assertSame(failure, assertThrows(IllegalArgumentException.class, () -> execution.blockLast(TIMEOUT)));
		assertEquals("following", CheckpointExecutionQueue.serialize(saver, CONFIG, () -> Flux.just("following"))
			.blockLast(TIMEOUT));
	}

	@Test
	void sameKeyNestedOperationInheritsActiveLease() {
		final MemorySaver saver = new MemorySaver();
		final List<String> events = new ArrayList<>();
		final Flux<String> execution = CheckpointExecutionQueue.serialize(saver, CONFIG, () -> {
			events.add("outer");
			return CheckpointExecutionQueue.serialize(saver, CONFIG, () -> {
				events.add("inner");
				return Flux.just("nested-result");
			});
		});

		assertEquals("nested-result", execution.blockLast(TIMEOUT));
		assertEquals(List.of("outer", "inner"), events);
		assertEquals("following", CheckpointExecutionQueue.serialize(saver, CONFIG, () -> Flux.just("following"))
			.blockLast(TIMEOUT));
	}

	@Test
	void expiredReactorContextCannotBypassNewActiveOperation() throws Exception {
		final MemorySaver saver = new MemorySaver();
		final AtomicReference<ContextView> captured = new AtomicReference<>();
		CheckpointExecutionQueue.serialize(saver, CONFIG, () -> Flux.deferContextual(context -> {
			captured.set(context);
			return Flux.just("original");
		})).blockLast(TIMEOUT);
		assertNotNull(captured.get());

		final Sinks.One<String> gate = Sinks.one();
		final CompletableFuture<List<String>> active = CheckpointExecutionQueue.serialize(saver, CONFIG,
				() -> gate.asMono().flux()).collectList().toFuture();
		final AtomicBoolean entered = new AtomicBoolean();
		final CompletableFuture<List<String>> reused = CheckpointExecutionQueue.serialize(saver, CONFIG, () -> {
			entered.set(true);
			return Flux.just("reused");
		}).contextWrite(context -> context.putAll(captured.get())).collectList().toFuture();

		try {
			assertFalse(entered.get(), "The old context must not provide a live lease");
			assertFalse(reused.isDone());
			assertEquals(Sinks.EmitResult.OK, gate.tryEmitValue("active"));
			assertEquals(List.of("active"), active.get(5, TimeUnit.SECONDS));
			assertEquals(List.of("reused"), reused.get(5, TimeUnit.SECONDS));
			assertTrue(entered.get());
		}
		finally {
			reused.cancel(true);
			active.cancel(true);
		}
	}

	@Test
	void saverIdentityKeepsEqualSaverInstancesIndependent() {
		final EqualSaver firstSaver = new EqualSaver();
		final EqualSaver secondSaver = new EqualSaver();
		assertEquals(firstSaver, secondSaver);
		assertNotSame(firstSaver, secondSaver);
		final Disposable active = CheckpointExecutionQueue.serialize(firstSaver, CONFIG, Flux::<String>never).subscribe();
		try {
			assertEquals("independent", CheckpointExecutionQueue.serialize(secondSaver, CONFIG,
					() -> Flux.just("independent")).blockLast(TIMEOUT));
		}
		finally {
			active.dispose();
		}
	}

	@Test
	void racingGrantAndWaitingCancellationDoesNotLeakLease() throws Exception {
		final ExecutorService racers = Executors.newFixedThreadPool(2);
		try {
			for (int iteration = 0; iteration < 200; iteration++) {
				final MemorySaver saver = new MemorySaver();
				final Sinks.One<String> gate = Sinks.one();
				final AtomicInteger entries = new AtomicInteger();
				final Disposable active = CheckpointExecutionQueue.serialize(saver, CONFIG,
						() -> gate.asMono().flux()).subscribe();
				final Disposable waiting = CheckpointExecutionQueue.serialize(saver, CONFIG, () -> {
					entries.incrementAndGet();
					return Flux.<String>never();
				}).subscribe();
				try {
					final CyclicBarrier start = new CyclicBarrier(3);
					final Future<?> grant = racers.submit(() -> {
						await(start);
						assertEquals(Sinks.EmitResult.OK, gate.tryEmitValue("active"));
					});
					final Future<?> cancel = racers.submit(() -> {
						await(start);
						waiting.dispose();
					});
					start.await(5, TimeUnit.SECONDS);
					grant.get(5, TimeUnit.SECONDS);
					cancel.get(5, TimeUnit.SECONDS);

					assertTrue(entries.get() <= 1, "A waiting operation can start at most once");
					assertEquals("following", CheckpointExecutionQueue.serialize(saver, CONFIG,
							() -> Flux.just("following")).blockLast(TIMEOUT), "No lease may remain after the race");
				}
				finally {
					waiting.dispose();
					active.dispose();
				}
			}
		}
		finally {
			racers.shutdownNow();
			assertTrue(racers.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void longSynchronousQueueDrainsInFifoOrderWithoutRecursiveOverflow() {
		final MemorySaver saver = new MemorySaver();
		final Sinks.One<Integer> gate = Sinks.one();
		final CompletableFuture<List<Integer>> active = CheckpointExecutionQueue.serialize(saver, CONFIG,
				() -> gate.asMono().flux()).collectList().toFuture();
		final AtomicInteger next = new AtomicInteger();
		final List<CompletableFuture<List<Integer>>> waiting = new ArrayList<>();
		try {
			for (int index = 0; index < 10000; index++) {
				final int expected = index;
				waiting.add(CheckpointExecutionQueue.serialize(saver, CONFIG, () -> {
					assertEquals(expected, next.getAndIncrement(), "Queue admission must preserve subscription order");
					return Flux.just(expected);
				}).collectList().toFuture());
			}
			assertEquals(0, next.get());

			assertDoesNotThrow(() -> assertEquals(Sinks.EmitResult.OK, gate.tryEmitValue(-1)));
			assertTrue(active.isDone(), "The synchronous drain must also complete its original holder");
			assertEquals(List.of(-1), active.join());
			assertEquals(10000, next.get());
			for (int index = 0; index < waiting.size(); index++) {
				assertTrue(waiting.get(index).isDone(), "The synchronous drain must finish every queued operation");
				assertEquals(List.of(index), waiting.get(index).join());
			}
			assertEquals(10000, CheckpointExecutionQueue.serialize(saver, CONFIG, () -> Flux.just(10000)).blockLast(TIMEOUT));
		}
		finally {
			waiting.forEach(future -> future.cancel(true));
			active.cancel(true);
		}
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(5, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Race participants must start together", ex);
		}
	}

	private static final class EqualSaver implements BaseCheckpointSaver {

		private final MemorySaver delegate = new MemorySaver();

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			return delegate.get(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			return delegate.put(config, checkpoint);
		}

		@Override
		public Tag release(RunnableConfig config) throws Exception {
			return delegate.release(config);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof EqualSaver;
		}

		@Override
		public int hashCode() {
			return 1;
		}

	}

}
