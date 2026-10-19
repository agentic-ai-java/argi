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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.KeyStrategyFactoryBuilder;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.action.InterruptableAction;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.agent.interceptor.ToolOutcome;
import io.github.agentic.ai.graph.exception.GraphStateException;
import com.networknt.schema.Schema;
import tools.jackson.databind.JsonNode;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncEdgeAction.edge_async;
import static io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig.node_async;

/**
 * Opt-in Plan-and-Execute runtime on top of {@link StateGraph}. A validated
 * {@link PlanSpec} is executed by a small fixed graph:
 *
 * <pre>
 * START -> plan_execute -> (plan_execute | plan_replan | plan_input | END)
 * </pre>
 *
 * Each {@code plan_execute} pass dispatches the ready steps (all dependencies completed)
 * of the current revision as one local, bounded parallel batch and records their outcomes
 * in the {@link PlanRun} ledger. A downstream step is never dispatched before its
 * dependencies completed. Failures are routed by the {@link PlanFailurePolicy} to retry,
 * bounded replanning, an input interruption or a terminal stop.
 *
 * <p>
 * This is a whole-run, in-process executor: node boundaries checkpoint the ledger through
 * the configured saver, and {@code plan_input} uses the standard graph interruption so a
 * run suspended with {@link PlanRunStatus#NEED_INPUT} resumes from its checkpoint.
 * Durable commit/journal semantics, cross-instance workers and sub-agent protocols remain
 * with their dedicated components; step effects stay behind the application's
 * {@link PlanStepExecutor} and its existing authorization gates.
 * </p>
 */
public final class PlanExecutor {

	/** State key holding the JSON-encoded {@link PlanRun}. */
	public static final String RUN_KEY = "argi_plan_run";

	/** State key holding JSON-encoded input supplied for the awaited step. */
	public static final String INPUT_KEY = "argi_plan_input";

	public static final String EXECUTE_NODE = "plan_execute";

	public static final String REPLAN_NODE = "plan_replan";

	public static final String INPUT_NODE = "plan_input";

	/** Interruption metadata key carrying the awaited step ID. */
	public static final String INTERRUPT_STEP_ID = "planStepId";

	/** Interruption metadata key carrying the input request. */
	public static final String INTERRUPT_PROMPT = "planPrompt";

	/** Interruption metadata key carrying the reason code. */
	public static final String INTERRUPT_REASON = "planReasonCode";

	private final PlanValidator validator;

	private final PlanStepExecutor stepExecutor;

	private final PlanReplanner replanner;

	private final PlanFailurePolicy failurePolicy;

	private final PlanExecutionLimits limits;

	private final Clock clock;

	private final Executor executor;

	private PlanExecutor(Builder builder) {
		this.validator = builder.validator;
		this.stepExecutor = builder.stepExecutor;
		this.replanner = builder.replanner;
		this.failurePolicy = builder.failurePolicy;
		this.limits = builder.limits;
		this.clock = builder.clock;
		this.executor = builder.executor;
	}

	public static Builder builder(PlanValidator validator, PlanStepExecutor stepExecutor) {
		return new Builder(validator, stepExecutor);
	}

	/**
	 * Create the initial state of a new run. A new run cannot claim progress: the plan
	 * must pass {@link PlanValidator#validateGenerated(PlanSpec)}.
	 * @param plan the validated initial revision
	 * @return the state to pass to the compiled graph
	 */
	public Map<String, Object> initialState(PlanSpec plan) {
		this.validator.validateGenerated(plan);
		Ledger ledger = new Ledger(plan, this.clock.millis());
		return Map.of(RUN_KEY, encode(ledger.toRun()));
	}

	/**
	 * State update that supplies input for the awaited step, for use with
	 * {@link RunnableConfig.Builder#addStateUpdate(Map)} on resume or
	 * {@link CompiledGraph#updateState(RunnableConfig, Map)}.
	 * @param stepId the awaited step
	 * @param jsonInput JSON-encoded input
	 * @return the state update
	 */
	public static Map<String, Object> input(String stepId, String jsonInput) {
		PlanValidator.text(stepId, "stepId");
		return Map.of(INPUT_KEY, PlanCodec.MAPPER.writeValueAsString(new ProvidedInput(stepId, jsonInput)));
	}

