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
import io.github.agentic.ai.api.chat.ChatRole;
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

	private static final List<ChatMessage> FIRST_TURN = List.of(ChatMessage.user("search for argi"));

	private static final List<ToolDefinition> TOOLS = List.of(
			new ToolDefinition("search", "search the web", "{\"type\":\"object\"}"));

	private final MemoryRunStore store = new MemoryRunStore();

	private RunSpec run;

	private RunLease lease;

	private RunStore runtimeStore = store;

	private RunSnapshot snapshot;

	private ModelTurnRecorder recorder;

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
				new RunCommit(0, RunState.RUNNING,
						new RunPayload(1, "test.checkpoint", "{\"phase\":\"MODEL_READY\"}"), List.of(), List.of(),
						List.of()));
		recorder = new ModelTurnRecorder();
	}

	@Test
	void preparesOnlyTheRequestFingerprintWithoutWriting() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunSnapshot before = snapshot;

		List<RunEntry> entries = recorder.prepareRequest(turn, request(FIRST_TURN));

		assertThat(entries).hasSize(1);
		assertThatThrownBy(entries::clear).isInstanceOf(UnsupportedOperationException.class);
		RunEntry recorded = entries.get(0);
		assertThat(recorded.id()).isEqualTo("agent:0#req");
		assertThat(recorded.schemaVersion()).isEqualTo(1);
		assertThat(recorded.kind()).isEqualTo(RunEntry.Kind.MODEL_CALL);
		assertThat(recorded.payload().type()).isEqualTo(ModelTurnCodec.REQUEST_TYPE);
		// The request body is never persisted: only the fingerprint is.
		assertThat(recorded.payload().json()).contains("requestHash").doesNotContain("search for argi");
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void equivalentToolArgumentsProduceTheSameHashAndRecord() {
		assertThat(ModelTurnCodec.canonicalArguments("{\"q\":\"x\",\"p\":\"y\"}")).isEqualTo("{\"p\":\"y\",\"q\":\"x\"}");
		assertThat(ModelTurnCodec.canonicalArguments("{ \"p\" : \"y\", \"q\" : \"x\" }"))
			.isEqualTo("{\"p\":\"y\",\"q\":\"x\"}");

		String first = ModelTurnCodec.encodeToolPlan(new ToolCall("call-a", "search", "{\"q\":\"x\",\"p\":\"y\"}"),
				TOOLS.get(0), 0);
		String second = ModelTurnCodec.encodeToolPlan(
				new ToolCall("call-b", "search", "{ \"p\" : \"y\", \"q\" : \"x\" }"), TOOLS.get(0), 0);

		// Formatting and the provider's call id must not change the recorded plan.
		assertThat(second).isEqualTo(first);
		assertThat(first).contains("\"arguments\":\"{\\\"p\\\":\\\"y\\\",\\\"q\\\":\\\"x\\\"}\"");
	}

	@Test
	void arrayOrderAndMalformedArgumentsArePreserved() {
		// Array order is positional, so it carries meaning and must not be sorted.
		assertThat(ModelTurnCodec.canonicalArguments("{\"tags\":[\"b\",\"a\"]}"))
			.isEqualTo("{\"tags\":[\"b\",\"a\"]}");
		// Argument validation is opt-in, so an unparsable argument must pass through.
		assertThat(ModelTurnCodec.canonicalArguments("not json at all")).isEqualTo("not json at all");
		assertThat(ModelTurnCodec.canonicalArguments("{}")).isEqualTo("{}");
		assertThat(ModelTurnCodec.canonicalArguments(null)).isNull();
	}

	@Test
	void equivalentToolArgumentsInHistoryDoNotChangeTheRequestFingerprint() {
		ChatMessage assistant = new ChatMessage(ChatRole.ASSISTANT, List.of(),
				List.of(new ToolCall("call-a", "search", "{\"q\":\"x\",\"p\":\"y\"}")), null, null, Map.of());
		ChatMessage reformatted = new ChatMessage(ChatRole.ASSISTANT, List.of(),
				List.of(new ToolCall("call-a", "search", "{ \"p\":\"y\", \"q\":\"x\" }")), null, null, Map.of());
		List<ChatMessage> first = List.of(ChatMessage.user("go"), assistant);
		List<ChatMessage> second = List.of(ChatMessage.user("go"), reformatted);

		assertThat(ModelTurnCodec.requestHash(request(second))).isEqualTo(ModelTurnCodec.requestHash(request(first)));
	}

	@Test
	void preparingTheResponseReturnsAnImmutableBatchWithoutWriting() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunSnapshot before = snapshot;

		List<RunEntry> entries = recorder.prepareResponse(turn, toolCallingResponse("call-1"), TOOLS);

		assertThat(entries).extracting(RunEntry::id).containsExactly("agent:0#resp", "agent:0#task.0");
		assertThatThrownBy(entries::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThat(snapshot).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void commitsTheResponseAndItsPlanWithCheckpoint() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		commitRequest(turn, request(FIRST_TURN));
		long revision = snapshot.revision();

		commitResponse(turn, toolCallingResponse("provider-call-1"), TOOLS);

		assertThat(snapshot.revision()).isEqualTo(revision + 1);
		assertThat(snapshot.checkpoint()).isEqualTo(checkpoint(turn));
		assertThat(snapshot.tasks()).isEmpty();
		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req", "agent:0#resp", "agent:0#task.0");
		RunEntry plan = find("agent:0#task.0").orElseThrow();
		assertThat(plan.kind()).isEqualTo(RunEntry.Kind.TASK_CALL);
		assertThat(plan.payload().type()).isEqualTo(ModelTurnCodec.PLAN_TYPE);
		assertThat(plan.payload().json()).contains("\"toolName\":\"search\"", "\"ordinal\":0", "\"argumentsHash\"");
		// The provider's call id is protocol correlation, never task identity.
		assertThat(plan.payload().json()).doesNotContain("provider-call-1");
	}

	@Test
	void referenceExecutionDispatchesOnlyAfterTheWholePlanAndCheckpointAreCommitted() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		long revision = snapshot.revision();
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();
		List<ToolCall> calls = List.of(new ToolCall("provider-1", "search", "{\"q\":\"first\"}"),
				new ToolCall("provider-2", "search", "{\"q\":\"second\"}"));
		ChatResponse response = new ChatResponse("response", "model", List.of(ChatMessage.assistant("", calls)),
				calls, FinishReason.TOOL_CALLS, null, Map.of());

		executeReference(turn, request(FIRST_TURN), () -> {
			modelCalls.incrementAndGet();
			return response;
		}, call -> {
			RunSnapshot committed = store.find(run.namespace(), RUN_ID).orElseThrow();
			assertThat(committed.checkpoint()).isEqualTo(checkpoint(turn));
			assertThat(committed.journal()).containsOnlyKeys("agent:0#req", "agent:0#resp", "agent:0#task.0",
					"agent:0#task.1");
			assertThat(committed.tasks()).isEmpty();
			toolCalls.incrementAndGet();
		});

		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(toolCalls.get()).isEqualTo(2);
		assertThat(snapshot.revision()).isEqualTo(revision + 2);
	}

	@Test
	void requestCommitFailurePreventsModelAndToolCalls() {
		IllegalStateException unavailable = new IllegalStateException("request storage unavailable");
		runtimeStore = new FailingRunStore(store, ModelTurnCodec.REQUEST_TYPE, false, unavailable);
		RunSnapshot before = snapshot;
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"),
				request(FIRST_TURN), () -> {
					modelCalls.incrementAndGet();
					return toolCallingResponse("call-1");
				}, call -> toolCalls.incrementAndGet())).isSameAs(unavailable);

		assertThat(modelCalls.get()).isZero();
		assertThat(toolCalls.get()).isZero();
		assertThat(snapshot).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void responseCommitFailurePreventsToolCallsAndRetainsTheRequestCheckpoint() {
		IllegalStateException unavailable = new IllegalStateException("response storage unavailable");
		runtimeStore = new FailingRunStore(store, ModelTurnCodec.RESPONSE_TYPE, false, unavailable);
		RunSnapshot before = snapshot;
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"),
				request(FIRST_TURN), () -> {
					modelCalls.incrementAndGet();
					return toolCallingResponse("call-1");
				}, call -> toolCalls.incrementAndGet())).isSameAs(unavailable);

		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(toolCalls.get()).isZero();
		assertThat(snapshot.revision()).isEqualTo(before.revision() + 1);
		assertThat(snapshot.checkpoint()).isEqualTo(before.checkpoint());
		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req");
		assertThat(store.find(run.namespace(), RUN_ID)).contains(snapshot);
	}

	@Test
	void lostResponseCommitConfirmationPreventsDispatchUntilReloadAndReplay() {
		IllegalStateException lostConfirmation = new IllegalStateException("commit confirmation lost");
		runtimeStore = new FailingRunStore(store, ModelTurnCodec.RESPONSE_TYPE, true, lostConfirmation);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();
		Supplier<ChatResponse> model = () -> {
			modelCalls.incrementAndGet();
			return toolCallingResponse("call-1");
		};

		assertThatThrownBy(() -> executeReference(turn, request(FIRST_TURN), model,
				call -> toolCalls.incrementAndGet())).isSameAs(lostConfirmation);

		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(toolCalls.get()).isZero();
		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req");
		RunSnapshot committed = store.find(run.namespace(), RUN_ID).orElseThrow();
		assertThat(committed.revision()).isEqualTo(snapshot.revision() + 1);
		assertThat(committed.journal()).containsOnlyKeys("agent:0#req", "agent:0#resp", "agent:0#task.0");

		// Reload instead of assuming that a thrown commit exception means no write occurred.
		snapshot = committed;
		recorder = new ModelTurnRecorder();
		executeReference(turn, request(FIRST_TURN), model, call -> toolCalls.incrementAndGet());

		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(toolCalls.get()).isEqualTo(1);
		assertThat(snapshot).isSameAs(committed);
	}

	@Test
	void staleSnapshotRequestCommitConflictsBeforeCallingTheModel() {
		RunSnapshot stale = snapshot;
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		commitRequest(turn, request(FIRST_TURN));
		commitResponse(turn, toolCallingResponse("call-1"), TOOLS);
		RunSnapshot committed = snapshot;
		snapshot = stale;
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(turn, request(FIRST_TURN), () -> {
			modelCalls.incrementAndGet();
			return toolCallingResponse("call-2");
		}, call -> toolCalls.incrementAndGet())).isInstanceOf(RunStoreConflictException.class);

		assertThat(modelCalls.get()).isZero();
		assertThat(toolCalls.get()).isZero();
		assertThat(snapshot).isSameAs(stale);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(committed);
	}

	@Test
	void replaysCommittedPlanAfterRecorderAndSnapshotAreReloadedWithoutCallingTheModel() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "planner/visit-3/model-2");
		commitRequest(turn, request(FIRST_TURN));
		commitResponse(turn, toolCallingResponse("provider-call-1"), TOOLS);
		RunEntry planned = find(turn.turnId() + "#task.0").orElseThrow();
		long revision = snapshot.revision();

		// Recreate the execution objects after plan persistence, before tool dispatch.
		snapshot = store.find(run.namespace(), RUN_ID).orElseThrow();
		recorder = new ModelTurnRecorder();
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();
		ChatResponse replayed = executeReference(
				new ModelTurnRecorder.Turn(RUN_ID, "planner/visit-3/model-2"), request(FIRST_TURN), () -> {
					modelCalls.incrementAndGet();
					return toolCallingResponse("provider-call-2");
				}, call -> toolCalls.incrementAndGet());

		assertThat(modelCalls.get()).isZero();
		assertThat(toolCalls.get()).isEqualTo(1);
		assertThat(snapshot.revision()).isEqualTo(revision);
		assertThat(find(turn.turnId() + "#task.0")).contains(planned);
		assertThat(snapshot.checkpoint()).isEqualTo(checkpoint(turn));
		assertThat(snapshot.journal()).containsOnlyKeys("planner/visit-3/model-2#req",
				"planner/visit-3/model-2#resp", "planner/visit-3/model-2#task.0");
		assertThat(replayed.toolCalls())
			.containsExactly(new ToolCall("provider-call-1", "search", "{\"q\":\"argi\"}"));
	}

	@Test
	void rejectsReplayWhenTheRecordedRequestDiffers() {
		commitRequest(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), request(FIRST_TURN));
		commitResponse(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), toolCallingResponse("provider-call-1"), TOOLS);

		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		List<ChatMessage> different = List.of(ChatMessage.user("a different instruction"));

		assertThatThrownBy(() -> recorder.replay(turn, request(different), snapshot))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("different request")
			.hasCauseInstanceOf(RunStoreConflictException.class);
	}

	@Test
	void anInterruptedTurnFailsClosedInsteadOfAskingAgain() {
		commitRequest(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), request(FIRST_TURN));

		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");

		assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN), snapshot))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("never recorded");
		assertThat(find("agent:0#resp")).isEmpty();
	}

	@Test
	void rejectsBlankTurnIdentityComponents() {
		for (String invalid : new String[] { null, "", " \t" }) {
			assertThatThrownBy(() -> new ModelTurnRecorder.Turn(invalid, "turn"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new ModelTurnRecorder.Turn(RUN_ID, invalid))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void replayRejectsASnapshotFromAnotherRun() {
		assertThatThrownBy(() -> recorder.replay(new ModelTurnRecorder.Turn("another-run", "agent:0"),
				request(FIRST_TURN), snapshot)).isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("Run snapshot does not match");
	}

	@Test
	void responseWithoutRequestFailsBeforeModelOrToolCalls() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		commitResponse(turn, toolCallingResponse("call-1"), TOOLS);
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(turn, request(FIRST_TURN), () -> {
			modelCalls.incrementAndGet();
			return toolCallingResponse("call-2");
		}, call -> toolCalls.incrementAndGet())).isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("missing its request fingerprint");

		assertThat(modelCalls.get()).isZero();
		assertThat(toolCalls.get()).isZero();
	}

	@Test
	void replayRejectsAnOrphanPlanInsteadOfTreatingTheTurnAsNew() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunEntry plan = recorder.prepareResponse(turn, toolCallingResponse("call-1"), TOOLS).get(1);
		RunSnapshot orphaned = snapshotWithJournal(List.of(plan));

		assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN), orphaned))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("missing its request fingerprint");
	}

	@Test
	void replayRejectsIncompatibleEntryKindsTypesAndPayloadVersions() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		List<RunEntry> complete = completeEntries(turn);
		for (int index = 0; index < complete.size(); index++) {
			RunEntry original = complete.get(index);
			List<RunEntry> incompatible = List.of(
					new RunEntry(1, original.id(), RunEntry.Kind.EVENT, original.payload()),
					new RunEntry(1, original.id(), original.kind(), new RunPayload(1, "other.type", "{}")),
					new RunEntry(1, original.id(), original.kind(),
							new RunPayload(2, original.payload().type(), original.payload().json())));
			for (RunEntry replacement : incompatible) {
				List<RunEntry> entries = new ArrayList<>(complete);
				entries.set(index, replacement);
				RunSnapshot invalid = snapshotWithJournal(entries);

				assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN), invalid))
					.isInstanceOf(ModelTurnJournalException.class)
					.hasMessageContaining("Incompatible journal entry " + original.id());
			}
		}
	}

	@Test
	void replayRejectsAnEntryWhoseIdDoesNotMatchItsJournalKey() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunSnapshot complete = snapshotWithJournal(completeEntries(turn));
		Map<String, RunEntry> entries = new HashMap<>(complete.journal());
		RunEntry request = entries.get("agent:0#req");
		entries.put(request.id(), new RunEntry(1, "another-turn#req", request.kind(), request.payload()));
		RunSnapshot invalid = new RunSnapshot(complete.spec(), complete.revision(), complete.state(),
				complete.checkpoint(), complete.tasks(), entries, complete.outbox());

		assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN), invalid))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("Incompatible journal entry agent:0#req");
	}

	@Test
	void replayRejectsARequestRecordWithoutAFingerprint() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunEntry invalid = new RunEntry(1, "agent:0#req", RunEntry.Kind.MODEL_CALL,
				new RunPayload(1, ModelTurnCodec.REQUEST_TYPE, "{}"));
		RunSnapshot recorded = snapshotWithJournal(List.of(invalid));

		assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN), recorded))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("invalid request fingerprint");
	}

	@Test
	void replayRejectsMissingExtraAndNonContiguousPlans() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		List<RunEntry> complete = completeEntries(turn);
		RunEntry plan = complete.get(2);
		RunEntry extra = new RunEntry(1, "agent:0#task.1", plan.kind(), plan.payload());
		RunEntry alias = new RunEntry(1, "agent:0#task.00", plan.kind(), plan.payload());
		List<List<RunEntry>> incomplete = List.of(complete.subList(0, 2),
				List.of(complete.get(0), complete.get(1), extra),
				List.of(complete.get(0), complete.get(1), plan, extra),
				List.of(complete.get(0), complete.get(1), plan, alias));
		for (List<RunEntry> entries : incomplete) {
			RunSnapshot recorded = snapshotWithJournal(entries);

			assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN), recorded))
				.isInstanceOf(ModelTurnJournalException.class)
				.hasMessageContaining("tool plan");
		}
	}

	@Test
	void replayRejectsPlanContentsThatDisagreeWithTheResponseOrDisclosedTools() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		List<RunEntry> complete = completeEntries(turn);
		RunEntry plan = complete.get(2);
		String json = plan.payload().json();
		List<String> changed = List.of(json.replace("\"ordinal\":0", "\"ordinal\":1"),
				json.replace("\"toolName\":\"search\"", "\"toolName\":\"another-tool\""),
				json.replace("\"argumentsHash\":\"", "\"argumentsHash\":\"changed-"),
				json.replace("\"definitionVersion\":\"", "\"definitionVersion\":\"changed-"),
				ModelTurnCodec.encodeToolPlan(new ToolCall("call-1", "search", "{\"q\":\"different\"}"),
						TOOLS.get(0), 0));
		for (String changedJson : changed) {
			RunEntry replacement = new RunEntry(1, plan.id(), plan.kind(),
					new RunPayload(1, ModelTurnCodec.PLAN_TYPE, changedJson));
			RunSnapshot invalid = snapshotWithJournal(List.of(complete.get(0), complete.get(1), replacement));

			assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN), invalid))
				.isInstanceOf(ModelTurnJournalException.class)
				.hasMessageContaining("tool plan does not match");
		}
	}

	@Test
	void replayScopesPlansToExactTurnIdsIncludingSeparatorLikeCharacters() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent.[0]+");
		ModelTurnRecorder.Turn other = new ModelTurnRecorder.Turn(RUN_ID, turn.turnId() + "#task.0");
		List<RunEntry> entries = new ArrayList<>(completeEntries(turn));
		entries.addAll(completeEntries(other));
		RunSnapshot recorded = snapshotWithJournal(entries);

		assertThat(recorder.replay(turn, request(FIRST_TURN), recorded)).contains(toolCallingResponse("call-1"));
		assertThat(recorder.replay(other, request(FIRST_TURN), recorded)).contains(toolCallingResponse("call-1"));
	}

	@Test
	void textOnlyResponseReplaysWithNoToolPlans() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		ChatResponse text = new ChatResponse("response", "model", List.of(ChatMessage.assistant("done")),
				List.of(), FinishReason.STOP, null, Map.of());
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();
		Supplier<ChatResponse> model = () -> {
			modelCalls.incrementAndGet();
			return text;
		};

		assertThat(executeReference(turn, request(FIRST_TURN), model, call -> toolCalls.incrementAndGet()))
			.isEqualTo(text);
		assertThat(executeReference(turn, request(FIRST_TURN), model, call -> toolCalls.incrementAndGet()))
			.isEqualTo(text);

		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(toolCalls.get()).isZero();
		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req", "agent:0#resp");
	}

	@Test
	void replayValidatesCurrentContentsWithoutInterpretingTheCheckpointOrCommitHistory() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		commitRequest(turn, request(FIRST_TURN));
		List<RunEntry> entries = recorder.prepareResponse(turn, toolCallingResponse("call-1"), TOOLS);
		// Deliberately violate producer ordering to show that replay cannot prove atomic history.
		commit(null, entries.subList(0, 1));
		commit(new RunPayload(1, "application.checkpoint", "{\"opaque\":true}"), entries.subList(1, entries.size()));

		assertThat(recorder.replay(turn, request(FIRST_TURN), snapshot)).contains(toolCallingResponse("call-1"));
	}

	@Test
	void providerCallIdsDoNotChangeThePlannedTaskIdentity() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		List<RunEntry> first = recorder.prepareResponse(turn, toolCallingResponse("provider-call-1"), TOOLS);
		List<RunEntry> renamed = recorder.prepareResponse(turn, toolCallingResponse("provider-call-2"), TOOLS);
		assertThat(renamed.stream().filter(entry -> entry.kind() == RunEntry.Kind.TASK_CALL).toList())
			.isEqualTo(first.stream().filter(entry -> entry.kind() == RunEntry.Kind.TASK_CALL).toList());

		commitRequest(turn, request(FIRST_TURN));
		commit(checkpoint(turn), first);
		assertThat(recorder.replay(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), request(FIRST_TURN), snapshot))
			.contains(toolCallingResponse("provider-call-1"));
		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req", "agent:0#resp", "agent:0#task.0");
	}

	@Test
	void theSameTaskIdentityWithDifferentArgumentsConflictsWithoutUpdatingCheckpoint() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		commitRequest(turn, request(FIRST_TURN));
		commitResponse(turn, toolCallingResponse("call-1"), TOOLS);
		RunSnapshot before = snapshot;
		RunEntry changed = new RunEntry(1, "agent:0#task.0", RunEntry.Kind.TASK_CALL,
				new RunPayload(1, ModelTurnCodec.PLAN_TYPE, ModelTurnCodec.encodeToolPlan(
						new ToolCall("call-2", "search", "{\"q\":\"different\"}"), TOOLS.get(0), 0)));

		assertThatThrownBy(() -> commit(new RunPayload(1, "test.checkpoint", "{\"changed\":true}"),
				List.of(changed))).isInstanceOf(RunStoreConflictException.class);

		assertThat(snapshot).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void streamedTextIsRecordedUnderOneStableAttempt() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		String attempt = ModelTurnRecorder.newAttempt();
		RunSnapshot before = snapshot;

		commit(null, recorder.prepareDelta(turn, attempt, 0, "he"));
		commit(null, recorder.prepareDelta(turn, attempt, 1, "llo"));
		assertThat(recorder.prepareDelta(turn, attempt, 2, "")).isEmpty();

		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#progress." + attempt + "#delta.0",
				"agent:0#progress." + attempt + "#delta.1");
		assertThat(snapshot.revision()).isEqualTo(before.revision() + 2);
		assertThat(snapshot.checkpoint()).isEqualTo(before.checkpoint());
		// Progress is diagnostic only: it never produces a response or a plan.
		assertThat(find("agent:0#resp")).isEmpty();
		assertThat(find("agent:0#task.0")).isEmpty();
		assertThat(recorder.replay(turn, request(FIRST_TURN), snapshot)).isEmpty();
	}

	@Test
	void preparingDeltasIsImmutableAndHasNoStorageSideEffects() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunSnapshot before = snapshot;
		String attempt = ModelTurnRecorder.newAttempt();

		List<RunEntry> entries = recorder.prepareDelta(turn, attempt, 0, "hello");

		assertThat(entries).extracting(RunEntry::id).containsExactly("agent:0#progress." + attempt + "#delta.0");
		assertThatThrownBy(entries::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThat(recorder.prepareDelta(turn, attempt, 1, "")).isEmpty();
		assertThat(recorder.prepareDelta(turn, attempt, 1, null)).isEmpty();
		assertThat(snapshot).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void referenceCommitsDeltasBeforeTheResponseUsingEachSuccessfulRevision() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunSnapshot before = snapshot;
		String attempt = ModelTurnRecorder.newAttempt();
		AtomicInteger toolCalls = new AtomicInteger();

		executeReference(turn, request(FIRST_TURN), () -> {
			commit(null, recorder.prepareDelta(turn, attempt, 0, "he"));
			commit(null, recorder.prepareDelta(turn, attempt, 1, "llo"));
			assertThat(snapshot.revision()).isEqualTo(before.revision() + 3);
			assertThat(snapshot.checkpoint()).isEqualTo(before.checkpoint());
			assertThat(find("agent:0#resp")).isEmpty();
			return toolCallingResponse("call-1");
		}, call -> {
			assertThat(snapshot.checkpoint()).isEqualTo(checkpoint(turn));
			assertThat(snapshot.revision()).isEqualTo(before.revision() + 4);
			assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req", "agent:0#resp", "agent:0#task.0",
					"agent:0#progress." + attempt + "#delta.0", "agent:0#progress." + attempt + "#delta.1");
			toolCalls.incrementAndGet();
		});

		assertThat(toolCalls.get()).isEqualTo(1);
	}

	@Test
	void deltaCommitFailureIsPropagatedBeforeTheResponseAndToolDispatch() {
		IllegalStateException unavailable = new IllegalStateException("delta storage unavailable");
		runtimeStore = new FailingRunStore(store, ModelTurnCodec.DELTA_TYPE, false, unavailable);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		AtomicInteger toolCalls = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(turn, request(FIRST_TURN), () -> {
			commit(null, recorder.prepareDelta(turn, ModelTurnRecorder.newAttempt(), 0, "hello"));
			return toolCallingResponse("call-1");
		}, call -> toolCalls.incrementAndGet())).isSameAs(unavailable);

		assertThat(toolCalls.get()).isZero();
		assertThat(snapshot.journal()).containsOnlyKeys("agent:0#req");
		assertThat(store.find(run.namespace(), RUN_ID)).contains(snapshot);
	}

	@Test
	void differentCallsWithTheSameRequestHaveIndependentRecords() {
		ModelTurnRecorder.Turn first = new ModelTurnRecorder.Turn(RUN_ID, "first-call");
		ModelTurnRecorder.Turn next = new ModelTurnRecorder.Turn(RUN_ID, "next-call");
		commitRequest(first, request(FIRST_TURN));
		commitResponse(first, toolCallingResponse("call-1"), TOOLS);

		assertThat(recorder.replay(next, request(FIRST_TURN), snapshot)).isEmpty();
		commitRequest(next, request(FIRST_TURN));
		commitResponse(next, toolCallingResponse("call-2"), TOOLS);

		assertThat(snapshot.journal()).containsOnlyKeys("first-call#req", "first-call#resp", "first-call#task.0",
				"next-call#req", "next-call#resp", "next-call#task.0");
	}

	/**
	 * Regression guard for the defect that made summarisation fatal: a count over the
	 * conversation shrinks when history is summarised or trimmed, so the next call reused
	 * an ordinal that an earlier turn already owned and every later turn conflicted.
	 */
	@Test
	void shrinkingTheConversationDoesNotCollideWithAnEarlierTurn() {
		for (long ordinal = 0; ordinal < 3; ordinal++) {
			ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:" + ordinal);
			commitRequest(turn, request(List.of(ChatMessage.user("question " + ordinal))));
			commitResponse(turn, toolCallingResponse("call-" + ordinal), TOOLS);
		}

		// Summarisation replaced the history with a summary plus a short tail, so the
		// conversation now holds fewer assistant messages than before. The engine supplies
		// a new identity independently of that history.
		List<ChatMessage> summarisedHistory = List.of(ChatMessage.system("summary of earlier turns"),
				ChatMessage.user("question 3"));
		ModelTurnRecorder.Turn next = new ModelTurnRecorder.Turn(RUN_ID, "after-summary");

		assertThat(recorder.replay(next, request(summarisedHistory), snapshot)).isEmpty();
		commitRequest(next, request(summarisedHistory));
		assertThat(find("after-summary#req")).isPresent();
	}

	/**
	 * Documented limitation: a request rewritten non-deterministically between the original
	 * call and a restart - for example by a summarisation hook that asks a model for the
	 * summary - yields a different fingerprint for the same turn. The turn must fail closed
	 * rather than replay a response that was produced for other input.
	 */
	@Test
	void aNonDeterministicRewriteOfTheSameTurnFailsClosed() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "summary-turn");
		commitRequest(turn, request(List.of(ChatMessage.system("summary, first attempt"),
				ChatMessage.user("question"))));
		commitResponse(turn, toolCallingResponse("call-1"), TOOLS);

		// After a restart the same hook produced a different summary for the same turn.
		List<ChatMessage> rebuilt = List.of(ChatMessage.system("summary, second attempt"),
				ChatMessage.user("question"));

		assertThatThrownBy(() -> recorder.replay(new ModelTurnRecorder.Turn(RUN_ID, "summary-turn"),
				request(rebuilt), snapshot))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("summarisation");
	}

	private void commitResponse(ModelTurnRecorder.Turn turn, ChatResponse response, Iterable<ToolDefinition> tools) {
		commit(checkpoint(turn), recorder.prepareResponse(turn, response, tools));
	}

	private static RunPayload checkpoint(ModelTurnRecorder.Turn turn) {
		return new RunPayload(1, "test.checkpoint",
				"{\"phase\":\"TOOLS_PENDING\",\"turnId\":\"" + turn.turnId() + "\"}");
	}

	/** In-process recovery reference only; tool receipts and a production runtime are separate. */
	private ChatResponse executeReference(ModelTurnRecorder.Turn turn, ChatRequest request,
			Supplier<ChatResponse> model, Consumer<ToolCall> dispatch) {
		ChatResponse response = recorder.replay(turn, request, snapshot).orElseGet(() -> {
			commitRequest(turn, request);
			ChatResponse result = model.get();
			commitResponse(turn, result, request.tools());
			return result;
		});
		response.toolCalls().forEach(dispatch);
		return response;
	}

	private void commitRequest(ModelTurnRecorder.Turn turn, ChatRequest request) {
		commit(null, recorder.prepareRequest(turn, request));
	}

	private void commit(RunPayload checkpoint, List<RunEntry> entries) {
		snapshot = runtimeStore.commit(run.namespace(), RUN_ID, lease,
				new RunCommit(snapshot.revision(), snapshot.state(), checkpoint, List.of(), entries, List.of()));
	}

	private Optional<RunEntry> find(String id) {
		return Optional.ofNullable(snapshot.journal().get(id));
	}

	private List<RunEntry> completeEntries(ModelTurnRecorder.Turn turn) {
		List<RunEntry> entries = new ArrayList<>(recorder.prepareRequest(turn, request(FIRST_TURN)));
		entries.addAll(recorder.prepareResponse(turn, toolCallingResponse("call-1"), TOOLS));
		return entries;
	}

	private RunSnapshot snapshotWithJournal(List<RunEntry> entries) {
		Map<String, RunEntry> journal = new HashMap<>();
		entries.forEach(entry -> journal.put(entry.id(), entry));
		return new RunSnapshot(snapshot.spec(), snapshot.revision(), snapshot.state(), snapshot.checkpoint(),
				snapshot.tasks(), journal, snapshot.outbox());
	}

	private static ChatRequest request(List<ChatMessage> messages) {
		return new ChatRequest(messages, TOOLS, null, Map.of());
	}

	private static ChatResponse toolCallingResponse(String callId) {
		ToolCall call = new ToolCall(callId, "search", "{\"q\":\"argi\"}");
		return new ChatResponse("response", "test-model", List.of(ChatMessage.assistant("", List.of(call))),
				List.of(call), FinishReason.TOOL_CALLS, null, Map.of());
	}

	/** Simulates both definite rejection and a write whose acknowledgement is lost. */
	private static final class FailingRunStore implements RunStore {

		private final RunStore delegate;

		private final String payloadType;

		private final boolean commitBeforeFailure;

		private final RuntimeException failure;

		private FailingRunStore(RunStore delegate, String payloadType, boolean commitBeforeFailure,
				RuntimeException failure) {
			this.delegate = delegate;
			this.payloadType = payloadType;
			this.commitBeforeFailure = commitBeforeFailure;
			this.failure = failure;
		}

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
