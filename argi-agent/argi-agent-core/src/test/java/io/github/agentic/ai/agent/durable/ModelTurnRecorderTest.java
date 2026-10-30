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
package io.github.agentic.ai.agent.durable;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.runtime.execution.definition.DefinitionKind;
import io.github.agentic.ai.runtime.execution.definition.DefinitionReference;
import io.github.agentic.ai.runtime.execution.run.MemoryRunStore;
import io.github.agentic.ai.runtime.execution.run.RunCommit;
import io.github.agentic.ai.runtime.execution.run.RunEntry;
import io.github.agentic.ai.runtime.execution.run.RunLease;
import io.github.agentic.ai.runtime.execution.run.RunNamespace;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunSnapshot;
import io.github.agentic.ai.runtime.execution.run.RunSpec;
import io.github.agentic.ai.runtime.execution.run.RunState;
import io.github.agentic.ai.runtime.execution.run.RunStore;
import io.github.agentic.ai.runtime.execution.run.RunStoreCapabilities;
import io.github.agentic.ai.runtime.execution.run.RunStoreConflictException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The neutral model-turn contract, exercised without any graph or streaming engine. */
@Timeout(10)
class ModelTurnRecorderTest {

	private static final String RUN_ID = "run-1";

	private static final ModelTurnRecorder.Turn TURN = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");

	private static final List<ToolDefinition> TOOLS = List.of(
			new ToolDefinition("search", "search the web", "{\"type\":\"object\"}"));

	private static final ChatRequest REQUEST = new ChatRequest(List.of(ChatMessage.user("search for argi")),
			TOOLS, null, Map.of());

	private static final ChatResponse RESPONSE = toolCallingResponse("call-1");

	private final MemoryRunStore store = new MemoryRunStore();

	private RunStore runtimeStore = store;

	private ModelTurnRecorder recorder = new ModelTurnRecorder();

	private RunSpec run;

	private RunLease lease;

	private RunSnapshot snapshot;

	@BeforeEach
	void setUp() {
		TrustedExecutionContext context = TrustedExecutionContext.builder()
				.tenant(new TenantReference("iam", "tenant"))
				.principal(new PrincipalReference("iam", "user"))
				.runId(RUN_ID)
				.correlationId("trace")
				.build();
		run = RunSpec.from(context, "app", "session", "submission",
				DefinitionReference.of(DefinitionKind.AGENT, "agent", "1"), new RunPayload(1, "test.input", "{}"));
		store.submit(run, context);
		lease = store.acquire(run.namespace(), RUN_ID, UUID.randomUUID(), Duration.ofMinutes(1));
		snapshot = store.commit(run.namespace(), RUN_ID, lease,
				new RunCommit(0, RunState.RUNNING, new RunPayload(1, "test.checkpoint", "{\"phase\":\"MODEL_READY\"}"),
						List.of(), List.of(), List.of()));
	}