	/** Read the run ledger from graph state. */
	public static PlanRun read(OverAllState state) {
		Objects.requireNonNull(state, "state");
		return read(state.data());
	}

	/** Read the run ledger from graph state data. */
	public static PlanRun read(Map<String, Object> state) {
		Object value = state == null ? null : state.get(RUN_KEY);
		PlanValidator.require(value instanceof String, "State does not contain a plan run");
		try {
			return PlanCodec.MAPPER.readValue((String) value, PlanRun.class);
		}
		catch (RuntimeException ex) {
			throw new PlanValidationException("Invalid plan run state", ex);
		}
	}

	/**
	 * Graph iterations needed by one invocation within the configured limits: each
	 * execution pass consumes budget, each replan consumes a replanning attempt.
	 */
	public int recursionLimit() {
		return 2 * this.limits.maxStepExecutions() + 2 * this.limits.maxReplans() + 4;
	}

	/** Build the uncompiled executor graph. */
	public StateGraph graph() throws GraphStateException {
		var keys = new KeyStrategyFactoryBuilder().defaultStrategy(KeyStrategy.REPLACE)
			.addStrategy(RUN_KEY, KeyStrategy.REPLACE)
			.addStrategy(INPUT_KEY, KeyStrategy.REPLACE)
			.build();
		Map<String, String> routes = Map.of(EXECUTE_NODE, EXECUTE_NODE, REPLAN_NODE, REPLAN_NODE, INPUT_NODE,
				INPUT_NODE, END, END);
		return new StateGraph("argi-plan-executor", keys)
			.addNode(EXECUTE_NODE, node_async((state, config) -> execute(state)))
			.addNode(REPLAN_NODE, node_async((state, config) -> replan(state)))
			.addNode(INPUT_NODE, new InputNode())
			.addEdge(START, EXECUTE_NODE)
			.addConditionalEdges(EXECUTE_NODE, edge_async(PlanExecutor::route), routes)
			.addConditionalEdges(REPLAN_NODE, edge_async(PlanExecutor::route), routes)
			.addConditionalEdges(INPUT_NODE, edge_async(PlanExecutor::route), routes);
	}

	/**
	 * Compile the executor graph, raising the recursion limit to
	 * {@link #recursionLimit()} when the given configuration is lower.
	 */
	public CompiledGraph compile(CompileConfig config) throws GraphStateException {
		CompileConfig base = config == null ? CompileConfig.builder().build() : config;
		return graph().compile(CompileConfig.builder(base)
			.recursionLimit(Math.max(base.recursionLimit(), recursionLimit()))
			.build());
	}

	private static String route(OverAllState state) {
		PlanRun run = read(state);
		if (run.status() == PlanRunStatus.NEED_INPUT) {
			return INPUT_NODE;
		}
		if (run.terminal()) {
			return END;
		}
		return run.failedStepIds().isEmpty() ? EXECUTE_NODE : REPLAN_NODE;
	}

	// ---------------------------------------------------------------- execution

