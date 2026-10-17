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
package io.github.agentic.ai.graph.execution.durable;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionLeaseScope;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseOptions;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionResolver;
import io.github.agentic.ai.graph.execution.definition.ResolvedRunDefinitions;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionAdmission;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionRecord;
import io.github.agentic.ai.graph.execution.definition.WorkerCapabilities;
import io.github.agentic.ai.graph.execution.run.RunLease;
import io.github.agentic.ai.graph.execution.run.RunNamespace;
import io.github.agentic.ai.graph.execution.run.RunPayload;
import io.github.agentic.ai.graph.execution.run.RunSnapshot;
import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunState;
import io.github.agentic.ai.graph.execution.run.RunStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.StateGraph.END;

/**
 * Explicit whole-run worker, reusing CompiledGraph execution. No queue, node sharding,
 * model journal replay or exactly-once side-effect guarantee is implied. Applications
 * authenticate query/cancel requests, re-resolve identity before run(), and route
 * cancellation to the current worker. Dropping a subscription cancels that execution;
 * it is not a resumable client disconnect.
 */
public final class DurableRunRunner {
	private static final Logger LOG = LoggerFactory.getLogger(DurableRunRunner.class);
	private final RunStore store;
	private final DefinitionResolver resolver;
	private final WorkerCapabilities worker;
	private final LeaseOptions options;
	private final Consumer<RunExecutionEvent> observer;
	private final Clock clock;
	private final Map<Key, Execution> active = new ConcurrentHashMap<>();

	public DurableRunRunner(RunStore store, DefinitionResolver resolver, WorkerCapabilities worker,
			LeaseOptions options, Consumer<RunExecutionEvent> observer) {
		this(store, resolver, worker, options, observer, Clock.systemUTC());
		store.capabilities().requireDurableMode();
	}

	/**
	 * Internal reference-test constructor. Only the public constructor admits production
	 * durable work. Keeping the engine testable does not make MemoryRunStore persistent.
	 */
	DurableRunRunner(RunStore store, DefinitionResolver resolver, WorkerCapabilities worker,
			LeaseOptions options, Consumer<RunExecutionEvent> observer, Clock clock) {
		this.store = Objects.requireNonNull(store, "store cannot be null");
		this.resolver = Objects.requireNonNull(resolver, "resolver cannot be null");
		this.worker = Objects.requireNonNull(worker, "worker cannot be null");
		this.options = Objects.requireNonNull(options, "options cannot be null");
		this.observer = Objects.requireNonNull(observer, "observer cannot be null");
		this.clock = Objects.requireNonNull(clock, "clock cannot be null");
	}

	/**
	 * Atomically pin the definition record with input before a worker may execute. The
	 * stored input hash covers both, so changing a version under a submission key conflicts.
	 */
	public RunSnapshot submit(RunSpec spec, RunDefinitionRecord definitions, TrustedExecutionContext context) {
		resolve(spec, definitions);
		RunPayload envelope = new DurableRunInput(definitions, spec.input()).encode();
		RunSpec pinned = new RunSpec(spec.schemaVersion(), spec.namespace(), spec.runId(), spec.submissionKey(),
				envelope.inputHash(), spec.graphId(), spec.graphVersion(), envelope, spec.context());
		return store.submit(pinned, context);
	}

	/** Authorize at the application boundary before querying this namespace. */
	public Optional<RunSnapshot> query(RunNamespace namespace, String runId) {
		return store.find(namespace, runId);
	}

	/** Cold execution: acquire one physical lease when subscribed. */
	public Mono<RunSnapshot> run(RunNamespace namespace, String runId, TrustedExecutionContext context) {
		return run(namespace, runId, null, context);
	}

	/**
	 * Execute using the worker's already-acquired owner/fence. Once identity validation
	 * succeeds and a free local execution slot is accepted, the runner owns renewal and
	 * release, including definition-resolution failure cleanup. Rejected identity or an
	 * already-active local slot leaves ownership with the caller. Do not concurrently
	 * renew or release an accepted lease from the caller.
	 */
	public Mono<RunSnapshot> runOwned(RunNamespace namespace, String runId, RunLease lease,
			TrustedExecutionContext context) {
		Objects.requireNonNull(lease, "lease cannot be null");
		if (!namespace.equals(lease.namespace())) {
			throw new IllegalArgumentException("Worker lease belongs to a different namespace");
		}
		return run(namespace, runId, lease, context);
	}