	@Test
	void preparationIsImmutableAndHasNoStorageSideEffects() {
		RunSnapshot before = snapshot;
		List<List<RunEntry>> batches = List.of(recorder.prepareRequest(TURN, REQUEST),
				recorder.prepareAttempt(TURN, REQUEST, snapshot), recorder.prepareResponse(TURN, RESPONSE, TOOLS),
				recorder.prepareDelta(TURN, ModelTurnRecorder.newAttempt(), 0, "hello"));

		for (List<RunEntry> batch : batches) {
			assertThatThrownBy(batch::clear).isInstanceOf(UnsupportedOperationException.class);
		}
		assertThat(recorder.prepareAttempt(TURN, REQUEST, snapshot)).isEqualTo(batches.get(1));
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void toolPlansIgnoreFormattingAndProviderCallIdsWhilePreservingArrayOrder() {
		String first = ModelTurnCodec.encodeToolPlan(
				new ToolCall("call-a", "search", "{\"q\":\"x\",\"p\":\"y\"}"), TOOLS.get(0), 0);
		String second = ModelTurnCodec.encodeToolPlan(
				new ToolCall("call-b", "search", "{ \"p\" : \"y\", \"q\" : \"x\" }"), TOOLS.get(0), 0);

		assertThat(second).isEqualTo(first);
		assertThat(ModelTurnCodec.canonicalArguments("{\"tags\":[\"b\",\"a\"]}"))
				.isEqualTo("{\"tags\":[\"b\",\"a\"]}");
	}

	@Test
	void referenceDispatchesOnlyAfterTheResponseAllPlansAndCheckpointAreCommitted() {
		List<ToolCall> calls = List.of(new ToolCall("first", "search", "{\"q\":\"first\"}"),
				new ToolCall("second", "search", "{\"q\":\"second\"}"));
		ChatResponse response = new ChatResponse("response", "model", List.of(ChatMessage.assistant("", calls)),
				calls, FinishReason.TOOL_CALLS, null, Map.of());
		AtomicInteger tools = new AtomicInteger();

		executeReference(() -> {
			assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req", "agent:0#attempt.1");
			return response;
		}, call -> {
			RunSnapshot committed = store.find(run.namespace(), RUN_ID).orElseThrow();
			assertThat(committed.checkpoint()).isEqualTo(checkpoint());
			assertThat(committed.journal()).containsOnlyKeys("agent:0#req", "agent:0#attempt.1",
					"agent:0#resp", "agent:0#task.0", "agent:0#task.1");
			tools.incrementAndGet();
		});

		assertThat(tools.get()).isEqualTo(2);
	}

	@ParameterizedTest
	@CsvSource({ "argi.model-request, 0", "argi.model-attempt, 0", "argi.model-response, 1" })
	void commitFailurePreventsSubsequentSideEffects(String payloadType, int expectedModelCalls) {
		IllegalStateException unavailable = new IllegalStateException("storage unavailable");
		runtimeStore = new FailingRunStore(store, payloadType, false, unavailable);
		RunPayload before = snapshot.checkpoint();
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger tools = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(countedModel(modelCalls), call -> tools.incrementAndGet()))
				.isSameAs(unavailable);

		assertThat(modelCalls.get()).isEqualTo(expectedModelCalls);
		assertThat(tools.get()).isZero();
		assertThat(snapshot.checkpoint()).isEqualTo(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(snapshot);
	}

	@Test
	void lostAttemptConfirmationDoesNotInvokeTheModelOrRefundTheReservation() {
		IllegalStateException lost = new IllegalStateException("attempt confirmation lost");
		runtimeStore = new FailingRunStore(store, ModelTurnCodec.ATTEMPT_TYPE, true, lost);
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger tools = new AtomicInteger();
		RunSnapshot before = snapshot;

		assertThatThrownBy(() -> executeReference(1, countedModel(modelCalls), call -> tools.incrementAndGet()))
				.isSameAs(lost);
		assertThat(snapshot).isSameAs(before);

		runtimeStore = store;
		reload();
		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req", "agent:0#attempt.1");
		assertThatThrownBy(() -> executeReference(1, countedModel(modelCalls), call -> tools.incrementAndGet()))
				.isInstanceOf(ModelTurnJournalException.class).hasMessageContaining("budget exhausted");
		assertThat(modelCalls.get()).isZero();
		assertThat(tools.get()).isZero();
	}

	@Test
	void lostResponseConfirmationRecoversTheSamePlanWithoutAnotherModelCall() {
		IllegalStateException lost = new IllegalStateException("response confirmation lost");
		runtimeStore = new FailingRunStore(store, ModelTurnCodec.RESPONSE_TYPE, true, lost);
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger tools = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(countedModel(modelCalls), call -> tools.incrementAndGet()))
				.isSameAs(lost);
		assertThat(tools.get()).isZero();

		runtimeStore = store;
		reload();
		RunEntry plan = snapshot.journal().get("agent:0#task.0");
		RunSnapshot committed = snapshot;
		ChatResponse recovered = executeReference(1, () -> {
			modelCalls.incrementAndGet();
			return toolCallingResponse("different-provider-id");
		}, call -> tools.incrementAndGet());

		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(tools.get()).isEqualTo(1);
		assertThat(recovered.toolCalls()).isEqualTo(RESPONSE.toolCalls());
		assertThat(snapshot.journal()).containsEntry("agent:0#task.0", plan);
		assertThat(snapshot).isSameAs(committed);
	}

