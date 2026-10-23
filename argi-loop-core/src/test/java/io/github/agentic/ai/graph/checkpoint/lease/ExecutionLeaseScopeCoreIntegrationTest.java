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
package io.github.agentic.ai.graph.checkpoint.lease;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointExecutionQueue;
import io.github.agentic.ai.graph.checkpoint.CheckpointSnapshot;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemoryLeasedCheckpointSaver;
import io.github.agentic.ai.graph.internal.node.SubCompiledGraphNodeAction;
import io.github.agentic.ai.graph.state.StateSnapshot;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig.node_async;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.outputKeyToParent;
import static io.github.agentic.ai.graph.internal.node.ResumableSubGraphAction.resumeSubGraphId;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class ExecutionLeaseScopeCoreIntegrationTest {

	@Test
	void secondFacadeSharingBackendGetsBusyBeforeNodeActionStarts() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver firstFacade = new CountingLeasedSaver(backend);
		CountingLeasedSaver secondFacade = new CountingLeasedSaver(backend);
		AtomicInteger firstNodeCalls = new AtomicInteger();
		AtomicInteger secondNodeCalls = new AtomicInteger();
		CountDownLatch firstNodeStarted = new CountDownLatch(1);
		CompletableFuture<Map<String, Object>> unblockFirst = new CompletableFuture<>();
		CompiledGraph firstGraph = blockingGraph(firstFacade, firstNodeCalls, firstNodeStarted, unblockFirst);
		CompiledGraph secondGraph = immediateGraph(secondFacade, secondNodeCalls);
		RunnableConfig config = config("busy-before-node");

		CompletableFuture<Throwable> firstFailure = runGraphAsync(firstGraph, config);
		assertTrue(firstNodeStarted.await(5, TimeUnit.SECONDS));

		assertThrows(LeaseBusyException.class, () -> secondGraph.invoke(Map.of("value", "second"), config));

		assertEquals(1, firstNodeCalls.get());
		assertEquals(0, secondNodeCalls.get());
		assertEquals(1, secondFacade.acquireCalls.get());
		assertEquals(0, secondFacade.releaseCalls.get());

		unblockFirst.complete(Map.of("value", "first"));
		assertNull(firstFailure.get(5, TimeUnit.SECONDS));
		assertEquals(1, firstFacade.releaseCalls.get());
	}

	@Test
	void expiredOwnerCannotPersistNodeResultAfterNewOwnerTakesNamespace() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver staleFacade = new CountingLeasedSaver(backend);
		CountingLeasedSaver winnerFacade = new CountingLeasedSaver(backend);
		RunnableConfig config = config("expired-owner-write");
		AtomicInteger nodeCalls = new AtomicInteger();
		AtomicReference<Long> revisionBeforeRejectedWrite = new AtomicReference<>();
		CompiledGraph graph = graph(staleFacade, node_async((state, runConfig) -> {
			nodeCalls.incrementAndGet();
			clock.advance(Duration.ofSeconds(31));
			winnerFacade.acquireLease(config, UUID.randomUUID());
			revisionBeforeRejectedWrite.set(backend.getVersioned(config).revision());
			return Map.of("value", "stale");
		}));

		assertThrows(LeaseLostException.class, () -> graph.invoke(Map.of("value", "input"), config));

		assertEquals(1, nodeCalls.get());
		assertEquals(1, revisionBeforeRejectedWrite.get());
		CheckpointSnapshot snapshot = backend.getVersioned(config);
		assertEquals(1, snapshot.revision());
		assertEquals(START, snapshot.checkpoint().orElseThrow().getNodeId());
		assertFalse(staleFacade.ownerlessVersionPutUsed);
	}

	@Test
	void executionLeaseScopeCompletesWithoutWaitingForNeverEmittingLossSignal() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig config = config("normal-completion");

		String value = ExecutionLeaseScope.withLease(saver, config, scope -> Flux.just("done"))
			.single()
			.block(Duration.ofSeconds(2));

		assertEquals("done", value);
		assertEquals(1, saver.acquireCalls.get());
		assertEquals(0, saver.renewCalls.get());
		assertEquals(1, saver.releaseCalls.get());
	}

	@Test
	void cancellationRunsInnerFinallyBeforeReleasingLease() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig config = config("cancel-cleanup");
		CountDownLatch subscribed = new CountDownLatch(1);
		CountDownLatch cleanupFinished = new CountDownLatch(1);
		AtomicReference<ExecutionLeaseScope> scopeRef = new AtomicReference<>();

		Disposable subscription = ExecutionLeaseScope.withLease(saver, config, scope -> {
			scopeRef.set(scope);
			subscribed.countDown();
			return Flux.never().doFinally(signal -> {
				try {
					scope.assertActive();
					saver.putIfLeasedVersion(config, checkpoint("rewound", "rewound"), 0, scope.lease());
					saver.events.add("inner-rewind");
				}
				catch (Exception ex) {
					saver.events.add("inner-failed");
				}
				finally {
					cleanupFinished.countDown();
				}
			});
		}).subscribe();

		assertTrue(subscribed.await(5, TimeUnit.SECONDS));
		subscription.dispose();

		assertTrue(cleanupFinished.await(5, TimeUnit.SECONDS));
		assertEquals(List.of("inner-rewind", "release"), saver.events);
		assertEquals("rewound", backend.getVersioned(config).checkpoint().orElseThrow().getState().get("value"));
	}

	@Test
	void lossRejectsInnerFinallyRewindBeforeBestEffortRelease() {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig config = config("loss-cleanup");
		AtomicReference<ExecutionLeaseScope> scopeRef = new AtomicReference<>();

		assertThrows(LeaseLostException.class, () -> ExecutionLeaseScope.withLease(saver, config, scope -> {
			scopeRef.set(scope);
			LeaseLostException loss = new LeaseLostException(scope.lease().namespace(), scope.lease().ownerId(),
					scope.lease().fencingToken(), "forced loss");
			scope.invalidate(loss);
			return Flux.error(loss).doFinally(signal -> {
				try {
					scope.assertActive();
					saver.putIfLeasedVersion(config, checkpoint("bad-rewind", "bad-rewind"), 0, scope.lease());
					saver.events.add("inner-rewind");
				}
				catch (LeaseLostException ex) {
					saver.events.add("inner-rejected");
				}
				catch (Exception ex) {
					saver.events.add("inner-other-failure");
				}
			});
		}).blockLast(Duration.ofSeconds(2)));

		assertEquals(List.of("inner-rejected", "release"), saver.events);
		assertTrue(backend.getVersioned(config).checkpoint().isEmpty());
	}

	@Test
	void matchingNestedLeaseScopeReusesOwnerAndListenerFailuresDoNotBlockLossCallbacks() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				new LeaseOptions(Duration.ofMillis(100), Duration.ofMillis(25)), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig config = config("nested");
		AtomicInteger callbacks = new AtomicInteger();

		LeaseLostException loss = assertThrows(LeaseLostException.class,
				() -> ExecutionLeaseScope.withLease(saver, config, outer -> {
					outer.guard().onLoss(() -> {
						throw new IllegalStateException("listener boom");
					});
					outer.guard().onLoss(callbacks::incrementAndGet);
					return ExecutionLeaseScope.withLease(saver, config, inner -> {
						assertSame(outer, inner);
						clock.advance(Duration.ofMillis(101));
						return Flux.error(new LeaseLostException(inner.lease().namespace(), inner.lease().ownerId(),
								inner.lease().fencingToken(), "test loss"));
					});
				}).blockLast(Duration.ofSeconds(2)));

		assertEquals("test loss", loss.getReason());
		assertEquals(1, callbacks.get());
		assertEquals(1, saver.acquireCalls.get());
		assertEquals(1, saver.releaseCalls.get());
	}

	@Test
	void runnableConfigGuardIsRunLocalAndClearedAtPublicBoundaries() throws Exception {
		AtomicInteger guardCalls = new AtomicInteger();
		ExecutionGuard guard = new ExecutionGuard() {
			@Override
			public void assertActive() {
				guardCalls.incrementAndGet();
			}

			@Override
			public AutoCloseable onLoss(Runnable cancellation) {
				return () -> {
				};
			}
		};
		RunnableConfig config = RunnableConfig.builder()
			.threadId("guard")
			.executionGuard(guard)
			.addMetadata("visible", "yes")
			.build();

		assertSame(guard, config.executionGuard().orElseThrow());
		assertDoesNotThrow(config::assertExecutionActive);
		assertEquals(1, guardCalls.get());
		assertSame(guard, RunnableConfig.builder(config).clearContext().build().executionGuard().orElseThrow());
		assertNotSame(config, config.withoutExecutionGuard());
		assertTrue(config.withoutExecutionGuard().executionGuard().isEmpty());
		RunnableConfig stripped = config.withoutExecutionGuard();
		assertSame(stripped, stripped.withoutExecutionGuard());

		String json = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).writeValueAsString(config);

		assertFalse(json.contains("executionGuard"));
		assertFalse(config.toString().contains("executionGuard"));
	}

	@Test
	void stateSnapshotsStripRuntimeGuardFromReturnedConfig() {
		ExecutionGuard guard = new NoopGuard();
		RunnableConfig config = RunnableConfig.builder().threadId("snapshot").executionGuard(guard).build();
		Checkpoint checkpoint = Checkpoint.builder()
			.id("checkpoint")
			.nodeId("node")
			.nextNodeId(END)
			.state(Map.of("value", "snapshot"))
			.build();

		StateSnapshot snapshot = StateSnapshot.of(Map.of(), checkpoint, config,
				StateGraph.DEFAULT_JACKSON_SERIALIZER.stateFactory());

		assertTrue(snapshot.config().executionGuard().isEmpty());
		assertSame(guard, config.executionGuard().orElseThrow());
	}

	@Test
	void localDeadlineInvalidatesBlockedRenewAndLateAckCannotReviveScope() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				new LeaseOptions(Duration.ofMillis(100), Duration.ofMillis(10)), clock);
		BlockingRenewSaver saver = new BlockingRenewSaver(backend);
		RunnableConfig config = config("blocked-renew");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		Scheduler timer = Schedulers.newSingle("lease-deadline-test");
		Scheduler rpc = Schedulers.newSingle("lease-rpc-test");
		AtomicInteger callbacks = new AtomicInteger();
		try {
			ExecutionLeaseScope scope = new ExecutionLeaseScope(saver, config, lease, System.nanoTime(),
					System::nanoTime, timer, rpc);
			scope.guard().onLoss(callbacks::incrementAndGet);

			assertTrue(saver.renewStarted.await(5, TimeUnit.SECONDS));
			awaitLeaseLoss(scope);
			awaitCounter(callbacks, 1);

			saver.allowRenew.countDown();
			assertTrue(saver.renewReturned.await(5, TimeUnit.SECONDS));
			assertThrows(LeaseLostException.class, scope::assertActive);
			assertEquals(1, callbacks.get());
			assertEquals(0, backend.getVersioned(config).revision());
		}
		finally {
			saver.allowRenew.countDown();
			timer.dispose();
			rpc.dispose();
		}
	}

	@Test
	void heartbeatRenewalsDoNotAdvanceCheckpointRevision() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				new LeaseOptions(Duration.ofSeconds(1), Duration.ofMillis(50)), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig config = config("heartbeat-revision");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		Scheduler timer = Schedulers.newSingle("lease-heartbeat-test");
		Scheduler rpc = Schedulers.newSingle("lease-heartbeat-rpc-test");
		try {
			ExecutionLeaseScope scope = new ExecutionLeaseScope(saver, config, lease, System.nanoTime(),
					System::nanoTime, timer, rpc);
			awaitRenewCalls(saver, 1);
			assertEquals(0, backend.getVersioned(config).revision());
			scope.invalidate(new LeaseLostException(lease.namespace(), lease.ownerId(), lease.fencingToken(),
					"test complete"));
		}
		finally {
			timer.dispose();
			rpc.dispose();
		}
	}

	@Test
	void initialVersionedReadFailureReleasesLeaseBeforeNodeActionStarts() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		saver.versionedReadFailure = new IllegalStateException("initial read failed");
		AtomicInteger nodeCalls = new AtomicInteger();
		CompiledGraph graph = immediateGraph(saver, nodeCalls);
		RunnableConfig config = config("initial-read-failure");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> graph.invoke(Map.of("value", "input"), config));

		assertEquals("initial read failed", failure.getMessage());
		assertEquals(1, saver.acquireCalls.get());
		assertEquals(1, saver.releaseCalls.get());
		assertEquals(0, nodeCalls.get());
	}

	@Test
	void withLeaseIsColdAcrossSubscriptionsAndIndependentNamespacesGetDistinctOwners() {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig outerConfig = config("cold-outer");
		RunnableConfig innerConfig = config("cold-inner");
		Flux<String> flow = ExecutionLeaseScope.withLease(saver, outerConfig,
				outer -> ExecutionLeaseScope.withLease(saver, innerConfig, inner -> {
					assertNotSame(outer, inner);
					assertNotSame(outer.lease(), inner.lease());
					return Flux.just(outer.lease().namespace() + "/" + inner.lease().namespace());
				}));

		assertEquals("cold-outer/cold-inner", flow.single().block(Duration.ofSeconds(2)));
		assertEquals("cold-outer/cold-inner", flow.single().block(Duration.ofSeconds(2)));
		assertEquals(4, saver.acquireCalls.get());
		assertEquals(4, saver.releaseCalls.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	void leasedSubgraphResumeUpdateThenStreamUsesChildLeaseAndNoOwnerlessFallback() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig childConfig = config("leased-parent_subgraph_nested");
		ExecutionLease seedLease = backend.acquireLease(childConfig, UUID.randomUUID());
		backend.putIfLeasedVersion(childConfig, Checkpoint.builder()
			.nodeId(START)
			.nextNodeId("append")
			.state(Map.of("messages", List.of("seed")))
			.build(), 0, seedLease);
		assertTrue(backend.releaseLease(childConfig, seedLease));
		CompiledGraph child = childGraph(saver);
		SubCompiledGraphNodeAction action = new SubCompiledGraphNodeAction("nested", compileConfig(saver), child);
		RunnableConfig parentConfig = RunnableConfig.builder(config("leased-parent"))
			.addMetadata(resumeSubGraphId("nested"), true)
			.build();
		OverAllState parentState = new OverAllState(Map.of("messages", List.of("parent")));

		Flux<GraphResponse<NodeOutput>> childStream = (Flux<GraphResponse<NodeOutput>>) action.apply(parentState,
				parentConfig).get(5, TimeUnit.SECONDS).get(outputKeyToParent("nested"));
		childStream.collectList().block(Duration.ofSeconds(5));

		CheckpointSnapshot childSnapshot = backend.getVersioned(childConfig);
		assertEquals(1, saver.acquireCalls.get());
		assertEquals(1, saver.releaseCalls.get());
		assertFalse(saver.ownerlessVersionPutUsed);
		assertEquals(3, childSnapshot.revision());
		assertEquals(List.of("child"),
				childSnapshot.checkpoint().orElseThrow().getState().get("messages"));
	}

	@Test
	void queuedSubscriberWaitsForBackendReleaseBeforeSecondAcquire() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		BlockingFirstReleaseSaver saver = new BlockingFirstReleaseSaver(backend);
		RunnableConfig config = config("release-before-queue-grant");
		AtomicReference<Throwable> firstError = new AtomicReference<>();
		AtomicReference<Throwable> secondError = new AtomicReference<>();
		AtomicReference<String> secondValue = new AtomicReference<>();
		CountDownLatch firstComplete = new CountDownLatch(1);
		CountDownLatch secondComplete = new CountDownLatch(1);
		Flux<String> leasedTurn = CheckpointExecutionQueue.serialize(saver, config,
				() -> ExecutionLeaseScope.withLease(saver, config, scope -> Flux.just("done")));

		leasedTurn.subscribeOn(Schedulers.boundedElastic()).subscribe(ignored -> {
		}, firstError::set, firstComplete::countDown);
		assertTrue(saver.firstReleaseStarted.await(5, TimeUnit.SECONDS));
		leasedTurn.subscribeOn(Schedulers.boundedElastic())
			.subscribe(secondValue::set, secondError::set, secondComplete::countDown);

		try {
			assertFalse(saver.secondAcquireStarted.await(150, TimeUnit.MILLISECONDS),
					"second local turn must not acquire backend lease before first backend release finishes");
			assertEquals(1, saver.acquireCalls.get());
			assertEquals(1, firstComplete.getCount(),
					"first local turn must not complete before backend release finishes");
		}
		finally {
			saver.allowFirstRelease.countDown();
		}
		assertTrue(firstComplete.await(5, TimeUnit.SECONDS));
		assertTrue(secondComplete.await(5, TimeUnit.SECONDS));
		assertNull(firstError.get());
		assertNull(secondError.get());
		assertEquals("done", secondValue.get());
		assertEquals(2, saver.acquireCalls.get());
		assertEquals(2, saver.releaseCalls.get());
	}

	@Test
	void assertActiveDetectsExpiredDeadlineEvenIfWatchdogHasNotRun() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				new LeaseOptions(Duration.ofSeconds(1), Duration.ofMillis(500)), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig config = config("delayed-watchdog");
		AtomicLong now = new AtomicLong();
		Scheduler timer = Schedulers.newSingle("lease-delayed-watchdog-test");
		Scheduler rpc = Schedulers.newSingle("lease-delayed-watchdog-rpc-test");
		try {
			ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
			ExecutionLeaseScope scope = new ExecutionLeaseScope(saver, config, lease, 0L, now::get, timer, rpc);

			now.set(TimeUnit.SECONDS.toNanos(2));

			LeaseLostException loss = assertThrows(LeaseLostException.class, scope::assertActive);
			assertEquals("local lease deadline passed", loss.getReason());
		}
		finally {
			timer.dispose();
			rpc.dispose();
		}
	}

	@Test
	void normalCloseMakesGuardInactiveAndBlockedRenewAckCannotReviveIt() {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				new LeaseOptions(Duration.ofMillis(500), Duration.ofMillis(50)), clock);
		BlockingRenewSaver saver = new BlockingRenewSaver(backend);
		RunnableConfig config = config("close-during-renew");
		AtomicReference<ExecutionLeaseScope> scopeRef = new AtomicReference<>();

		String result = ExecutionLeaseScope.withLease(saver, config, scope -> {
			scopeRef.set(scope);
			return Mono.fromCallable(() -> {
				assertTrue(saver.renewStarted.await(5, TimeUnit.SECONDS));
				return "done";
			}).flux();
		}).single().block(Duration.ofSeconds(5));

		assertEquals("done", result);
		ExecutionLeaseScope scope = scopeRef.get();
		assertThrows(LeaseLostException.class, scope::assertActive);

		saver.allowRenew.countDown();
		assertTrue(awaitLatch(saver.renewReturned));
		assertThrows(LeaseLostException.class, scope::assertActive);
		assertEquals(1, saver.releaseCalls.get());
	}

	@Test
	void lossCallbacksCanBeRemovedAndLateRegistrationRunsExactlyOnce() throws Exception {
		MutableClock clock = new MutableClock();
		MemoryLeasedCheckpointSaver backend = new MemoryLeasedCheckpointSaver(StateGraph.DEFAULT_JACKSON_SERIALIZER,
				LeaseOptions.defaults(), clock);
		CountingLeasedSaver saver = new CountingLeasedSaver(backend);
		RunnableConfig config = config("callback-cleanup");
		ExecutionLease lease = saver.acquireLease(config, UUID.randomUUID());
		Scheduler timer = Schedulers.newSingle("lease-callback-test");
		Scheduler rpc = Schedulers.newSingle("lease-callback-rpc-test");
		AtomicInteger callbacks = new AtomicInteger();
		try {
			ExecutionLeaseScope scope = new ExecutionLeaseScope(saver, config, lease, System.nanoTime(),
					System::nanoTime, timer, rpc);
			AutoCloseable registration = scope.guard().onLoss(callbacks::incrementAndGet);
			registration.close();

			scope.invalidate(new LeaseLostException(lease.namespace(), lease.ownerId(), lease.fencingToken(),
					"forced loss"));

			assertEquals(0, callbacks.get());
			scope.guard().onLoss(callbacks::incrementAndGet).close();
			scope.invalidate(new LeaseLostException(lease.namespace(), lease.ownerId(), lease.fencingToken(),
					"duplicate loss"));
			assertEquals(1, callbacks.get());
		}
		finally {
			timer.dispose();
			rpc.dispose();
		}
	}

	private static CompiledGraph immediateGraph(LeasedCheckpointSaver saver, AtomicInteger nodeCalls) throws Exception {
		return graph(saver, node_async((state, config) -> {
			nodeCalls.incrementAndGet();
			return Map.of("value", "done");
		}));
	}

	private static CompiledGraph blockingGraph(LeasedCheckpointSaver saver, AtomicInteger nodeCalls,
			CountDownLatch nodeStarted, CompletableFuture<Map<String, Object>> result) throws Exception {
		return graph(saver, (state, config) -> {
			nodeCalls.incrementAndGet();
			nodeStarted.countDown();
			return result;
		});
	}

	private static CompiledGraph graph(LeasedCheckpointSaver saver,
			io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig action) throws Exception {
		return new StateGraph(() -> Map.of("value", (left, right) -> right))
			.addNode("node", action)
			.addEdge(START, "node")
			.addEdge("node", END)
			.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
	}

	private static CompiledGraph childGraph(LeasedCheckpointSaver saver) throws Exception {
		return new StateGraph(() -> Map.<String, KeyStrategy>of("messages", new AppendStrategy(false)))
			.addNode("append", (state, config) -> CompletableFuture.completedFuture(Map.of("messages", List.of("child"))))
			.addEdge(START, "append")
			.addEdge("append", END)
			.compile(compileConfig(saver));
	}

	private static CompileConfig compileConfig(BaseCheckpointSaver saver) {
		return CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build();
	}

	private static CompletableFuture<Throwable> runGraphAsync(CompiledGraph graph, RunnableConfig config) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				graph.stream(Map.of("value", "input"), config).blockLast(Duration.ofSeconds(10));
				return null;
			}
			catch (Throwable ex) {
				return ex;
			}
		});
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	private static Checkpoint checkpoint(String id, String value) {
		return Checkpoint.builder().id(id).nodeId(id).nextNodeId(END).state(Map.of("value", value)).build();
	}

	private static LeaseLostException awaitLeaseLoss(ExecutionLeaseScope scope) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			try {
				scope.assertActive();
			}
			catch (LeaseLostException ex) {
				return ex;
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Timed out waiting for lease loss");
			}
			Thread.onSpinWait();
		}
	}

	private static void awaitRenewCalls(CountingLeasedSaver saver, int expectedCalls) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (saver.renewCalls.get() < expectedCalls) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Timed out waiting for renew calls; calls=" + saver.renewCalls.get());
			}
			Thread.onSpinWait();
		}
	}

	private static void awaitCounter(AtomicInteger counter, int expectedValue) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (counter.get() < expectedValue) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Timed out waiting for counter; value=" + counter.get());
			}
			Thread.onSpinWait();
		}
	}

	private static boolean awaitLatch(CountDownLatch latch) {
		try {
			return latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static final class CountingLeasedSaver implements LeasedCheckpointSaver {

		private final LeasedCheckpointSaver delegate;

		private final AtomicInteger acquireCalls = new AtomicInteger();

		private final AtomicInteger renewCalls = new AtomicInteger();

		private final AtomicInteger releaseCalls = new AtomicInteger();

		private final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

		private volatile boolean ownerlessVersionPutUsed;

		private volatile RuntimeException versionedReadFailure;

		private CountingLeasedSaver(LeasedCheckpointSaver delegate) {
			this.delegate = delegate;
		}

		@Override
		public LeaseOptions leaseOptions() {
			return delegate.leaseOptions();
		}

		@Override
		public ExecutionLease acquireLease(RunnableConfig config, UUID ownerId) throws Exception {
			acquireCalls.incrementAndGet();
			return delegate.acquireLease(config, ownerId);
		}

		@Override
		public ExecutionLease renewLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			renewCalls.incrementAndGet();
			return delegate.renewLease(config, lease);
		}

		@Override
		public boolean releaseLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			releaseCalls.incrementAndGet();
			events.add("release");
			return delegate.releaseLease(config, lease);
		}

		@Override
		public RunnableConfig putIfLeasedVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision,
				ExecutionLease lease) throws Exception {
			return delegate.putIfLeasedVersion(config, checkpoint, expectedRevision, lease);
		}

		@Override
		public Tag releaseIfLeasedVersion(RunnableConfig config, long expectedRevision, ExecutionLease lease)
				throws Exception {
			return delegate.releaseIfLeasedVersion(config, expectedRevision, lease);
		}

		@Override
		public CheckpointSnapshot getVersioned(RunnableConfig config) {
			if (versionedReadFailure != null) {
				throw versionedReadFailure;
			}
			return delegate.getVersioned(config);
		}

		@Override
		public RunnableConfig putIfVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision)
				throws Exception {
			ownerlessVersionPutUsed = true;
			return LeasedCheckpointSaver.super.putIfVersion(config, checkpoint, expectedRevision);
		}

		@Override
		public Tag releaseIfVersion(RunnableConfig config, long expectedRevision) throws Exception {
			return LeasedCheckpointSaver.super.releaseIfVersion(config, expectedRevision);
		}

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
			return LeasedCheckpointSaver.super.put(config, checkpoint);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return LeasedCheckpointSaver.super.release(config);
		}

	}

	private static final class BlockingFirstReleaseSaver implements LeasedCheckpointSaver {

		private final LeasedCheckpointSaver delegate;

		private final AtomicInteger acquireCalls = new AtomicInteger();

		private final AtomicInteger releaseCalls = new AtomicInteger();

		private final CountDownLatch firstReleaseStarted = new CountDownLatch(1);

		private final CountDownLatch allowFirstRelease = new CountDownLatch(1);

		private final CountDownLatch secondAcquireStarted = new CountDownLatch(1);

		private BlockingFirstReleaseSaver(LeasedCheckpointSaver delegate) {
			this.delegate = delegate;
		}

		@Override
		public LeaseOptions leaseOptions() {
			return delegate.leaseOptions();
		}

		@Override
		public ExecutionLease acquireLease(RunnableConfig config, UUID ownerId) throws Exception {
			int call = acquireCalls.incrementAndGet();
			if (call == 2) {
				secondAcquireStarted.countDown();
			}
			return delegate.acquireLease(config, ownerId);
		}

		@Override
		public ExecutionLease renewLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			return delegate.renewLease(config, lease);
		}

		@Override
		public boolean releaseLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			int call = releaseCalls.incrementAndGet();
			if (call == 1) {
				firstReleaseStarted.countDown();
				assertTrue(allowFirstRelease.await(5, TimeUnit.SECONDS));
			}
			return delegate.releaseLease(config, lease);
		}

		@Override
		public RunnableConfig putIfLeasedVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision,
				ExecutionLease lease) throws Exception {
			return delegate.putIfLeasedVersion(config, checkpoint, expectedRevision, lease);
		}

		@Override
		public Tag releaseIfLeasedVersion(RunnableConfig config, long expectedRevision, ExecutionLease lease)
				throws Exception {
			return delegate.releaseIfLeasedVersion(config, expectedRevision, lease);
		}

		@Override
		public CheckpointSnapshot getVersioned(RunnableConfig config) {
			return delegate.getVersioned(config);
		}

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
			return LeasedCheckpointSaver.super.put(config, checkpoint);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return LeasedCheckpointSaver.super.release(config);
		}

	}

	private static final class BlockingRenewSaver implements LeasedCheckpointSaver {

		private final LeasedCheckpointSaver delegate;

		private final CountDownLatch renewStarted = new CountDownLatch(1);

		private final CountDownLatch allowRenew = new CountDownLatch(1);

		private final CountDownLatch renewReturned = new CountDownLatch(1);

		private final AtomicInteger releaseCalls = new AtomicInteger();

		private BlockingRenewSaver(LeasedCheckpointSaver delegate) {
			this.delegate = delegate;
		}

		@Override
		public LeaseOptions leaseOptions() {
			return delegate.leaseOptions();
		}

		@Override
		public ExecutionLease acquireLease(RunnableConfig config, UUID ownerId) throws Exception {
			return delegate.acquireLease(config, ownerId);
		}

		@Override
		public ExecutionLease renewLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			renewStarted.countDown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (allowRenew.getCount() > 0) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError("Timed out waiting to unblock renew");
				}
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
			}
			try {
				return new ExecutionLease(lease.namespace(), lease.ownerId(), lease.fencingToken(),
						lease.expiresAtMillis() + leaseOptions().ttl().toMillis());
			}
			finally {
				renewReturned.countDown();
			}
		}

		@Override
		public boolean releaseLease(RunnableConfig config, ExecutionLease lease) throws Exception {
			releaseCalls.incrementAndGet();
			return delegate.releaseLease(config, lease);
		}

		@Override
		public RunnableConfig putIfLeasedVersion(RunnableConfig config, Checkpoint checkpoint, long expectedRevision,
				ExecutionLease lease) throws Exception {
			return delegate.putIfLeasedVersion(config, checkpoint, expectedRevision, lease);
		}

		@Override
		public Tag releaseIfLeasedVersion(RunnableConfig config, long expectedRevision, ExecutionLease lease)
				throws Exception {
			return delegate.releaseIfLeasedVersion(config, expectedRevision, lease);
		}

		@Override
		public CheckpointSnapshot getVersioned(RunnableConfig config) {
			return delegate.getVersioned(config);
		}

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
			return LeasedCheckpointSaver.super.put(config, checkpoint);
		}

		@Override
		public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
			return LeasedCheckpointSaver.super.release(config);
		}

	}

	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-01-01T00:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

	}

	private static final class NoopGuard implements ExecutionGuard {

		@Override
		public void assertActive() {
		}

		@Override
		public AutoCloseable onLoss(Runnable cancellation) {
			return () -> {
			};
		}

	}

}