	private Map<String, Object> execute(OverAllState state) {
		Ledger ledger = new Ledger(read(state));
		if (ledger.status != PlanRunStatus.RUNNING || !ledger.failedStepIds.isEmpty()) {
			return Map.of();
		}
		if (deadlinePassed(ledger)) {
			return write(ledger.terminate(PlanRunStatus.DEADLINE_EXCEEDED, PlanReasonCodes.DEADLINE_EXCEEDED, null));
		}
		List<PlanStep> ready = ledger.readySteps();
		if (ready.isEmpty()) {
			return write(ledger.allCompleted()
					? ledger.terminate(PlanRunStatus.COMPLETED, PlanReasonCodes.PLAN_COMPLETED, null)
					: ledger.terminate(PlanRunStatus.FAILED, PlanReasonCodes.NO_READY_STEPS, null));
		}
		int budget = this.limits.maxStepExecutions() - ledger.stepExecutions;
		if (budget <= 0) {
			return write(ledger.terminate(PlanRunStatus.BUDGET_EXHAUSTED, PlanReasonCodes.BUDGET_EXHAUSTED, null));
		}
		List<PlanStep> batch = ready.subList(0, Math.min(ready.size(), Math.min(budget, this.limits.maxParallelism())));

		Map<String, CompletableFuture<PlanStepOutcome>> running = new LinkedHashMap<>();
		Map<String, Integer> attempts = new LinkedHashMap<>();
		for (PlanStep step : batch) {
			ledger.stepExecutions++;
			int attempt = ledger.attempts.merge(step.stepId(), 1, Integer::sum);
			attempts.put(step.stepId(), attempt);
			PlanStepRequest request = new PlanStepRequest(ledger.objective(), ledger.revision(), step,
					stepKey(step), attempt, ledger.dependencyResults(step), ledger.inputs.get(step.stepId()));
			running.put(step.stepId(), CompletableFuture.supplyAsync(() -> invoke(request), this.executor));
		}

		boolean deadlineHit = false;
		Map<String, PlanStepOutcome> outcomes = new LinkedHashMap<>();
		for (Map.Entry<String, CompletableFuture<PlanStepOutcome>> entry : running.entrySet()) {
			PlanStepOutcome outcome;
			try {
				Long remaining = remainingMillis(ledger);
				outcome = remaining == null ? entry.getValue().get()
						: entry.getValue().get(Math.max(0, remaining), TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException ex) {
				entry.getValue().cancel(true);
				deadlineHit = true;
				outcome = PlanStepOutcome.failed(ToolOutcome.TIMEOUT, "Plan deadline passed while the step was running");
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				entry.getValue().cancel(true);
				outcome = PlanStepOutcome.failed(ToolOutcome.CANCELLED, "Plan execution was interrupted");
			}
			catch (ExecutionException ex) {
				outcome = PlanStepOutcome.failed(ToolOutcome.ERROR, describe(ex.getCause()));
			}
			outcomes.put(entry.getKey(), outcome);
		}

		Decision decision = null;
		for (PlanStep step : batch) {
			PlanStepOutcome outcome = outcomes.get(step.stepId());
			if (outcome.effectReference() != null) {
				ledger.effectReferences.put(step.stepId(), outcome.effectReference());
			}
			Decision next = switch (outcome.kind()) {
				case SUCCEEDED -> {
					String problem = outputProblem(step, outcome.result());
					if (problem == null) {
						ledger.complete(step, outcome.result());
						yield null;
					}
					yield decide(step, ToolOutcome.ERROR, problem, attempts.get(step.stepId()),
							outcome.result() == null ? PlanReasonCodes.STEP_RESULT_MISSING
									: PlanReasonCodes.STEP_OUTPUT_INVALID);
				}
				case NEED_INPUT -> new Decision(PlanFailurePolicy.Action.NEED_INPUT, step, outcome.error(),
						PlanReasonCodes.NEED_INPUT);
				case FAILED -> decide(step, outcome.outcome(), outcome.error(), attempts.get(step.stepId()),
						PlanReasonCodes.STEP_FAILED);
			};
			if (next != null && next.action() == PlanFailurePolicy.Action.REPLAN) {
				ledger.fail(step, next.detail());
			}
			if (next != null && next.action() == PlanFailurePolicy.Action.STOP) {
				ledger.fail(step, next.detail());
			}
			if (next != null && (decision == null || priority(next.action()) > priority(decision.action()))) {
				decision = next;
			}
		}

		if (deadlineHit) {
			ledger.failedStepIds.clear();
			return write(ledger.terminate(PlanRunStatus.DEADLINE_EXCEEDED, PlanReasonCodes.DEADLINE_EXCEEDED, null));
		}
		if (decision == null || decision.action() == PlanFailurePolicy.Action.RETRY) {
			return write(ledger.allCompleted()
					? ledger.terminate(PlanRunStatus.COMPLETED, PlanReasonCodes.PLAN_COMPLETED, null) : ledger);
		}
		return write(switch (decision.action()) {
			case STOP -> {
				ledger.failedStepIds.clear();
				yield ledger.terminate(PlanRunStatus.FAILED, decision.reasonCode(),
						decision.step().stepId() + ": " + decision.detail());
			}
			case REPLAN -> ledger.mark(PlanRunStatus.RUNNING, decision.reasonCode(),
					decision.step().stepId() + ": " + decision.detail());
			case NEED_INPUT -> ledger.await(decision.step(), decision.detail());
			case RETRY -> ledger;
		});
	}

	private Decision decide(PlanStep step, String outcome, String error, int attempt, String reasonCode) {
		PlanFailurePolicy.Action action = this.failurePolicy
			.decide(new PlanFailurePolicy.Failure(step, outcome, error, attempt, this.limits.maxStepAttempts()));
		if (action == null) {
			action = PlanFailurePolicy.Action.STOP;
		}
		if (action == PlanFailurePolicy.Action.RETRY && attempt >= this.limits.maxStepAttempts()) {
			action = PlanFailurePolicy.Action.REPLAN;
		}
		return new Decision(action, step, outcome + (error == null ? "" : " - " + error), reasonCode);
	}

	/**
	 * A batch resolves to its most severe continuation: stop, then replan (input
	 * requests of other steps are asked again in the next revision), then input, then
	 * retry. Completed steps of the batch are always kept.
	 */
	private static int priority(PlanFailurePolicy.Action action) {
		return switch (action) {
			case STOP -> 3;
			case REPLAN -> 2;
			case NEED_INPUT -> 1;
			case RETRY -> 0;
		};
	}

	private PlanStepOutcome invoke(PlanStepRequest request) {
		try {
			PlanStepOutcome outcome = this.stepExecutor.execute(request);
			return outcome != null ? outcome
					: PlanStepOutcome.failed(ToolOutcome.ERROR, "Step executor returned no outcome");
		}
		catch (Exception ex) {
			return PlanStepOutcome.failed(ToolOutcome.ERROR, describe(ex));
		}
	}

	private static String outputProblem(PlanStep step, String result) {
		if (result == null) {
			return "Step returned no result";
		}
		JsonNode node;
		try {
			node = PlanCodec.MAPPER.readTree(result);
		}
		catch (RuntimeException ex) {
			return "Step result is not valid JSON";
		}
		Schema schema = PlanCodec.SCHEMAS.getSchema(PlanCodec.MAPPER.readTree(step.outputSchema()));
		return node == null || !schema.validate(node).isEmpty() ? "Step result does not match its output schema"
				: null;
	}

	// ---------------------------------------------------------------- replanning

	private Map<String, Object> replan(OverAllState state) {
		Ledger ledger = new Ledger(read(state));
		if (ledger.status != PlanRunStatus.RUNNING || ledger.failedStepIds.isEmpty()) {
			return Map.of();
		}
		if (deadlinePassed(ledger)) {
			return write(ledger.terminate(PlanRunStatus.DEADLINE_EXCEEDED, PlanReasonCodes.DEADLINE_EXCEEDED, null));
		}
		if (ledger.replans >= this.limits.maxReplans()) {
			return write(ledger.terminate(PlanRunStatus.REPLAN_LIMIT_EXCEEDED, PlanReasonCodes.REPLAN_LIMIT_EXCEEDED,
					ledger.detail));
		}
		if (this.replanner == null) {
			return write(ledger.terminate(PlanRunStatus.FAILED, PlanReasonCodes.REPLAN_UNAVAILABLE, ledger.detail));
		}
		ledger.replans++;
		PlanSpec current = ledger.toRun().plan();
		PlanSpec proposed;
		try {
			proposed = this.replanner.replan(new PlanReplanner.Request(current, ledger.failedStepIds,
					ledger.toRun().results(), current.revision() + 1, ledger.replans));
		}
		catch (Exception ex) {
			return write(ledger.terminate(PlanRunStatus.FAILED, PlanReasonCodes.REPLAN_FAILED, describe(ex)));
		}
		if (proposed == null) {
			return write(ledger.terminate(PlanRunStatus.REPLAN_REJECTED, PlanReasonCodes.REPLAN_EMPTY, null));
		}
		Rejection rejection = check(ledger, current, proposed);
		if (rejection != null) {
			return write(ledger.terminate(rejection.status(), rejection.reasonCode(), rejection.detail()));
		}
		return write(ledger.adopt(proposed));
	}

	private Rejection check(Ledger ledger, PlanSpec current, PlanSpec proposed) {
		try {
			this.validator.validateGenerated(proposed);
		}
		catch (PlanValidationException ex) {
			return new Rejection(PlanRunStatus.INVALID_PLAN, PlanReasonCodes.REPLAN_INVALID, ex.getMessage());
		}
		if (proposed.revision() != current.revision() + 1 || !proposed.objective().equals(current.objective())) {
			return new Rejection(PlanRunStatus.INVALID_PLAN, PlanReasonCodes.REPLAN_INVALID,
					"Proposal must keep the objective and use revision " + (current.revision() + 1));
		}
		Map<String, PlanStep> proposedSteps = new LinkedHashMap<>();
		proposed.steps().forEach(step -> proposedSteps.put(step.stepId(), step));
		for (PlanStep step : ledger.steps.values()) {
			if (step.status() == PlanStep.Status.COMPLETED) {
				PlanStep retained = proposedSteps.get(step.stepId());
				if (retained == null) {
					return new Rejection(PlanRunStatus.INVALID_PLAN, PlanReasonCodes.COMPLETED_STEP_DROPPED,
							step.stepId());
				}
				if (!identity(retained).equals(identity(step))) {
					return new Rejection(PlanRunStatus.INVALID_PLAN, PlanReasonCodes.STEP_IDENTITY_REUSED,
							step.stepId());
				}
			}
		}
		boolean introducesWork = false;
		boolean pendingWork = false;
		Set<String> failedDefinitions = new HashSet<>(ledger.failedDefinitions);
		ledger.failedStepIds.forEach(id -> failedDefinitions.add(definition(ledger.steps.get(id))));
		for (PlanStep step : proposed.steps()) {
			PlanStep existing = ledger.steps.get(step.stepId());
			if (existing != null && existing.status() == PlanStep.Status.COMPLETED) {
				continue;
			}
			pendingWork = true;
			if (ledger.retiredStepIds.contains(step.stepId()) || ledger.failedStepIds.contains(step.stepId())
					|| (existing != null && !identity(existing).equals(identity(step)))) {
				return new Rejection(PlanRunStatus.INVALID_PLAN, PlanReasonCodes.STEP_IDENTITY_REUSED, step.stepId());
			}
			if (failedDefinitions.contains(definition(step))) {
				return new Rejection(PlanRunStatus.REPLAN_REJECTED, PlanReasonCodes.REPLAN_NO_PROGRESS,
						step.stepId());
			}
			introducesWork |= existing == null;
		}
		if (!pendingWork) {
			return new Rejection(PlanRunStatus.REPLAN_REJECTED, PlanReasonCodes.REPLAN_EMPTY, null);
		}
		if (!introducesWork) {
			return new Rejection(PlanRunStatus.REPLAN_REJECTED, PlanReasonCodes.REPLAN_NO_PROGRESS,
					"Proposal only drops failed work");
		}
		return null;
	}

	// ---------------------------------------------------------------- helpers

	private boolean deadlinePassed(Ledger ledger) {
		Long remaining = remainingMillis(ledger);
		return remaining != null && remaining <= 0;
	}

	private Long remainingMillis(Ledger ledger) {
		if (this.limits.deadline() == null) {
			return null;
		}
		return ledger.startedAtMillis + this.limits.deadline().toMillis() - this.clock.millis();
	}

	private static Map<String, Object> write(Ledger ledger) {
		return Map.of(RUN_KEY, encode(ledger.toRun()));
	}

	private static String encode(PlanRun run) {
		return PlanCodec.MAPPER.writeValueAsString(run);
	}

	private static String describe(Throwable error) {
		if (error == null) {
			return "Unknown failure";
		}
		return error.getMessage() == null ? error.getClass().getName()
				: error.getClass().getSimpleName() + ": " + error.getMessage();
	}

	/**
	 * Stable step identity: the ID plus every field that determines what the step does.
	 */
	static String stepKey(PlanStep step) {
		return step.stepId() + "#" + identity(step).substring(0, 16);
	}

	private static String identity(PlanStep step) {
		return digest(Arrays.asList(step.stepId(), step.objective(), step.dependencies(), step.executionTarget(),
				step.inputSchema(), step.outputSchema(), step.completionCriteria()));
	}

	/** What a step does, independent of its ID and position in the graph. */
	private static String definition(PlanStep step) {
		return digest(Arrays.asList(step.objective(), step.executionTarget(), step.inputSchema(), step.outputSchema(),
				step.completionCriteria()));
	}

	private static String digest(List<Object> fields) {
		try {
			byte[] bytes = PlanCodec.MAPPER.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private record Decision(PlanFailurePolicy.Action action, PlanStep step, String detail, String reasonCode) {

	}

	private record Rejection(PlanRunStatus status, String reasonCode, String detail) {

	}

	private record ProvidedInput(String stepId, String input) {

	}

	/** Interrupts before applying until input for the awaited step is present. */
	private final class InputNode implements AsyncNodeActionWithConfig, InterruptableAction {

		@Override
		public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
			PlanRun run = read(state);
			if (run.status() != PlanRunStatus.NEED_INPUT) {
				return Optional.empty();
			}
			ProvidedInput input = provided(state);
			if (input != null && run.awaitingStepId().equals(input.stepId())) {
				return Optional.empty();
			}
			return Optional.of(InterruptionMetadata.builder(nodeId, state)
				.addMetadata(INTERRUPT_STEP_ID, run.awaitingStepId())
				.addMetadata(INTERRUPT_PROMPT, run.awaitingPrompt() == null ? "" : run.awaitingPrompt())
				.addMetadata(INTERRUPT_REASON, run.reasonCode())
				.build());
		}

		@Override
		public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
			Ledger ledger = new Ledger(read(state));
			ProvidedInput input = provided(state);
			if (ledger.status == PlanRunStatus.NEED_INPUT && input != null
					&& input.stepId().equals(ledger.awaitingStepId)) {
				if (deadlinePassed(ledger)) {
					ledger.terminate(PlanRunStatus.DEADLINE_EXCEEDED, PlanReasonCodes.DEADLINE_EXCEEDED, null);
				}
				else if (validJson(input.input())) {
					ledger.inputs.put(input.stepId(), input.input());
					ledger.awaitingStepId = null;
					ledger.awaitingPrompt = null;
					ledger.mark(PlanRunStatus.RUNNING, PlanReasonCodes.INPUT_RECEIVED, null);
				}
				else {
					ledger.mark(PlanRunStatus.NEED_INPUT, PlanReasonCodes.INPUT_INVALID, input.stepId());
				}
			}
			Map<String, Object> update = new LinkedHashMap<>(write(ledger));
			update.put(INPUT_KEY, OverAllState.MARK_FOR_REMOVAL);
			return CompletableFuture.completedFuture(update);
		}

		private ProvidedInput provided(OverAllState state) {
			Object value = state.data().get(INPUT_KEY);
			if (!(value instanceof String json)) {
				return null;
			}
			try {
				return PlanCodec.MAPPER.readValue(json, ProvidedInput.class);
			}
			catch (RuntimeException ex) {
				return null;
			}
		}

		private boolean validJson(String json) {
			if (json == null) {
				return false;
			}
			try {
				return PlanCodec.MAPPER.readTree(json) != null;
			}
			catch (RuntimeException ex) {
				return false;
			}
		}

	}

	/** Mutable working copy of a {@link PlanRun}, confined to one node invocation. */
	private static final class Ledger {

		PlanRunStatus status;

		String reasonCode;

		String detail;

		final int schemaVersion;

		long revision;

		final String objective;

		final LinkedHashMap<String, PlanStep> steps = new LinkedHashMap<>();

		final List<Long> revisions;

		int replans;

		int stepExecutions;

		final long startedAtMillis;

		final Map<String, Integer> attempts;

		final Map<String, String> effectReferences;

		final Set<String> retiredStepIds;

		final Set<String> failedDefinitions;

		final List<String> failedStepIds;

		String awaitingStepId;

		String awaitingPrompt;

		final Map<String, String> inputs;

		Ledger(PlanSpec plan, long startedAtMillis) {
			this(new PlanRun(PlanRunStatus.RUNNING, PlanReasonCodes.STARTED, null, plan, List.of(plan.revision()), 0,
					0, startedAtMillis, null, null, null, null, null, null, null, null));
		}

		Ledger(PlanRun run) {
			this.status = run.status();
			this.reasonCode = run.reasonCode();
			this.detail = run.detail();
			this.schemaVersion = run.plan().schemaVersion();
			this.revision = run.plan().revision();
			this.objective = run.plan().objective();
			run.plan().steps().forEach(step -> this.steps.put(step.stepId(), step));
			this.revisions = new ArrayList<>(run.revisions());
			this.replans = run.replans();
			this.stepExecutions = run.stepExecutions();
			this.startedAtMillis = run.startedAtMillis();
			this.attempts = new LinkedHashMap<>(run.attempts());
			this.effectReferences = new LinkedHashMap<>(run.effectReferences());
			this.retiredStepIds = new LinkedHashSet<>(run.retiredStepIds());
			this.failedDefinitions = new LinkedHashSet<>(run.failedDefinitions());
			this.failedStepIds = new ArrayList<>(run.failedStepIds());
			this.awaitingStepId = run.awaitingStepId();
			this.awaitingPrompt = run.awaitingPrompt();
			this.inputs = new LinkedHashMap<>(run.inputs());
		}

		String objective() {
			return this.objective;
		}

		long revision() {
			return this.revision;
		}

		List<PlanStep> readySteps() {
			List<PlanStep> ready = new ArrayList<>();
			for (PlanStep step : this.steps.values()) {
				if (step.status() == PlanStep.Status.PENDING && step.dependencies()
					.stream()
					.allMatch(id -> this.steps.get(id).status() == PlanStep.Status.COMPLETED)) {
					ready.add(step);
				}
			}
			return ready;
		}

		boolean allCompleted() {
			return this.steps.values().stream().allMatch(step -> step.status() == PlanStep.Status.COMPLETED);
		}

		Map<String, String> dependencyResults(PlanStep step) {
			Map<String, String> results = new LinkedHashMap<>();
			step.dependencies().forEach(id -> results.put(id, this.steps.get(id).result()));
			return results;
		}

		void complete(PlanStep step, String result) {
			this.steps.put(step.stepId(), withOutcome(step, PlanStep.Status.COMPLETED, result, null));
		}

		void fail(PlanStep step, String error) {
			this.steps.put(step.stepId(), withOutcome(step, PlanStep.Status.FAILED, null, error));
			if (!this.failedStepIds.contains(step.stepId())) {
				this.failedStepIds.add(step.stepId());
			}
		}

		Ledger await(PlanStep step, String prompt) {
			this.awaitingStepId = step.stepId();
			this.awaitingPrompt = prompt;
			return mark(PlanRunStatus.NEED_INPUT, PlanReasonCodes.NEED_INPUT, step.stepId());
		}

		Ledger mark(PlanRunStatus status, String reasonCode, String detail) {
			this.status = status;
			this.reasonCode = reasonCode;
			this.detail = detail;
			return this;
		}

		Ledger terminate(PlanRunStatus status, String reasonCode, String detail) {
			this.awaitingStepId = null;
			this.awaitingPrompt = null;
			return mark(status, reasonCode, detail);
		}

		/** Adopt a checked proposal: completed outcomes come from this ledger only. */
		Ledger adopt(PlanSpec proposed) {
			Set<String> kept = new HashSet<>();
			LinkedHashMap<String, PlanStep> next = new LinkedHashMap<>();
			for (PlanStep step : proposed.steps()) {
				PlanStep existing = this.steps.get(step.stepId());
				boolean completed = existing != null && existing.status() == PlanStep.Status.COMPLETED;
				next.put(step.stepId(), completed ? existing : step);
				kept.add(step.stepId());
			}
			for (PlanStep step : this.steps.values()) {
				if (!kept.contains(step.stepId())) {
					this.retiredStepIds.add(step.stepId());
					this.attempts.remove(step.stepId());
					this.inputs.remove(step.stepId());
				}
				if (step.status() == PlanStep.Status.FAILED) {
					this.failedDefinitions.add(definition(step));
				}
			}
			this.steps.clear();
			this.steps.putAll(next);
			this.revision = proposed.revision();
			this.revisions.add(proposed.revision());
			this.failedStepIds.clear();
			return mark(PlanRunStatus.RUNNING, PlanReasonCodes.REPLANNED, "revision " + proposed.revision());
		}

		PlanRun toRun() {
			PlanSpec plan = new PlanSpec(this.schemaVersion, this.revision, this.objective,
					new ArrayList<>(this.steps.values()));
			return new PlanRun(this.status, this.reasonCode, this.detail, plan, this.revisions, this.replans,
					this.stepExecutions, this.startedAtMillis, this.attempts, this.effectReferences,
					new ArrayList<>(this.retiredStepIds), new ArrayList<>(this.failedDefinitions), this.failedStepIds,
					this.awaitingStepId, this.awaitingPrompt, this.inputs);
		}

		private static PlanStep withOutcome(PlanStep step, PlanStep.Status status, String result, String error) {
			return new PlanStep(step.stepId(), step.objective(), step.dependencies(), step.executionTarget(),
					step.inputSchema(), step.outputSchema(), step.completionCriteria(), status, result, error);
		}

	}

	/** Builder of an opt-in plan executor. */
	public static final class Builder {

		private final PlanValidator validator;

		private final PlanStepExecutor stepExecutor;

		private PlanReplanner replanner;

		private PlanFailurePolicy failurePolicy = PlanFailurePolicy.defaults();

		private PlanExecutionLimits limits = PlanExecutionLimits.defaults();

		private Clock clock = Clock.systemUTC();

		private Executor executor = ForkJoinPool.commonPool();

		private Builder(PlanValidator validator, PlanStepExecutor stepExecutor) {
			this.validator = Objects.requireNonNull(validator, "validator");
			this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor");
		}

		/** Without a replanner, a {@code REPLAN} decision terminates the run as failed. */
		public Builder replanner(PlanReplanner replanner) {
			this.replanner = replanner;
			return this;
		}

		public Builder failurePolicy(PlanFailurePolicy failurePolicy) {
			this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
			return this;
		}

		public Builder limits(PlanExecutionLimits limits) {
			this.limits = Objects.requireNonNull(limits, "limits");
			return this;
		}

		public Builder clock(Clock clock) {
			this.clock = Objects.requireNonNull(clock, "clock");
			return this;
		}

		/** Executor for local parallel batches; use a dedicated one for blocking steps. */
		public Builder executor(Executor executor) {
			this.executor = Objects.requireNonNull(executor, "executor");
			return this;
		}

		public PlanExecutor build() {
			return new PlanExecutor(this);
		}

	}

}
