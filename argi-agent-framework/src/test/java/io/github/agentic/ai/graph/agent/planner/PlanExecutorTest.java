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
package io.github.agentic.ai.graph.agent.planner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.action.ActionType;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolOutcome;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.INPUT;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.OBJECTIVE;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.OUTPUT;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.SEARCH;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.plan;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutorTest {

	private final ExecutorService pool = Executors.newFixedThreadPool(4);

	private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

	@AfterEach
	void shutdown() {
		this.pool.shutdownNow();
	}

	@Test
	void serialDependenciesRunInOrderAndPassUpstreamResults() throws Exception {
		Map<String, PlanStepRequest> requests = new ConcurrentHashMap<>();
		PlanRun run = run(executor(request -> {
			requests.put(request.step().stepId(), request);
			return ok(request);
		}).build(), plan(step("a"), step("b", "a"), step("c", "b")));

		assertEquals(PlanRunStatus.COMPLETED, run.status());
		assertEquals(List.of("a", "b", "c"), this.calls);
		assertEquals(Map.of("a", "\"a-done\""), requests.get("b").dependencyResults());
		assertEquals(Map.of("a", "\"a-done\"", "b", "\"b-done\"", "c", "\"c-done\""), run.results());
		assertEquals(ActionType.FINAL_RESPONSE, run.decision().type());
		assertEquals(3, run.stepExecutions());
	}

	@Test
	void independentStepsRunInParallelAndTheJoinWaitsForBoth() throws Exception {
		CountDownLatch bothStarted = new CountDownLatch(2);
		PlanRun run = run(executor(request -> {
			if (!request.step().stepId().equals("join")) {
				bothStarted.countDown();
				assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "branches must overlap");
			}
			else {
				assertEquals(2, request.dependencyResults().size());
			}
			return ok(request);
		}).build(), plan(step("left"), step("right"), step("join", "left", "right")));

		assertEquals(PlanRunStatus.COMPLETED, run.status());
		assertEquals("join", this.calls.get(2));
	}

	@Test
	void downstreamStepNeverStartsWhenItsDependencyFails() throws Exception {
		PlanRun run = run(executor(request -> PlanStepOutcome.failed(ToolOutcome.UNCERTAIN, "lost ack", "receipt-a"))
			.build(), plan(step("a"), step("b", "a")));

		assertEquals(PlanRunStatus.FAILED, run.status());
		assertEquals(PlanReasonCodes.STEP_FAILED, run.reasonCode());
		assertEquals(List.of("a"), this.calls);
		assertEquals(PlanStep.Status.FAILED, run.plan().steps().get(0).status());
		assertEquals(PlanStep.Status.PENDING, run.plan().steps().get(1).status());
		assertEquals("receipt-a", run.effectReferences().get("a"));
		assertEquals(ActionType.STOP, run.decision().type());
	}

	@Test
	void transientFailureRetriesTheSameStepIdentity() throws Exception {
		List<String> keys = Collections.synchronizedList(new ArrayList<>());
		PlanRun run = run(executor(request -> {
			keys.add(request.stepKey());
			return request.attempt() == 1 ? PlanStepOutcome.failed(ToolOutcome.TRANSIENT, "busy") : ok(request);
		}).build(), plan(step("a")));

		assertEquals(PlanRunStatus.COMPLETED, run.status());
		assertEquals(2, keys.size());
		assertEquals(keys.get(0), keys.get(1));
		assertEquals(2, run.attempts().get("a"));
	}

	@Test
	void failureReplansOnceAndNeverRepeatsCompletedEffects() throws Exception {
		List<PlanReplanner.Request> replans = new ArrayList<>();
		PlanExecutor executor = executor(request -> request.step().stepId().equals("b")
				? PlanStepOutcome.failed(ToolOutcome.BUSINESS_FAILED, "quota") : ok(request, "receipt-"))
			.replanner(request -> {
				replans.add(request);
				return new PlanSpec(1, request.nextRevision(), OBJECTIVE,
						List.of(step("a"), custom("b2", "Perform b with a smaller quota", "a")));
			})
			.build();

		PlanRun run = run(executor, plan(step("a"), step("b", "a")));

		assertEquals(PlanRunStatus.COMPLETED, run.status());
		assertEquals(List.of("a", "b", "b2"), this.calls);
		assertEquals(List.of(1L, 2L), run.revisions());
		assertEquals(2L, run.plan().revision());
		assertEquals(1, run.replans());
		assertEquals(List.of("b"), run.retiredStepIds());
		assertEquals("receipt-a", run.effectReferences().get("a"));
		assertEquals(List.of("b"), replans.get(0).failedStepIds());
		assertEquals(Map.of("a", "\"a-done\""), replans.get(0).completedResults());
		assertEquals(PlanStep.Status.FAILED, replans.get(0).currentPlan().steps().get(1).status());
		assertEquals(Map.of("a", "\"a-done\"", "b2", "\"b2-done\""), run.results());
	}

	@Test
	void illegalProposalsTerminateWithInvalidPlan() throws Exception {
		assertRejected(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE,
				List.of(step("a"), custom("b", "Perform b differently", "a"))), PlanRunStatus.INVALID_PLAN,
				PlanReasonCodes.STEP_IDENTITY_REUSED);
		assertRejected(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE,
				List.of(custom("a", "Perform a again"), step("c", "a"))), PlanRunStatus.INVALID_PLAN,
				PlanReasonCodes.STEP_IDENTITY_REUSED);
		assertRejected(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE, List.of(step("c"))),
				PlanRunStatus.INVALID_PLAN, PlanReasonCodes.COMPLETED_STEP_DROPPED);
		assertRejected(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE,
				List.of(claimed("a"), step("c", "a"))), PlanRunStatus.INVALID_PLAN, PlanReasonCodes.REPLAN_INVALID);
		assertRejected(request -> new PlanSpec(1, request.nextRevision() + 1, OBJECTIVE,
				List.of(step("a"), step("c", "a"))), PlanRunStatus.INVALID_PLAN, PlanReasonCodes.REPLAN_INVALID);
		assertRejected(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE,
				List.of(step("a"), step("c", "missing"))), PlanRunStatus.INVALID_PLAN, PlanReasonCodes.REPLAN_INVALID);
	}

	@Test
	void emptyOrRepeatedDecisionsAreRejected() throws Exception {
		assertRejected(request -> null, PlanRunStatus.REPLAN_REJECTED, PlanReasonCodes.REPLAN_EMPTY);
		assertRejected(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE, List.of(step("a"))),
				PlanRunStatus.REPLAN_REJECTED, PlanReasonCodes.REPLAN_EMPTY);
		assertRejected(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE,
				List.of(step("a"), renamed("b-again", "a"))), PlanRunStatus.REPLAN_REJECTED,
				PlanReasonCodes.REPLAN_NO_PROGRESS);
		assertRejected(request -> {
			throw new IllegalStateException("model unavailable");
		}, PlanRunStatus.FAILED, PlanReasonCodes.REPLAN_FAILED);
	}

	@Test
	void retainingUntouchedWorkWithoutNewStepsIsNoProgress() throws Exception {
		PlanExecutor executor = executor(request -> request.step().stepId().equals("b")
				? PlanStepOutcome.failed(ToolOutcome.BUSINESS_FAILED, "quota") : ok(request))
			.limits(new PlanExecutionLimits(1, 2, 2, 64, null))
			.replanner(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE, List.of(step("a"), step("c"))))
			.build();

		PlanRun run = run(executor, plan(step("a"), step("b", "a"), step("c")));

		assertEquals(PlanRunStatus.REPLAN_REJECTED, run.status());
		assertEquals(PlanReasonCodes.REPLAN_NO_PROGRESS, run.reasonCode());
	}

	@Test
	void replanningIsBoundedByCount() throws Exception {
		AtomicInteger proposals = new AtomicInteger();
		PlanExecutor executor = executor(request -> PlanStepOutcome.failed(ToolOutcome.BUSINESS_FAILED, "no"))
			.limits(new PlanExecutionLimits(4, 2, 1, 64, null))
			.replanner(request -> new PlanSpec(1, request.nextRevision(), OBJECTIVE,
					List.of(custom("try-" + proposals.incrementAndGet(), "Variant " + proposals.get()))))
			.build();

		PlanRun run = run(executor, plan(step("a")));

		assertEquals(PlanRunStatus.REPLAN_LIMIT_EXCEEDED, run.status());
		assertEquals(1, proposals.get());
		assertEquals(1, run.replans());
	}

	@Test
	void missingInvalidOrAbsentResultsAreNeverSuccess() throws Exception {
		for (Function<PlanStepRequest, PlanStepOutcome> step : List.<Function<PlanStepRequest, PlanStepOutcome>>of(
				request -> PlanStepOutcome.succeeded(null), request -> PlanStepOutcome.succeeded("42"),
				request -> PlanStepOutcome.succeeded("not json"), request -> null, request -> {
					throw new IllegalStateException("boom");
				})) {
			PlanRun run = run(executor(step::apply).build(), plan(step("a")));
			assertEquals(PlanRunStatus.FAILED, run.status());
			assertEquals(PlanReasonCodes.REPLAN_UNAVAILABLE, run.reasonCode());
			assertEquals(PlanStep.Status.FAILED, run.plan().steps().get(0).status());
			assertTrue(run.results().isEmpty());
		}
	}

	@Test
	void budgetExhaustionStopsBeforeTheNextDispatch() throws Exception {
		PlanRun run = run(executor(this::ok).limits(new PlanExecutionLimits(4, 2, 2, 2, null)).build(),
				plan(step("a"), step("b", "a"), step("c", "b")));

		assertEquals(PlanRunStatus.BUDGET_EXHAUSTED, run.status());
		assertEquals(List.of("a", "b"), this.calls);
		assertEquals(2, run.results().size());
	}

	@Test
	void deadlineStopsTheRun() throws Exception {
		MutableClock clock = new MutableClock();
		PlanRun run = run(executor(request -> {
			clock.advance(Duration.ofSeconds(11));
			return ok(request);
		}).clock(clock).limits(PlanExecutionLimits.defaults().withDeadline(Duration.ofSeconds(10))).build(),
				plan(step("a"), step("b", "a")));

		assertEquals(PlanRunStatus.DEADLINE_EXCEEDED, run.status());
		assertEquals(List.of("a"), this.calls);
		assertEquals(Map.of("a", "\"a-done\""), run.results());
	}

	@Test
	void stepStillRunningAtTheDeadlineIsCancelled() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		PlanRun run = run(executor(request -> {
			release.await(5, TimeUnit.SECONDS);
			return ok(request);
		}).limits(PlanExecutionLimits.defaults().withDeadline(Duration.ofMillis(200))).build(), plan(step("a")));
		release.countDown();

		assertEquals(PlanRunStatus.DEADLINE_EXCEEDED, run.status());
		assertEquals(PlanStep.Status.FAILED, run.plan().steps().get(0).status());
		assertTrue(run.plan().steps().get(0).error().startsWith(ToolOutcome.TIMEOUT));
		assertTrue(run.results().isEmpty());
	}

	@Test
	void missingInputInterruptsAndResumesFromTheCheckpoint() throws Exception {
		PlanExecutor executor = executor(request -> {
			if (request.step().stepId().equals("approve") && request.input() == null) {
				return PlanStepOutcome.needInput("Approve the summary?");
			}
			return PlanStepOutcome.succeeded(request.input() != null ? request.input() : done(request));
		}).build();
		CompiledGraph graph = executor
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(MemorySaver.builder().build()).build())
				.build());
		RunnableConfig config = RunnableConfig.builder().threadId("plan-input").build();

		graph.invoke(executor.initialState(plan(step("a"), step("approve", "a"), step("publish", "approve"))), config);
		PlanRun waiting = PlanExecutor.read(graph.getState(config).state());
		assertEquals(PlanRunStatus.NEED_INPUT, waiting.status());
		assertEquals("approve", waiting.awaitingStepId());
		assertEquals("Approve the summary?", waiting.awaitingPrompt());
		assertEquals(ActionType.NEED_INPUT, waiting.decision().type());
		assertFalse(waiting.terminal());

		// Resuming without the awaited input keeps the run suspended.
		graph.invoke((Map<String, Object>) null, RunnableConfig.builder(config).resume().build());
		assertEquals(PlanRunStatus.NEED_INPUT, PlanExecutor.read(graph.getState(config).state()).status());

		Optional<OverAllState> resumed = graph.invoke((Map<String, Object>) null, RunnableConfig.builder(config)
			.resume()
			.addStateUpdate(PlanExecutor.input("approve", "\"approved\""))
			.build());

		PlanRun run = PlanExecutor.read(resumed.orElseThrow());
		assertEquals(PlanRunStatus.COMPLETED, run.status());
		assertEquals(List.of("a", "approve", "approve", "publish"), this.calls);
		assertEquals("\"approved\"", run.results().get("approve"));
		assertNull(run.awaitingStepId());
		assertFalse(resumed.get().data().containsKey(PlanExecutor.INPUT_KEY));
	}

	@Test
	void newRunsCannotClaimProgress() {
		PlanExecutor executor = executor(this::ok).build();
		assertThrows(PlanValidationException.class, () -> executor.initialState(plan(claimed("a"))));
		assertThrows(PlanValidationException.class, () -> PlanExecutor.read(Map.of()));
	}

	@Test
	void defaultPolicyStopsOnUnknownEffectsAndReplansDefiniteFailures() {
		PlanFailurePolicy policy = PlanFailurePolicy.defaults();
		PlanStep step = step("a");
		assertEquals(PlanFailurePolicy.Action.RETRY,
				policy.decide(new PlanFailurePolicy.Failure(step, ToolOutcome.TRANSIENT, null, 1, 2)));
		assertEquals(PlanFailurePolicy.Action.REPLAN,
				policy.decide(new PlanFailurePolicy.Failure(step, ToolOutcome.TRANSIENT, null, 2, 2)));
		for (String unknown : List.of(ToolOutcome.TIMEOUT, ToolOutcome.CANCELLED, ToolOutcome.UNCERTAIN,
				ToolOutcome.AUTH_DENIED)) {
			assertEquals(PlanFailurePolicy.Action.STOP,
					policy.decide(new PlanFailurePolicy.Failure(step, unknown, null, 1, 2)));
		}
		for (String definite : List.of(ToolOutcome.ERROR, ToolOutcome.BUSINESS_FAILED, ToolOutcome.VALIDATION_DENIED)) {
			assertEquals(PlanFailurePolicy.Action.REPLAN,
					policy.decide(new PlanFailurePolicy.Failure(step, definite, null, 1, 2)));
		}
	}

	@Test
	void toolResponsesKeepTheirOutcomeVocabulary() {
		assertEquals(PlanStepOutcome.Kind.SUCCEEDED,
				PlanStepOutcome.fromToolCallResponse(ToolCallResponse.success("1", "search", "\"x\"")).kind());
		PlanStepOutcome timeout = PlanStepOutcome.fromToolCallResponse(ToolCallResponse.builder()
			.toolCallId("1")
			.toolName("search")
			.content("slow")
			.status(ToolOutcome.ERROR)
			.metadata(Map.of(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_TIMEOUT))
			.build());
		assertEquals(ToolOutcome.TIMEOUT, timeout.outcome());
		assertEquals(ToolOutcome.BUSINESS_FAILED, PlanStepOutcome.fromToolCallResponse(ToolCallResponse.builder()
			.toolCallId("1")
			.toolName("search")
			.content("rejected")
			.status(ToolOutcome.BUSINESS_FAILED)
			.build()).outcome());
		assertEquals(ToolOutcome.ERROR, PlanStepOutcome.failed(null, "x").outcome());
	}

	@Test
	void runLedgerRoundTripsThroughState() throws Exception {
		PlanExecutor executor = executor(this::ok).build();
		Map<String, Object> state = executor.initialState(plan(step("a")));
		assertTrue(state.get(PlanExecutor.RUN_KEY) instanceof String);
		PlanRun run = PlanExecutor.read(state);
		assertEquals(PlanRunStatus.RUNNING, run.status());
		assertNull(run.decision());
		assertNotNull(PlanExecutor.stepKey(run.plan().steps().get(0)));
		assertTrue(executor.compile(null).getMaxIterations() >= executor.recursionLimit());
	}

	// ---------------------------------------------------------------- helpers

	private void assertRejected(PlanReplanner replanner, PlanRunStatus status, String reasonCode) throws Exception {
		this.calls.clear();
		PlanExecutor executor = executor(request -> request.step().stepId().equals("b")
				? PlanStepOutcome.failed(ToolOutcome.BUSINESS_FAILED, "quota") : ok(request))
			.replanner(replanner)
			.build();
		PlanRun run = run(executor, plan(step("a"), step("b", "a")));
		assertEquals(status, run.status(), run.detail());
		assertEquals(reasonCode, run.reasonCode());
		assertEquals(List.of("a", "b"), this.calls, "rejected proposals must not execute");
		assertEquals(ActionType.STOP, run.decision().type());
	}

	private PlanExecutor.Builder executor(PlanStepExecutor delegate) {
		return PlanExecutor.builder(PlannerFixtures.validator(), request -> {
			this.calls.add(request.step().stepId());
			return delegate.execute(request);
		}).executor(this.pool);
	}

	private PlanRun run(PlanExecutor executor, PlanSpec plan) throws Exception {
		CompiledGraph graph = executor.compile(CompileConfig.builder().build());
		return PlanExecutor.read(graph.invoke(executor.initialState(plan), RunnableConfig.builder().build())
			.orElseThrow());
	}

	private PlanStepOutcome ok(PlanStepRequest request) {
		return PlanStepOutcome.succeeded(done(request));
	}

	private PlanStepOutcome ok(PlanStepRequest request, String receiptPrefix) {
		return PlanStepOutcome.succeeded(done(request), receiptPrefix + request.step().stepId());
	}

	private static String done(PlanStepRequest request) {
		return "\"" + request.step().stepId() + "-done\"";
	}

	private static PlanStep custom(String id, String objective, String... dependencies) {
		return new PlanStep(id, objective, List.of(dependencies), SEARCH.executionTarget(), INPUT, OUTPUT,
				"Produce a relevant document", PlanStep.Status.PENDING, null, null);
	}

	/** Same definition as {@code step("b")} under a new ID. */
	private static PlanStep renamed(String id, String... dependencies) {
		return custom(id, "Perform b", dependencies);
	}

	private static PlanStep claimed(String id) {
		return new PlanStep(id, "Perform " + id, List.of(), SEARCH.executionTarget(), INPUT, OUTPUT,
				"Produce a relevant document", PlanStep.Status.COMPLETED, "\"fake\"", null);
	}

	private static final class MutableClock extends Clock {

		private final AtomicLong millis = new AtomicLong(1_000_000L);

		void advance(Duration duration) {
			this.millis.addAndGet(duration.toMillis());
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(this.millis.get());
		}

		@Override
		public long millis() {
			return this.millis.get();
		}

	}

}
