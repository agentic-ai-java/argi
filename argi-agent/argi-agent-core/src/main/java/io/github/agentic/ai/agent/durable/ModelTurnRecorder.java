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

import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.runtime.execution.run.RunEntry;
import io.github.agentic.ai.runtime.execution.run.RunJournal;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunStoreConflictException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Records the non-deterministic part of a model turn and replays it on recovery.
 *
 * <p>
 * This component is engine-neutral: it knows the neutral chat contracts and the journal,
 * not graphs, streams, checkpoints or schedulers. An engine supplies the stream identity of
 * its invocation and the ordinal of the model call, then records streamed text as it
 * arrives and commits the terminal response before dispatching any tool.
 *
 * <p>
 * A turn is identified by the stream, the agent name and an engine-supplied ordinal. The
 * recorder deliberately does not derive the ordinal from the conversation: summarisation
 * and history trimming legitimately shrink the message list, so any count over it can move
 * backwards and collide with an earlier turn. See {@link #begin(String, long)} for what the
 * engine must guarantee. Two agents sharing one stream must not share a name; when they
 * do, the request fingerprint rejects the mismatch instead of replaying another agent's
 * response.
 *
 * <p>
 * The request fingerprint is taken from the request the provider actually receives, after
 * every request-mutating interceptor and hook. A component that rewrites the request
 * non-deterministically - for example summarisation that calls a model to produce the
 * summary - can rebuild a different request for the same turn after a restart. Such a turn
 * fails closed with a conflict rather than replaying a response produced for other input;
 * it is not recovered automatically. Applications that need automatic recovery across such
 * a rewrite must make the rewrite deterministic or disable it while journaling.
 *
 * <p>
 * Entry ids of one turn:
 * <ul>
 * <li>{@code <agent>:<turn>#req} - the request fingerprint, written before the call</li>
 * <li>{@code <agent>:<turn>#progress.<attempt>#delta.<seq>} - streamed text, diagnostic
 * only</li>
 * <li>{@code <agent>:<turn>#resp} - the complete terminal response</li>
 * <li>{@code <agent>:<turn>#task.<ordinal>} - the tool plan, committed atomically with the
 * response</li>
 * </ul>
 *
 * <p>
 * An interrupted turn is never re-asked: a recorded request without a recorded response is
 * evidence that a call was already issued, and repeating it would make cost and outcome
 * depend on how often a worker restarted. Budgeted re-asking belongs to the retry path.
 */
public final class ModelTurnRecorder {

	private final RunJournal journal;

	private final String agentName;

	private ModelTurnRecorder(RunJournal journal, String agentName) {
		this.journal = requireNonNull(journal, "journal cannot be null");
		this.agentName = requireNonNull(agentName, "agentName cannot be null");
	}

	/** Builds a recorder for one agent, or returns {@code null} when journaling is off. */
	public static ModelTurnRecorder of(RunJournal journal, String agentName) {
		return journal == null ? null : new ModelTurnRecorder(journal, agentName);
	}

	/**
	 * One logical model call.
	 * @param streamId the engine's durable stream identity, for example a graph thread id or
	 * an agent run id
	 * @param turnId the agent and turn identity within that stream
	 */
	public record Turn(String streamId, String turnId) {

		public Turn {
			requireNonNull(streamId, "streamId cannot be null");
			requireNonNull(turnId, "turnId cannot be null");
		}

	}

	/**
	 * Resolves the turn this model call belongs to.
	 *
	 * <p>
	 * The engine owns the ordinal and must guarantee three properties:
	 * <ul>
	 * <li><b>Monotonic</b> - it never moves backwards within a stream, regardless of how
	 * the conversation is summarised, trimmed or rewritten;</li>
	 * <li><b>Durable</b> - it is restored from committed data after a restart, never from a
	 * process-local counter;</li>
	 * <li><b>Stable on re-execution</b> - a model call that is re-executed after a crash
	 * before its result was committed resolves to the same ordinal, so the recorded turn is
	 * found and replayed.</li>
	 * </ul>
	 * A counter kept in persisted engine state and advanced in the same update that
	 * publishes the model result satisfies all three.
	 * @param streamId the engine's stream identity; an engine without one cannot journal
	 * @param ordinal the engine's monotonic model-call ordinal within the stream
	 * @throws IllegalArgumentException when the stream identity is blank or the ordinal is
	 * negative
	 */
	public Turn begin(String streamId, long ordinal) {
		if (streamId == null || streamId.isBlank()) {
			throw new IllegalArgumentException("Journal persistence requires a stream identity");
		}
		if (ordinal < 0) {
			throw new IllegalArgumentException("Model-call ordinal must not be negative: " + ordinal);
		}
		return new Turn(streamId, agentName + ":" + ordinal);
	}

	/**
	 * Returns the recorded response of this turn when the request is the one that produced
	 * it.
	 *
	 * <p>
	 * A recorded request whose fingerprint differs fails closed: replaying it would hand the
	 * engine a plan the model never produced for this input. This is also how a
	 * non-deterministic request rewrite surfaces after a restart; see the class
	 * documentation. A recorded request without a recorded response was interrupted after a
	 * provider call was issued and also fails closed, because the outcome is unknown rather
	 * than missing.
	 * @param turn the turn resolved by {@link #begin(String, long)}
	 * @param request the request about to be issued
	 * @return the recorded response, or empty when the model has not been called yet
	 * @throws ModelTurnJournalException when the recorded request differs, or when a turn
	 * was issued but its response was never recorded
	 */
	public Optional<ChatResponse> replay(Turn turn, ChatRequest request) {
		Optional<RunEntry> recorded = journal.find(turn.streamId(), turn.turnId() + "#req");
		if (recorded.isPresent()) {
			String hash = ModelTurnCodec.decodeRequestHash(recorded.get().payload().json());
			if (!hash.equals(ModelTurnCodec.requestHash(request))) {
				throw new ModelTurnJournalException("Model turn " + turn.turnId()
						+ " was recorded for a different request; refusing to replay it. This happens when the"
						+ " request is rewritten non-deterministically, for example by summarisation, or when"
						+ " the engine reuses an ordinal",
						new RunStoreConflictException(
								"Journal entry id identifies different data: " + turn.streamId() + "/" + turn.turnId()));
			}
		}
		Optional<RunEntry> response = journal.find(turn.streamId(), turn.turnId() + "#resp");
		if (response.isPresent()) {
			return response.map(entry -> ModelTurnCodec.decodeResponse(entry.payload().json()));
		}
		if (recorded.isPresent()) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId()
					+ " was issued but its response was never recorded; the turn cannot be replayed automatically");
		}
		return Optional.empty();
	}

	/** Records the request fingerprint before the provider call is issued. */
	public void recordRequest(Turn turn, ChatRequest request) {
		append(turn, List.of(new RunEntry(ModelTurnCodec.SCHEMA_VERSION, turn.turnId() + "#req",
				RunEntry.Kind.MODEL_CALL, new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.REQUEST_TYPE,
						ModelTurnCodec.encodeRequest(request)))));
	}

	/**
	 * Records the terminal response and, in the same atomic append, the tool plan derived
	 * from it. A failure here leaves no dispatchable plan behind, so the caller must treat
	 * it as a failure of the turn and must not dispatch the plan's tools.
	 * @param turn the turn being committed
	 * @param response the terminal response the engine is about to use
	 * @param tools the tool definitions that were disclosed to the model, used to pin each
	 * planned task to the schema the model saw
	 */
	public void commit(Turn turn, ChatResponse response, Iterable<ToolDefinition> tools) {
		List<RunEntry> entries = new ArrayList<>();
		entries.add(new RunEntry(ModelTurnCodec.SCHEMA_VERSION, turn.turnId() + "#resp", RunEntry.Kind.MODEL_CALL,
				new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.RESPONSE_TYPE,
						ModelTurnCodec.encodeResponse(response))));
		Map<String, ToolDefinition> byName = new HashMap<>();
		if (tools != null) {
			tools.forEach(definition -> byName.put(definition.name(), definition));
		}
		int ordinal = 0;
		for (ToolCall call : response.toolCalls()) {
			entries.add(new RunEntry(ModelTurnCodec.SCHEMA_VERSION, turn.turnId() + "#task." + ordinal,
					RunEntry.Kind.TASK_CALL,
					new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.PLAN_TYPE,
							ModelTurnCodec.encodeToolPlan(call, byName.get(call.name()), ordinal))));
			ordinal++;
		}
		append(turn, entries);
	}

	/**
	 * Records text received while a response is still streaming.
	 *
	 * <p>
	 * These entries are diagnostic: they explain how far a lost stream got. They are never
	 * a source of truth, because a partial response is not a model result and must not
	 * trigger tool execution. The attempt identifier keeps text from an abandoned attempt
	 * from colliding with a later one, and is stable for every chunk of one attempt so the
	 * recorded text stays ordered.
	 * @param attempt the attempt token obtained from {@link #newAttempt()}
	 * @param seq the chunk ordinal within this attempt
	 * @param text the text delta received
	 */
	public void recordDelta(Turn turn, String attempt, int seq, String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		append(turn, List.of(new RunEntry(ModelTurnCodec.SCHEMA_VERSION,
				turn.turnId() + "#progress." + attempt + "#delta." + seq, RunEntry.Kind.EVENT,
				new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.DELTA_TYPE,
						ModelTurnCodec.encodeDelta(text)))));
	}

	/**
	 * A fresh identifier for one streaming attempt. Every subscription that starts a new
	 * provider call must use its own token, so an abandoned attempt cannot overwrite the
	 * text of the attempt that replaced it.
	 */
	public static String newAttempt() {
		return UUID.randomUUID().toString();
	}

	private void append(Turn turn, List<RunEntry> entries) {
		try {
			journal.append(turn.streamId(), entries);
		}
		catch (ModelTurnJournalException already) {
			throw already;
		}
		catch (RuntimeException failure) {
			throw new ModelTurnJournalException("Failed to journal model turn " + turn.turnId(), failure);
		}
	}

}
