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
import io.github.agentic.ai.runtime.execution.run.RunJournalWriter;
import io.github.agentic.ai.runtime.execution.run.RunLease;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunSnapshot;
import io.github.agentic.ai.runtime.execution.run.RunSpec;
import io.github.agentic.ai.runtime.execution.run.RunState;
import io.github.agentic.ai.runtime.execution.run.RunStoreConflictException;
import io.github.agentic.ai.runtime.execution.run.RunStoreJournalWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
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

	private RunStoreJournalWriter journal;

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
		RunSnapshot running = store.commit(run.namespace(), RUN_ID, lease,
				new RunCommit(0, RunState.RUNNING,
						new RunPayload(1, "test.checkpoint", "{\"phase\":\"MODEL_READY\"}"), List.of(), List.of(),
						List.of()));
		journal = new RunStoreJournalWriter(store, lease, running);
		recorder = ModelTurnRecorder.of(journal);
	}

	@Test
	void recordsOnlyTheRequestFingerprintBeforeTheCall() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");

		recorder.recordRequest(turn, request(FIRST_TURN));

		RunEntry recorded = journal.find(RUN_ID, "agent:0#req").orElseThrow();
		assertThat(recorded.kind()).isEqualTo(RunEntry.Kind.MODEL_CALL);
		assertThat(recorded.payload().type()).isEqualTo(ModelTurnCodec.REQUEST_TYPE);
		// The request body is never persisted: only the fingerprint is.
		assertThat(recorded.payload().json()).contains("requestHash").doesNotContain("search for argi");
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
		RunSnapshot before = journal.snapshot();

		List<RunEntry> entries = recorder.prepareResponse(turn, toolCallingResponse("call-1"), TOOLS);

		assertThat(entries).extracting(RunEntry::id).containsExactly("agent:0#resp", "agent:0#task.0");
		assertThatThrownBy(entries::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThat(journal.snapshot()).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void commitsTheResponseAndItsPlanWithCheckpoint() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		long revision = journal.snapshot().revision();

		commitResponse(turn, toolCallingResponse("provider-call-1"), TOOLS);

		assertThat(journal.snapshot().revision()).isEqualTo(revision + 1);
		assertThat(journal.snapshot().checkpoint()).isEqualTo(checkpoint(turn));
		assertThat(journal.snapshot().tasks()).isEmpty();
		assertThat(journal.snapshot().journal()).containsOnlyKeys("agent:0#resp", "agent:0#task.0");
		RunEntry plan = journal.find(RUN_ID, "agent:0#task.0").orElseThrow();
		assertThat(plan.kind()).isEqualTo(RunEntry.Kind.TASK_CALL);
		assertThat(plan.payload().type()).isEqualTo(ModelTurnCodec.PLAN_TYPE);
		assertThat(plan.payload().json()).contains("\"toolName\":\"search\"", "\"ordinal\":0", "\"argumentsHash\"");
		// The provider's call id is protocol correlation, never task identity.
		assertThat(plan.payload().json()).doesNotContain("provider-call-1");
	}

	@Test
	void referenceExecutionDispatchesOnlyAfterTheWholePlanAndCheckpointAreCommitted() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		long revision = journal.snapshot().revision();
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();
		List<ToolCall> calls = List.of(new ToolCall("provider-1", "search", "{\"q\":\"first\"}"),
				new ToolCall("provider-2", "search", "{\"q\":\"second\"}"));
		ChatResponse response = new ChatResponse("response", "model", List.of(ChatMessage.assistant("", calls)),
				calls, FinishReason.TOOL_CALLS, null, Map.of());

		executeReference(journal, turn, request(FIRST_TURN), () -> {
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
		assertThat(journal.snapshot().revision()).isEqualTo(revision + 2);
	}

	@Test
	void referenceExecutionDoesNotDispatchAnyToolWhenCommitFails() {
		IllegalStateException unavailable = new IllegalStateException("storage unavailable");
		RunJournalWriter failing = new RunJournalWriter() {
			@Override
			public Optional<RunEntry> find(String runId, String entryId) {
				return journal.find(runId, entryId);
			}

			@Override
			public void append(String runId, List<RunEntry> entries) {
				journal.append(runId, entries);
			}

			@Override
			public void commit(String runId, RunPayload checkpoint, List<RunEntry> entries) {
				throw unavailable;
			}
		};
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		RunPayload before = journal.snapshot().checkpoint();
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();

		assertThatThrownBy(() -> executeReference(failing, turn, request(FIRST_TURN), () -> {
			modelCalls.incrementAndGet();
			return toolCallingResponse("call-1");
		}, call -> toolCalls.incrementAndGet())).isSameAs(unavailable);

		assertThat(modelCalls.get()).isEqualTo(1);
		assertThat(toolCalls.get()).isZero();
		assertThat(journal.snapshot().checkpoint()).isEqualTo(before);
		assertThat(journal.snapshot().journal()).containsOnlyKeys("agent:0#req");
		assertThat(store.find(run.namespace(), RUN_ID)).contains(journal.snapshot());
	}

	@Test
	void replaysCommittedPlanAfterWriterAndRecorderAreRecreatedWithoutCallingTheModel() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "planner/visit-3/model-2");
		recorder.recordRequest(turn, request(FIRST_TURN));
		commitResponse(turn, toolCallingResponse("provider-call-1"), TOOLS);

		// Recreate the execution objects after plan persistence, before tool dispatch.
		journal = new RunStoreJournalWriter(store, lease, store.find(run.namespace(), RUN_ID).orElseThrow());
		AtomicInteger modelCalls = new AtomicInteger();
		AtomicInteger toolCalls = new AtomicInteger();
		ChatResponse replayed = executeReference(journal,
				new ModelTurnRecorder.Turn(RUN_ID, "planner/visit-3/model-2"), request(FIRST_TURN), () -> {
					modelCalls.incrementAndGet();
					return toolCallingResponse("provider-call-2");
				}, call -> toolCalls.incrementAndGet());

		assertThat(modelCalls.get()).isZero();
		assertThat(toolCalls.get()).isEqualTo(1);
		assertThat(journal.snapshot().checkpoint()).isEqualTo(checkpoint(turn));
		assertThat(journal.snapshot().journal()).containsOnlyKeys("planner/visit-3/model-2#req",
				"planner/visit-3/model-2#resp", "planner/visit-3/model-2#task.0");
		assertThat(replayed.toolCalls())
			.containsExactly(new ToolCall("provider-call-1", "search", "{\"q\":\"argi\"}"));
	}

	@Test
	void rejectsReplayWhenTheRecordedRequestDiffers() {
		recorder.recordRequest(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), request(FIRST_TURN));
		commitResponse(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), toolCallingResponse("provider-call-1"), TOOLS);

		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		List<ChatMessage> different = List.of(ChatMessage.user("a different instruction"));

		assertThatThrownBy(() -> recorder.replay(turn, request(different)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("different request")
			.hasCauseInstanceOf(RunStoreConflictException.class);
	}

	@Test
	void anInterruptedTurnFailsClosedInsteadOfAskingAgain() {
		recorder.recordRequest(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), request(FIRST_TURN));

		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");

		assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("never recorded");
		assertThat(journal.find(RUN_ID, "agent:0#resp")).isEmpty();
	}

	@Test
	void ajournalFailureSurfacesAsAJournalFailure() {
		RunJournalWriter failing = new RunJournalWriter() {
			@Override
			public Optional<RunEntry> find(String streamId, String entryId) {
				return Optional.empty();
			}

			@Override
			public void commit(String runId, RunPayload checkpoint, List<RunEntry> entries) {
				throw new IllegalStateException("journal unavailable");
			}

			@Override
			public void append(String streamId, List<RunEntry> entries) {
				throw new IllegalStateException("journal unavailable");
			}
		};
		ModelTurnRecorder recorder = ModelTurnRecorder.of(failing);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");

		assertThatThrownBy(() -> recorder.recordRequest(turn, request(FIRST_TURN)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasRootCauseMessage("journal unavailable");
	}

	@Test
	void providerCallIdsDoNotChangeThePlannedTaskIdentity() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		List<RunEntry> first = recorder.prepareResponse(turn, toolCallingResponse("provider-call-1"), TOOLS);
		List<RunEntry> renamed = recorder.prepareResponse(turn, toolCallingResponse("provider-call-2"), TOOLS);
		assertThat(renamed.stream().filter(entry -> entry.kind() == RunEntry.Kind.TASK_CALL).toList())
			.isEqualTo(first.stream().filter(entry -> entry.kind() == RunEntry.Kind.TASK_CALL).toList());

		recorder.recordRequest(turn, request(FIRST_TURN));
		journal.commit(RUN_ID, checkpoint(turn), first);
		assertThat(recorder.replay(new ModelTurnRecorder.Turn(RUN_ID, "agent:0"), request(FIRST_TURN)))
			.contains(toolCallingResponse("provider-call-1"));
		assertThat(journal.snapshot().journal()).containsOnlyKeys("agent:0#req", "agent:0#resp", "agent:0#task.0");
	}

	@Test
	void theSameTaskIdentityWithDifferentArgumentsConflictsWithoutUpdatingCheckpoint() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		commitResponse(turn, toolCallingResponse("call-1"), TOOLS);
		RunSnapshot before = journal.snapshot();
		RunEntry changed = new RunEntry(1, "agent:0#task.0", RunEntry.Kind.TASK_CALL,
				new RunPayload(1, ModelTurnCodec.PLAN_TYPE, ModelTurnCodec.encodeToolPlan(
						new ToolCall("call-2", "search", "{\"q\":\"different\"}"), TOOLS.get(0), 0)));

		assertThatThrownBy(() -> journal.commit(RUN_ID, new RunPayload(1, "test.checkpoint", "{\"changed\":true}"),
				List.of(changed))).isInstanceOf(RunStoreConflictException.class);

		assertThat(journal.snapshot()).isSameAs(before);
		assertThat(store.find(run.namespace(), RUN_ID)).contains(before);
	}

	@Test
	void streamedTextIsRecordedUnderOneStableAttempt() {
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(RUN_ID, "agent:0");
		String attempt = ModelTurnRecorder.newAttempt();
		RunSnapshot before = journal.snapshot();

		recorder.recordDelta(turn, attempt, 0, "he");
		recorder.recordDelta(turn, attempt, 1, "llo");
		recorder.recordDelta(turn, attempt, 2, "");

		assertThat(journal.snapshot().journal()).containsOnlyKeys("agent:0#progress." + attempt + "#delta.0",
				"agent:0#progress." + attempt + "#delta.1");
		assertThat(journal.snapshot().revision()).isEqualTo(before.revision() + 2);
		assertThat(journal.snapshot().checkpoint()).isEqualTo(before.checkpoint());
		// Progress is diagnostic only: it never produces a response or a plan.
		assertThat(journal.find(RUN_ID, "agent:0#resp")).isEmpty();
		assertThat(journal.find(RUN_ID, "agent:0#task.0")).isEmpty();
	}

	@Test
	void differentCallsWithTheSameRequestHaveIndependentRecords() {
		ModelTurnRecorder.Turn first = new ModelTurnRecorder.Turn(RUN_ID, "first-call");
		ModelTurnRecorder.Turn next = new ModelTurnRecorder.Turn(RUN_ID, "next-call");
		recorder.recordRequest(first, request(FIRST_TURN));
		commitResponse(first, toolCallingResponse("call-1"), TOOLS);

		assertThat(recorder.replay(next, request(FIRST_TURN))).isEmpty();
		recorder.recordRequest(next, request(FIRST_TURN));
		commitResponse(next, toolCallingResponse("call-2"), TOOLS);

		assertThat(journal.snapshot().journal()).containsOnlyKeys("first-call#req", "first-call#resp", "first-call#task.0",
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
			recorder.recordRequest(turn, request(List.of(ChatMessage.user("question " + ordinal))));
			commitResponse(turn, toolCallingResponse("call-" + ordinal), TOOLS);
		}

		// Summarisation replaced the history with a summary plus a short tail, so the
		// conversation now holds fewer assistant messages than before. The engine supplies
		// a new identity independently of that history.
		List<ChatMessage> summarisedHistory = List.of(ChatMessage.system("summary of earlier turns"),
				ChatMessage.user("question 3"));
		ModelTurnRecorder.Turn next = new ModelTurnRecorder.Turn(RUN_ID, "after-summary");

		assertThat(recorder.replay(next, request(summarisedHistory))).isEmpty();
		recorder.recordRequest(next, request(summarisedHistory));
		assertThat(journal.find(RUN_ID, "after-summary#req")).isPresent();
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
		recorder.recordRequest(turn, request(List.of(ChatMessage.system("summary, first attempt"),
				ChatMessage.user("question"))));
		commitResponse(turn, toolCallingResponse("call-1"), TOOLS);

		// After a restart the same hook produced a different summary for the same turn.
		List<ChatMessage> rebuilt = List.of(ChatMessage.system("summary, second attempt"),
				ChatMessage.user("question"));

		assertThatThrownBy(() -> recorder.replay(new ModelTurnRecorder.Turn(RUN_ID, "summary-turn"), request(rebuilt)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("summarisation");
	}

	@Test
	void aDisabledJournalYieldsNoRecorder() {
		assertThat(ModelTurnRecorder.of(null)).isNull();
	}

	private void commitResponse(ModelTurnRecorder.Turn turn, ChatResponse response, Iterable<ToolDefinition> tools) {
		journal.commit(RUN_ID, checkpoint(turn), recorder.prepareResponse(turn, response, tools));
	}

	private static RunPayload checkpoint(ModelTurnRecorder.Turn turn) {
		return new RunPayload(1, "test.checkpoint",
				"{\"phase\":\"TOOLS_PENDING\",\"turnId\":\"" + turn.turnId() + "\"}");
	}

	/** Reference ordering only; tool receipts and production engine recovery are separate. */
	private static ChatResponse executeReference(RunJournalWriter writer, ModelTurnRecorder.Turn turn,
			ChatRequest request, Supplier<ChatResponse> model, Consumer<ToolCall> dispatch) {
		ModelTurnRecorder recorder = ModelTurnRecorder.of(writer);
		ChatResponse response = recorder.replay(turn, request).orElseGet(() -> {
			recorder.recordRequest(turn, request);
			ChatResponse result = model.get();
			List<RunEntry> entries = recorder.prepareResponse(turn, result, request.tools());
			writer.commit(turn.streamId(), checkpoint(turn), entries);
			return result;
		});
		response.toolCalls().forEach(dispatch);
		return response;
	}

	private static ChatRequest request(List<ChatMessage> messages) {
		return new ChatRequest(messages, TOOLS, null, Map.of());
	}

	private static ChatResponse toolCallingResponse(String callId) {
		ToolCall call = new ToolCall(callId, "search", "{\"q\":\"argi\"}");
		return new ChatResponse("response", "test-model", List.of(ChatMessage.assistant("", List.of(call))),
				List.of(call), FinishReason.TOOL_CALLS, null, Map.of());
	}

}