	/**
	 * Request cooperative cancellation on this worker. True means accepted locally, not
	 * that CANCELLED has committed. Query the run for completion. False means no local
	 * execution; this method never steals a remote owner's lease or reserves a session.
	 */
	public boolean cancel(RunNamespace namespace, String runId) {
		Execution execution = active.get(new Key(namespace, runId));
		if (execution == null) {
			return false;
		}
		execution.cancelled.set(true);
		execution.stop.tryEmitEmpty();
		return true;
	}

	private Mono<RunSnapshot> run(RunNamespace namespace, String runId, RunLease owned,
			TrustedExecutionContext context) {
		Key key = new Key(Objects.requireNonNull(namespace), Objects.requireNonNull(runId));
		Objects.requireNonNull(context, "context cannot be null");
		return Mono.defer(() -> {
			RunSnapshot snapshot = store.find(namespace, runId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown run"));
			if (!snapshot.spec().context().equals(context.toReference())) {
				throw new IllegalArgumentException("Recovered identity and constraints must match the admitted run");
			}
			if (snapshot.state().terminal()) {
				return Mono.just(snapshot);
			}
			return Mono.usingWhen(Mono.fromCallable(() -> acquire(key, owned, context))
					.doOnDiscard(Execution.class, execution -> execution.close(true)
						.subscribe(ignored -> { }, error -> LOG.warn("Cancelled run acquisition cleanup failed", error))),
					Execution::execute, execution -> execution.close(false),
					(execution, error) -> execution.close(false), execution -> execution.close(true));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private Execution acquire(Key key, RunLease owned, TrustedExecutionContext context) {
		// Serialize only acquisition on this worker, never graph execution or other runs.
		return active.compute(key, (ignored, existing) -> {
			if (existing != null) {
				throw new IllegalStateException("Run is already executing on this worker");
			}
			RunLease lease = owned != null ? owned : store.acquire(key.namespace(), key.runId(),
					UUID.randomUUID(), options.ttl());
			try {
				RunSnapshot current = store.find(key.namespace(), key.runId()).orElseThrow();
				if (!current.tasks().isEmpty()) {
					throw new IllegalArgumentException("Whole-run execution cannot recover independent tasks");
				}
				DurableRunInput input = DurableRunInput.decode(current.spec().input());
				ResolvedRunDefinitions definitions = resolve(current.spec(), input.definitions());
				return new Execution(key, current, lease, context, input, definitions);
			}
			catch (RuntimeException ex) {
				try {
					store.release(lease);
				}
				catch (RuntimeException cleanup) {
					ex.addSuppressed(cleanup);
				}
				throw ex;
			}
		});
	}

	private ResolvedRunDefinitions resolve(RunSpec spec, RunDefinitionRecord record) {
		ResolvedRunDefinitions definitions = RunDefinitionAdmission.resolve(spec, record, resolver, worker);
		definitions.definition(DefinitionKind.GRAPH, DurableGraphDefinition.class).requireRecoverable();
		definitions.definition(DefinitionKind.CODEC, DurableStateCodec.class);
		return definitions;
	}

	private record Key(RunNamespace namespace, String runId) {
	}

	private final class Execution {
		private final Key key;
		private final TrustedExecutionContext context;
		private final DurableRunInput input;
		private final ResolvedRunDefinitions definitions;
		private final RunCheckpointSaver saver;
		private final long startedAt = System.nanoTime();
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final AtomicBoolean timedOut = new AtomicBoolean();
		private final Instant deadline;
		private final Sinks.Empty<Void> stop = Sinks.empty();
		private boolean started;
		private boolean waiting;

		private Execution(Key key, RunSnapshot snapshot, RunLease lease, TrustedExecutionContext context,
				DurableRunInput input, ResolvedRunDefinitions definitions) {
			this.key = key;
			this.context = context;
			this.input = input;
			this.definitions = definitions;
			this.deadline = snapshot.spec().deadlineEpochMillis() == null ? null
					: Instant.ofEpochMilli(snapshot.spec().deadlineEpochMillis());
			this.saver = new RunCheckpointSaver(store, snapshot, lease,
					definitions.definition(DefinitionKind.CODEC, DurableStateCodec.class), options,
					ignored -> observe(RunExecutionEvent.Kind.CHECKPOINT_COMMITTED), this::deadlineReached);
		}

		private Mono<RunSnapshot> execute() {
			return Mono.defer(() -> {
				if (deadlineReached()) {
					return Mono.just(finish(RunState.TIMED_OUT));
				}
				try {
					DurableStateCodec codec = definitions.definition(DefinitionKind.CODEC, DurableStateCodec.class);
					CompiledGraph graph = definitions.definition(DefinitionKind.GRAPH, DurableGraphDefinition.class)
						.factory().compile(definitions, saver);
					if (graph.compileConfig.checkpointSaver().orElse(null) != saver
							|| graph.compileConfig.releaseThread()) {
						throw new IllegalArgumentException("Graph must retain the supplied durable checkpoint saver");
					}
					Checkpoint initial = Checkpoint.builder().nodeId(START).nextNodeId(START)
						.state(codec.decodeInput(input.input())).build();
					saver.start(initial);
					started = true;
					observe(RunExecutionEvent.Kind.STARTED);
					Checkpoint committed = codec.decodeCheckpoint(saver.snapshot().checkpoint());
					if (END.equals(committed.getNextNodeId())) {
						return Mono.just(finish(RunState.SUCCEEDED));
					}
					RunnableConfig config = RunnableConfig.builder().threadId(key.runId())
						.checkPointId(START.equals(committed.getNextNodeId()) ? null : committed.getId())
						.trustedExecutionContext(context).build();
					return ExecutionLeaseScope.withBorrowedLease(saver, config, saver.executionLease(),
							scope -> graph.graphResponseStream(Map.<String, Object>of(), config)
								.concatMap(this::consume)
								.takeUntilOther(stopSignal()))
						.then(Mono.fromCallable(() -> finish(cancelled.get() ? RunState.CANCELLED
								: deadlineReached() ? RunState.TIMED_OUT
								: waiting ? RunState.WAITING : RunState.SUCCEEDED)));
				}
				catch (Exception ex) {
					return Mono.error(ex);
				}
			}).onErrorResume(error -> {
				if (error instanceof RunCheckpointSaver.DeadlineExceededException) {
					return Mono.fromCallable(() -> finish(RunState.TIMED_OUT));
				}
				if (error instanceof LeaseLostException || saver.lost()) {
					observe(RunExecutionEvent.Kind.LEASE_LOST);
				}
				else {
					observe(RunExecutionEvent.Kind.EXECUTION_FAILED);
					if (started && !saver.snapshot().state().terminal()) {
						try {
							finish(deadlineReached() ? RunState.TIMED_OUT : RunState.FAILED);
						}
						catch (RuntimeException commitFailure) {
							error.addSuppressed(commitFailure);
						}
					}
				}
				return Mono.error(error);
			});
		}

		private Flux<NodeOutput> consume(GraphResponse<NodeOutput> response) {
			if (response.isDone()) {
				waiting |= response.resultValue().filter(InterruptionMetadata.class::isInstance).isPresent();
				return Flux.empty();
			}
			// Exceptional output futures must fail the run even when the response stream completes.
			return Mono.fromFuture(response.getOutput()).flux();
		}

		private Mono<?> stopSignal() {
			if (deadline == null) {
				return stop.asMono();
			}
			Duration remaining = Duration.between(clock.instant(), deadline);
			return Mono.firstWithSignal(stop.asMono(), Mono.delay(remaining.isNegative() ? Duration.ZERO : remaining)
				.doOnNext(ignored -> timedOut.set(true)));
		}

		private boolean deadlineReached() {
			return timedOut.get() || deadline != null && !clock.instant().isBefore(deadline);
		}

		private RunSnapshot finish(RunState state) {
			RunSnapshot snapshot = saver.finish(state);
			observe(RunExecutionEvent.Kind.FINISHED);
			return snapshot;
		}

		private Mono<Void> close(boolean subscriptionCancelled) {
			return Mono.fromRunnable(() -> {
				try {
					if (subscriptionCancelled && !saver.snapshot().state().terminal() && !saver.lost()) {
						finish(RunState.CANCELLED);
					}
				}
				finally {
					try {
						saver.release();
					}
					finally {
						active.remove(key, this);
					}
				}
			});
		}

		private void observe(RunExecutionEvent.Kind kind) {
			RunSnapshot snapshot = saver.snapshot();
			try {
				observer.accept(new RunExecutionEvent(key.namespace(), key.runId(), kind, snapshot.state(),
						snapshot.revision(), System.nanoTime() - startedAt));
			}
			catch (RuntimeException ex) {
				LOG.warn("Durable run observer failed for run '{}'", key.runId(), ex);
			}
		}
	}
}