	@Test
	void aStaleSnapshotCannotAuthorizeAnotherModelCall() {
		RunSnapshot stale = snapshot;
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot));
		RunSnapshot committed = snapshot;
		snapshot = stale;
		AtomicInteger modelCalls = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(countedModel(modelCalls), call -> {
			throw new AssertionError("No tool may run after a stale reservation");
		})).isInstanceOf(RunStoreConflictException.class);

		assertThat(modelCalls.get()).isZero();
		assertThat(snapshot).isSameAs(stale);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(committed);
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 3 })
	void committedReservationsSurviveReloadsAndEnforceTheConfiguredLimit(int maxAttempts) {
		for (int ordinal = 1; ordinal <= maxAttempts; ordinal++) {
			commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot, maxAttempts));
			reload();
			assertThat(snapshot.journal()).containsKey("agent:0#attempt." + ordinal);
		}
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger tools = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(maxAttempts, countedModel(modelCalls),
				call -> tools.incrementAndGet())).isInstanceOf(ModelTurnJournalException.class)
				.hasMessageContaining("budget exhausted");

		assertThat(modelCalls.get()).isZero();
		assertThat(tools.get()).isZero();
	}

	@Test
	void aLostResponseCanBeReaskedOnceByDefaultAfterReload() {
		IllegalStateException unavailable = new IllegalStateException("response storage unavailable");
		runtimeStore = new FailingRunStore(store, ModelTurnCodec.RESPONSE_TYPE, false, unavailable);
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger tools = new AtomicInteger();
		Supplier<ChatResponse> model = () -> toolCallingResponse("provider-call-" + modelCalls.incrementAndGet());

		assertThatThrownBy(() -> executeReference(model, call -> tools.incrementAndGet())).isSameAs(unavailable);
		assertThat(tools.get()).isZero();

		runtimeStore = store;
		reload();
		ChatResponse recovered = executeReference(model, call -> tools.incrementAndGet());

		assertThat(modelCalls.get()).isEqualTo(2);
		assertThat(tools.get()).isEqualTo(1);
		assertThat(recovered.toolCalls()).extracting(ToolCall::id).containsExactly("provider-call-2");
		assertThat(snapshot.journal()).containsKeys("agent:0#attempt.1", "agent:0#attempt.2", "agent:0#task.0");
	}

	@Test
	void aLegacyRequestCountsAsTheFirstReservation() {
		commit(null, recorder.prepareRequest(TURN, REQUEST));
		List<RunEntry> recovery = recorder.prepareAttempt(TURN, REQUEST, snapshot);
		assertThat(recovery).extracting(RunEntry::id).containsExactly("agent:0#attempt.2");

		commit(null, recovery);
		reload();

		assertThatThrownBy(() -> recorder.prepareAttempt(TURN, REQUEST, snapshot))
				.isInstanceOf(ModelTurnJournalException.class).hasMessageContaining("budget exhausted");
	}

	@Test
	void differentTurnsHaveIndependentAttemptBudgets() {
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot, 1));
		ModelTurnRecorder.Turn next = new ModelTurnRecorder.Turn(RUN_ID, "next");

		assertThat(recorder.prepareAttempt(next, REQUEST, snapshot, 1)).extracting(RunEntry::id)
				.containsExactly("next#req", "next#attempt.1");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void aDifferentRequestCannotReplaceAnInterruptedOrCompletedTurn(boolean completed) {
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot));
		if (completed) {
			commit(checkpoint(), recorder.prepareResponse(TURN, RESPONSE, TOOLS));
		}
		ChatRequest changed = new ChatRequest(List.of(ChatMessage.user("different request")), TOOLS, null, Map.of());

		assertThatThrownBy(() -> recorder.prepareAttempt(TURN, changed, snapshot))
				.isInstanceOf(ModelTurnJournalException.class).hasCauseInstanceOf(RunStoreConflictException.class);
	}

	@Test
	void replayRejectsIncompleteExtraOrMismatchedPlans() {
		List<RunEntry> complete = completeEntries();
		RunEntry request = complete.get(0);
		RunEntry response = complete.get(1);
		RunEntry plan = complete.get(2);
		RunEntry extra = new RunEntry(1, "agent:0#task.1", plan.kind(), plan.payload());
		RunEntry changed = new RunEntry(1, plan.id(), plan.kind(), new RunPayload(1, ModelTurnCodec.PLAN_TYPE,
				ModelTurnCodec.encodeToolPlan(new ToolCall("call-1", "search", "{\"q\":\"different\"}"),
						TOOLS.get(0), 0)));
		List<List<RunEntry>> invalid = List.of(List.of(response, plan), List.of(request, plan),
				List.of(request, response), List.of(request, response, plan, extra),
				List.of(request, response, changed));

		for (List<RunEntry> entries : invalid) {
			RunSnapshot recorded = snapshotWithJournal(entries);
			assertThatThrownBy(() -> recorder.replay(TURN, REQUEST, recorded))
					.isInstanceOf(ModelTurnJournalException.class);
		}
	}

	@Test
	void replayChecksEntryKindsTypesAndVersions() {
		List<RunEntry> complete = completeEntries();
		complete.add(recorder.prepareAttempt(TURN, REQUEST, snapshot).get(1));
		for (int index = 0; index < complete.size(); index++) {
			RunEntry original = complete.get(index);
			List<RunEntry> incompatible = List.of(
					new RunEntry(1, original.id(), RunEntry.Kind.EVENT, original.payload()),
					new RunEntry(1, original.id(), original.kind(),
							new RunPayload(1, "other.type", original.payload().json())),
					new RunEntry(1, original.id(), original.kind(),
							new RunPayload(2, original.payload().type(), original.payload().json())));
			for (RunEntry replacement : incompatible) {
				List<RunEntry> entries = new ArrayList<>(complete);
				entries.set(index, replacement);
				RunSnapshot invalid = snapshotWithJournal(entries);
				assertThatThrownBy(() -> recorder.replay(TURN, REQUEST, invalid))
						.isInstanceOf(ModelTurnJournalException.class);
			}
		}
	}

	@Test
	void inconsistentAttemptRecordsCannotResetTheBudget() {
		RunEntry request = recorder.prepareRequest(TURN, REQUEST).get(0);
		String hash = ModelTurnCodec.requestHash(REQUEST);
		List<RunEntry> invalid = List.of(
				new RunEntry(1, "agent:0#attempt.3", RunEntry.Kind.MODEL_CALL,
						new RunPayload(1, ModelTurnCodec.ATTEMPT_TYPE, ModelTurnCodec.encodeAttempt(3, hash))),
				new RunEntry(1, "agent:0#attempt.1", RunEntry.Kind.MODEL_CALL,
						new RunPayload(1, ModelTurnCodec.ATTEMPT_TYPE, ModelTurnCodec.encodeAttempt(1, "different"))));

		for (RunEntry attempt : invalid) {
			RunSnapshot recorded = snapshotWithJournal(List.of(request, attempt));
			assertThatThrownBy(() -> recorder.prepareAttempt(TURN, REQUEST, recorded, 5))
					.isInstanceOf(ModelTurnJournalException.class);
		}
	}

	@Test
	void theSameTaskWithDifferentArgumentsConflictsWithoutUpdatingTheCheckpoint() {
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot));
		commit(checkpoint(), recorder.prepareResponse(TURN, RESPONSE, TOOLS));
		RunSnapshot before = snapshot;
		RunEntry changed = new RunEntry(1, "agent:0#task.0", RunEntry.Kind.TASK_CALL,
				new RunPayload(1, ModelTurnCodec.PLAN_TYPE, ModelTurnCodec.encodeToolPlan(
						new ToolCall("call-2", "search", "{\"q\":\"different\"}"), TOOLS.get(0), 0)));

		assertThatThrownBy(() -> commit(new RunPayload(1, "test.checkpoint", "{\"changed\":true}"),
				List.of(changed))).isInstanceOf(RunStoreConflictException.class);
		assertThat(snapshot).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "01", "2147483648" })
	void malformedReservationIdsRemainVisibleToBudgetValidation(String suffix) {
		RunEntry request = recorder.prepareRequest(TURN, REQUEST).get(0);
		RunEntry attempt = new RunEntry(1, TURN.turnId() + "#attempt." + suffix, RunEntry.Kind.MODEL_CALL,
				new RunPayload(1, ModelTurnCodec.ATTEMPT_TYPE,
						ModelTurnCodec.encodeAttempt(1, ModelTurnCodec.requestHash(REQUEST))));
		RunSnapshot recorded = snapshotWithJournal(List.of(request, attempt));

		assertThatThrownBy(() -> recorder.prepareAttempt(TURN, REQUEST, recorded, 5))
				.isInstanceOf(ModelTurnJournalException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "00", "2147483648" })
	void malformedExtraPlanIdsRemainVisibleToReplay(String suffix) {
		List<RunEntry> entries = completeEntries();
		RunEntry plan = entries.get(2);
		entries.add(new RunEntry(1, TURN.turnId() + "#task." + suffix, plan.kind(), plan.payload()));
		RunSnapshot recorded = snapshotWithJournal(entries);

		assertThatThrownBy(() -> recorder.replayTurn(TURN, REQUEST, recorded))
				.isInstanceOf(ModelTurnJournalException.class);
	}

	@Test
	void nestedTurnsDoNotAffectReplayOrReservationCounts() {
		ModelTurnRecorder.Turn nested = new ModelTurnRecorder.Turn(RUN_ID, TURN.turnId() + "#attempt.1#task.0");
		commit(null, recorder.prepareAttempt(nested, REQUEST, snapshot));
		commit(checkpoint(), recorder.prepareResponse(nested, RESPONSE, TOOLS));

		assertThat(recorder.replayTurn(TURN, REQUEST, snapshot)).isEmpty();
		assertThat(recorder.prepareAttempt(TURN, REQUEST, snapshot, 1)).extracting(RunEntry::id)
				.containsExactly("agent:0#req", "agent:0#attempt.1");
		assertThat(recorder.replayTurn(nested, REQUEST, snapshot).orElseThrow().plans().get(0).identity())
				.isEqualTo(new ToolTaskIdentity(RUN_ID, nested.turnId(), 0));
	}

	@Test
	void replayTurnExposesTheSameCommittedPlansOnInitialReadAndRecoveryWithoutWriting() {
		assertThat(recorder.replayTurn(TURN, REQUEST, snapshot)).isEmpty();
		List<ToolCall> calls = List.of(new ToolCall("provider-first", "search", "{\"q\":\"same\"}"),
				new ToolCall("provider-second", "search", "{\"q\":\"same\"}"));
		ChatResponse response = new ChatResponse("response", "model", List.of(ChatMessage.assistant("", calls)),
				calls, FinishReason.TOOL_CALLS, null, Map.of());
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot));
		assertThat(recorder.replayTurn(TURN, REQUEST, snapshot)).isEmpty();
		commit(checkpoint(), recorder.prepareResponse(TURN, response, TOOLS));
		RunSnapshot before = snapshot;

		RecordedModelTurn initial = recorder.replayTurn(TURN, REQUEST, snapshot).orElseThrow();
		reload();
		RecordedModelTurn recovered = recorder.replayTurn(TURN, REQUEST, snapshot).orElseThrow();

		assertThat(recovered).isEqualTo(initial);
		assertThat(recorder.replay(TURN, REQUEST, snapshot)).contains(recovered.response());
		assertThat(recovered.plans()).extracting(PlannedToolCall::identity).containsExactly(
				new ToolTaskIdentity(RUN_ID, TURN.turnId(), 0), new ToolTaskIdentity(RUN_ID, TURN.turnId(), 1));
		assertThat(recovered.plans().get(0).argumentsHash()).isEqualTo(recovered.plans().get(1).argumentsHash());
		for (PlannedToolCall plan : recovered.plans()) {
			assertThat(plan.arguments()).isEqualTo("{\"q\":\"same\"}");
			assertThat(plan.definitionFingerprint()).isEqualTo(ModelTurnCodec.definitionVersion(TOOLS.get(0)));
			assertThat(ModelTurnCodec.decodeToolPlan(TURN, snapshot.journal().get(plan.identity().entryId())))
					.isEqualTo(plan);
			assertThat(recovered.response().toolCalls().get(plan.identity().ordinal()))
					.isEqualTo(calls.get(plan.identity().ordinal()));
		}
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
		assertThatThrownBy(() -> recovered.plans().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void providerCallIdsAndAttemptReservationsDoNotChangePlanIdentity() {
		List<RunEntry> firstEntries = recorder.prepareResponse(TURN, toolCallingResponse("provider-one"), TOOLS);
		PlannedToolCall first = ModelTurnCodec.decodeToolPlan(TURN, firstEntries.get(1));
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot));
		reload();
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot));
		commit(checkpoint(), recorder.prepareResponse(TURN, toolCallingResponse("provider-two"), TOOLS));

		RecordedModelTurn recorded = recorder.replayTurn(TURN, REQUEST, snapshot).orElseThrow();

		assertThat(recorded.plans()).containsExactly(first);
		assertThat(recorded.response().toolCalls().get(0).id()).isEqualTo("provider-two");
	}

	@Test
	void replayTurnRecordsUnknownToolsAndMalformedArgumentsWithoutAnExecutionGate() {
		ToolCall call = new ToolCall("provider", "not-disclosed", "{ invalid JSON");
		ChatResponse response = new ChatResponse("response", "model",
				List.of(ChatMessage.assistant("", List.of(call))), List.of(call), FinishReason.TOOL_CALLS, null, Map.of());
		commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot));
		commit(checkpoint(), recorder.prepareResponse(TURN, response, REQUEST.tools()));

		RecordedModelTurn recorded = recorder.replayTurn(TURN, REQUEST, snapshot).orElseThrow();

		assertThat(recorded.plans()).hasSize(1);
		assertThat(recorded.plans().get(0).toolName()).isEqualTo(call.name());
		assertThat(recorded.plans().get(0).arguments()).isEqualTo(call.arguments());
		assertThat(recorded.plans().get(0).definitionFingerprint()).isNull();
	}

	@Test
	void textOnlyResultsReplayWithoutToolPlans() {
		ChatResponse text = new ChatResponse("response", "model", List.of(ChatMessage.assistant("done")),
				List.of(), FinishReason.STOP, null, Map.of());
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger tools = new AtomicInteger();
		Supplier<ChatResponse> model = () -> {
			modelCalls.incrementAndGet();
			return text;
		};

		assertThat(executeReference(model, call -> tools.incrementAndGet())).isEqualTo(text);
		assertThat(executeReference(model, call -> tools.incrementAndGet())).isEqualTo(text);
		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(tools.get()).isZero();
		assertThat(recorder.replayTurn(TURN, REQUEST, snapshot).orElseThrow().plans()).isEmpty();
	}

	@Test
	void diagnosticDeltasRetainTheCheckpointUntilTheTerminalResponse() {
		RunPayload before = snapshot.checkpoint();
		String attempt = ModelTurnRecorder.newAttempt();
		AtomicInteger tools = new AtomicInteger();

		executeReference(() -> {
			commit(null, recorder.prepareDelta(TURN, attempt, 0, "hello"));
			assertThat(snapshot.checkpoint()).isEqualTo(before);
			assertThat(snapshot.journal()).doesNotContainKeys("agent:0#resp", "agent:0#task.0");
			return RESPONSE;
		}, call -> {
			assertThat(snapshot.checkpoint()).isEqualTo(checkpoint());
			tools.incrementAndGet();
		});

		assertThat(tools.get()).isEqualTo(1);
	}

	/** In-process reference ordering; production recovery and tool receipts remain runtime concerns. */
	private ChatResponse executeReference(Supplier<ChatResponse> model, Consumer<ToolCall> dispatch) {
		return executeReference(ModelTurnRecorder.DEFAULT_MAX_ATTEMPTS, model, dispatch);
	}

	private ChatResponse executeReference(int maxAttempts, Supplier<ChatResponse> model, Consumer<ToolCall> dispatch) {
		ChatResponse response = recorder.replay(TURN, REQUEST, snapshot).orElseGet(() -> {
			commit(null, recorder.prepareAttempt(TURN, REQUEST, snapshot, maxAttempts));
			ChatResponse result = model.get();
			commit(checkpoint(), recorder.prepareResponse(TURN, result, REQUEST.tools()));
			return result;
		});
		response.toolCalls().forEach(dispatch);
		return response;
	}

	private void commit(RunPayload checkpoint, List<RunEntry> entries) {
		snapshot = runtimeStore.commit(run.namespace(), RUN_ID, lease,
				new RunCommit(snapshot.revision(), snapshot.state(), checkpoint, List.of(), entries, List.of()));
	}

	private void reload() {
		snapshot = store.find(run.namespace(), RUN_ID).orElseThrow();
		recorder = new ModelTurnRecorder();
	}

	private List<RunEntry> completeEntries() {
		List<RunEntry> entries = new ArrayList<>(recorder.prepareRequest(TURN, REQUEST));
		entries.addAll(recorder.prepareResponse(TURN, RESPONSE, TOOLS));
		return entries;
	}

	private RunSnapshot snapshotWithJournal(List<RunEntry> entries) {
		Map<String, RunEntry> journal = new HashMap<>();
		entries.forEach(entry -> journal.put(entry.id(), entry));
		return new RunSnapshot(snapshot.spec(), snapshot.revision(), snapshot.state(), snapshot.checkpoint(),
				snapshot.tasks(), journal, snapshot.outbox());
	}

	private static RunPayload checkpoint() {
		return new RunPayload(1, "test.checkpoint", "{\"phase\":\"TOOLS_PENDING\",\"turnId\":\"agent:0\"}");
	}

	private static Supplier<ChatResponse> countedModel(AtomicInteger calls) {
		return () -> {
			calls.incrementAndGet();
			return RESPONSE;
		};
	}

	private static ChatResponse toolCallingResponse(String callId) {
		ToolCall call = new ToolCall(callId, "search", "{\"q\":\"argi\"}");
		return new ChatResponse("response", "test-model", List.of(ChatMessage.assistant("", List.of(call))),
				List.of(call), FinishReason.TOOL_CALLS, null, Map.of());
	}

	/** Simulates definite rejection or a successful write whose acknowledgement is lost. */
	private record FailingRunStore(RunStore delegate, String payloadType, boolean commitBeforeFailure,
			RuntimeException failure) implements RunStore {

		@Override
		public RunStoreCapabilities capabilities() {
			return delegate.capabilities();
		}

		@Override
		public RunSnapshot submit(RunSpec spec, TrustedExecutionContext context) {
			return delegate.submit(spec, context);
		}

		@Override
		public Optional<RunSnapshot> find(RunNamespace namespace, String runId) {
			return delegate.find(namespace, runId);
		}

		@Override
		public Optional<String> activeRun(RunNamespace namespace) {
			return delegate.activeRun(namespace);
		}

		@Override
		public RunLease acquire(RunNamespace namespace, String runId, UUID ownerId, Duration ttl) {
			return delegate.acquire(namespace, runId, ownerId, ttl);
		}

		@Override
		public RunLease renew(RunLease lease, Duration ttl) {
			return delegate.renew(lease, ttl);
		}

		@Override
		public boolean release(RunLease lease) {
			return delegate.release(lease);
		}

		@Override
		public RunSnapshot commit(RunNamespace namespace, String runId, RunLease lease, RunCommit commit) {
			if (commit.journal().stream().anyMatch(entry -> payloadType.equals(entry.payload().type()))) {
				if (commitBeforeFailure) {
					delegate.commit(namespace, runId, lease, commit);
				}
				throw failure;
			}
			return delegate.commit(namespace, runId, lease, commit);
		}
	}

}
