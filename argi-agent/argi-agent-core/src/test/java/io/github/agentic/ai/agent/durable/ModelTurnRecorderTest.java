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
import io.github.agentic.ai.runtime.execution.run.MemoryRunJournal;
import io.github.agentic.ai.runtime.execution.run.RunEntry;
import io.github.agentic.ai.runtime.execution.run.RunJournal;
import io.github.agentic.ai.runtime.execution.run.RunStoreConflictException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The neutral model-turn contract, exercised without any graph or streaming engine. */
@Timeout(10)
class ModelTurnRecorderTest {

	private static final String STREAM = "stream-1";

	private static final List<ChatMessage> FIRST_TURN = List.of(ChatMessage.user("search for argi"));

	private static final List<ToolDefinition> TOOLS = List.of(
			new ToolDefinition("search", "search the web", "{\"type\":\"object\"}"));

	@Test
	void recordsOnlyTheRequestFingerprintBeforeTheCall() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:0");

		recorder.recordRequest(turn, request(FIRST_TURN));

		RunEntry recorded = journal.find(STREAM, "agent:0#req").orElseThrow();
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
		// Argument validation is opt-in, so an unparseable argument must pass through.
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
	void commitsTheResponseAndItsPlanInOneAppend() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:0");

		recorder.commit(turn, toolCallingResponse("provider-call-1"),
				List.of(TOOLS.get(0)));

		assertThat(journal.entryIds(STREAM)).containsExactly("agent:0#resp", "agent:0#task.0");
		RunEntry plan = journal.find(STREAM, "agent:0#task.0").orElseThrow();
		assertThat(plan.kind()).isEqualTo(RunEntry.Kind.TASK_CALL);
		assertThat(plan.payload().type()).isEqualTo(ModelTurnCodec.PLAN_TYPE);
		assertThat(plan.payload().json()).contains("\"toolName\":\"search\"", "\"ordinal\":0", "\"argumentsHash\"");
		// The provider's call id is protocol correlation, never task identity.
		assertThat(plan.payload().json()).doesNotContain("provider-call-1");
	}

	@Test
	void replaysTheRecordedResponseAfterRecorderIsRecreated() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "planner/visit-3/model-2");
		recorder.recordRequest(turn, request(FIRST_TURN));
		recorder.commit(turn, toolCallingResponse("provider-call-1"), TOOLS);

		ModelTurnRecorder recovered = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn restored = new ModelTurnRecorder.Turn(STREAM, "planner/visit-3/model-2");
		Optional<ChatResponse> replayed = recovered.replay(restored, request(FIRST_TURN));

		assertThat(journal.entryIds(STREAM)).containsExactly("planner/visit-3/model-2#req",
				"planner/visit-3/model-2#resp", "planner/visit-3/model-2#task.0");
		assertThat(replayed).isPresent();
		assertThat(replayed.orElseThrow().toolCalls()).extracting(ToolCall::name).containsExactly("search");
		assertThat(replayed.orElseThrow().toolCalls()).extracting(ToolCall::arguments)
			.containsExactly("{\"q\":\"argi\"}");
	}

	@Test
	void rejectsReplayWhenTheRecordedRequestDiffers() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		recorder.recordRequest(new ModelTurnRecorder.Turn(STREAM, "agent:0"), request(FIRST_TURN));
		recorder.commit(new ModelTurnRecorder.Turn(STREAM, "agent:0"), toolCallingResponse("provider-call-1"), TOOLS);

		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:0");
		List<ChatMessage> different = List.of(ChatMessage.user("a different instruction"));

		assertThatThrownBy(() -> recorder.replay(turn, request(different)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("different request")
			.hasCauseInstanceOf(RunStoreConflictException.class);
	}

	@Test
	void anInterruptedTurnFailsClosedInsteadOfAskingAgain() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		recorder.recordRequest(new ModelTurnRecorder.Turn(STREAM, "agent:0"), request(FIRST_TURN));

		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:0");

		assertThatThrownBy(() -> recorder.replay(turn, request(FIRST_TURN)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("never recorded");
		assertThat(journal.find(STREAM, "agent:0#resp")).isEmpty();
	}

	@Test
	void ajournalFailureSurfacesAsAJournalFailure() {
		RunJournal failing = new RunJournal() {
			@Override
			public Optional<RunEntry> find(String streamId, String entryId) {
				return Optional.empty();
			}

			@Override
			public List<RunEntry> entries(String streamId) {
				return List.of();
			}

			@Override
			public void append(String streamId, List<RunEntry> entries) {
				throw new IllegalStateException("journal unavailable");
			}
		};
		ModelTurnRecorder recorder = ModelTurnRecorder.of(failing);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:0");

		assertThatThrownBy(() -> recorder.recordRequest(turn, request(FIRST_TURN)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasRootCauseMessage("journal unavailable");
	}

	@Test
	void aCommittedTurnAddsNoSecondPlanWhenTheProviderRenamesTheCallId() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:0");
		recorder.recordRequest(turn, request(FIRST_TURN));
		recorder.commit(turn, toolCallingResponse("provider-call-1"), TOOLS);

		// Recovery replays; it must not append a new task for the renamed call id.
		recorder.replay(new ModelTurnRecorder.Turn(STREAM, "agent:0"), request(FIRST_TURN));

		assertThat(journal.entryIds(STREAM)).containsExactly("agent:0#req", "agent:0#resp", "agent:0#task.0");
	}

	@Test
	void streamedTextIsRecordedUnderOneStableAttempt() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:0");
		String attempt = ModelTurnRecorder.newAttempt();

		recorder.recordDelta(turn, attempt, 0, "he");
		recorder.recordDelta(turn, attempt, 1, "llo");
		recorder.recordDelta(turn, attempt, 2, "");

		assertThat(journal.entryIds(STREAM)).containsExactly("agent:0#progress." + attempt + "#delta.0",
				"agent:0#progress." + attempt + "#delta.1");
		// Progress is diagnostic only: it never produces a response or a plan.
		assertThat(journal.find(STREAM, "agent:0#resp")).isEmpty();
		assertThat(journal.find(STREAM, "agent:0#task.0")).isEmpty();
	}

	@Test
	void differentCallsWithTheSameRequestHaveIndependentRecords() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn first = new ModelTurnRecorder.Turn(STREAM, "first-call");
		ModelTurnRecorder.Turn next = new ModelTurnRecorder.Turn(STREAM, "next-call");
		recorder.recordRequest(first, request(FIRST_TURN));
		recorder.commit(first, toolCallingResponse("call-1"), TOOLS);

		assertThat(recorder.replay(next, request(FIRST_TURN))).isEmpty();
		recorder.recordRequest(next, request(FIRST_TURN));
		recorder.commit(next, toolCallingResponse("call-2"), TOOLS);

		assertThat(journal.entryIds(STREAM)).containsExactly("first-call#req", "first-call#resp", "first-call#task.0",
				"next-call#req", "next-call#resp", "next-call#task.0");
	}

	/**
	 * Regression guard for the defect that made summarisation fatal: a count over the
	 * conversation shrinks when history is summarised or trimmed, so the next call reused
	 * an ordinal that an earlier turn already owned and every later turn conflicted.
	 */
	@Test
	void shrinkingTheConversationDoesNotCollideWithAnEarlierTurn() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		for (long ordinal = 0; ordinal < 3; ordinal++) {
			ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "agent:" + ordinal);
			recorder.recordRequest(turn, request(List.of(ChatMessage.user("question " + ordinal))));
			recorder.commit(turn, toolCallingResponse("call-" + ordinal), TOOLS);
		}

		// Summarisation replaced the history with a summary plus a short tail, so the
		// conversation now holds fewer assistant messages than before. The engine supplies
		// a new identity independently of that history.
		List<ChatMessage> summarisedHistory = List.of(ChatMessage.system("summary of earlier turns"),
				ChatMessage.user("question 3"));
		ModelTurnRecorder.Turn next = new ModelTurnRecorder.Turn(STREAM, "after-summary");

		assertThat(recorder.replay(next, request(summarisedHistory))).isEmpty();
		recorder.recordRequest(next, request(summarisedHistory));
		assertThat(journal.find(STREAM, "after-summary#req")).isPresent();
	}

	/**
	 * Documented limitation: a request rewritten non-deterministically between the original
	 * call and a restart - for example by a summarisation hook that asks a model for the
	 * summary - yields a different fingerprint for the same turn. The turn must fail closed
	 * rather than replay a response that was produced for other input.
	 */
	@Test
	void aNonDeterministicRewriteOfTheSameTurnFailsClosed() {
		MemoryRunJournal journal = new MemoryRunJournal();
		ModelTurnRecorder recorder = ModelTurnRecorder.of(journal);
		ModelTurnRecorder.Turn turn = new ModelTurnRecorder.Turn(STREAM, "summary-turn");
		recorder.recordRequest(turn, request(List.of(ChatMessage.system("summary, first attempt"),
				ChatMessage.user("question"))));
		recorder.commit(turn, toolCallingResponse("call-1"), TOOLS);

		// After a restart the same hook produced a different summary for the same turn.
		List<ChatMessage> rebuilt = List.of(ChatMessage.system("summary, second attempt"),
				ChatMessage.user("question"));

		assertThatThrownBy(() -> recorder.replay(new ModelTurnRecorder.Turn(STREAM, "summary-turn"), request(rebuilt)))
			.isInstanceOf(ModelTurnJournalException.class)
			.hasMessageContaining("summarisation");
	}

	@Test
	void aDisabledJournalYieldsNoRecorder() {
		assertThat(ModelTurnRecorder.of(null)).isNull();
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
