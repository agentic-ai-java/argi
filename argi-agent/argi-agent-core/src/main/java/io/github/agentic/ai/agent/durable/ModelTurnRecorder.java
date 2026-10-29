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
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunSnapshot;
import io.github.agentic.ai.runtime.execution.run.RunStore;
import io.github.agentic.ai.runtime.execution.run.RunStoreConflictException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Prepares journal data for a model turn and validates recorded results on recovery.
 *
 * <p>
 * Stateless: every preparation method returns immutable records and writes nothing. The
 * runtime owns the store, lease, revision and committed snapshot, and decides whether
 * journaling is in use at all. It commits the request fingerprint before calling the
 * provider, and the response with all its plans and the checkpoint in one
 * {@link RunStore#commit} before dispatching a tool. Store exceptions propagate unchanged;
 * they are not model failures.
 *
 * <p>
 * Entry ids of one turn:
 * <ul>
 * <li>{@code <turnId>#req} - the request fingerprint</li>
 * <li>{@code <turnId>#progress.<attempt>#delta.<seq>} - streamed text, diagnostic only</li>
 * <li>{@code <turnId>#resp} - the complete terminal response</li>
 * <li>{@code <turnId>#task.<ordinal>} - one planned tool call</li>
 * </ul>
 *
 * <p>
 * An interrupted turn is never re-asked: a request without a response means its outcome is
 * unknown, and budgeted re-asking belongs to the retry path.
 */
public final class ModelTurnRecorder {

	private static final int ENTRY_SCHEMA_VERSION = 1;

	private static final String PLAN_PREFIX = "#task.";

	/** Creates a stateless recorder; the runtime decides whether to use journaling. */
	public ModelTurnRecorder() {
	}

	/**
	 * Engine-supplied identity of one logical model call. The engine restores the same
	 * identity on recovery and assigns distinct turn ids to different calls within a run.
	 * @param runId the run to which the prepared records belong
	 * @param turnId the stable logical call identity within that run
	 */
	public record Turn(String runId, String turnId) {

		public Turn {
			if (runId == null || runId.isBlank() || turnId == null || turnId.isBlank()) {
				throw new IllegalArgumentException("runId and turnId must not be blank");
			}
		}

	}

	/**
	 * Returns the recorded response of this turn when the request is the one that produced
	 * it.
	 *
	 * <p>
	 * Reads only the supplied snapshot. A response requires its matching request and exactly
	 * the tool plans described by that response and request. Orphaned or incompatible data
	 * fails closed. These checks validate current contents, not historical commit boundaries
	 * or the meaning of an application-owned checkpoint.
	 *
	 * <p>
	 * The fingerprint covers the request the provider actually received, after every
	 * request-mutating interceptor. A rewrite that differs on the second call - for example
	 * summarisation that asks a model for its summary - therefore fails closed here instead
	 * of replaying a response produced for other input. Applications that need automatic
	 * recovery across such a rewrite must make it deterministic while journaling.
	 *
	 * <p>
	 * This method cannot detect snapshot staleness. After an empty result, the runtime must
	 * commit the request using this snapshot's revision before calling the model. A conflict
	 * requires reloading and repeating replay, not merely retrying with a newer revision.
	 * @param turn the engine-supplied identity of this logical call
	 * @param request the request about to be issued
	 * @param snapshot the runtime's consistent committed view of the run
	 * @return the recorded response, or empty when no request, response or plan is recorded
	 * @throws ModelTurnJournalException when recorded data is incomplete, incompatible or
	 * does not match the run or request
	 */
	public Optional<ChatResponse> replay(Turn turn, ChatRequest request, RunSnapshot snapshot) {
		requireNonNull(turn, "turn cannot be null");
		requireNonNull(request, "request cannot be null");
		requireNonNull(snapshot, "snapshot cannot be null");
		if (!turn.runId().equals(snapshot.spec().runId())) {
			throw new ModelTurnJournalException("Run snapshot does not match model turn " + turn.turnId());
		}
		RunEntry recorded = snapshot.journal().get(turn.turnId() + "#req");
		RunEntry response = snapshot.journal().get(turn.turnId() + "#resp");
		Map<String, RunEntry> plans = recordedPlans(turn, snapshot);
		if (recorded == null && response == null && plans.isEmpty()) {
			return Optional.empty();
		}
		if (recorded == null) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId() + " is missing its request fingerprint");
		}
		validateEntry(recorded, turn.turnId() + "#req", RunEntry.Kind.MODEL_CALL, ModelTurnCodec.REQUEST_TYPE);
		String hash = ModelTurnCodec.decodeRequestHash(recorded.payload().json());
		if (hash == null || hash.isBlank()) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId() + " has an invalid request fingerprint");
		}
		if (!hash.equals(ModelTurnCodec.requestHash(request))) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId()
					+ " was recorded for a different request; refusing to replay it. This happens when the"
					+ " request is rewritten non-deterministically, for example by summarisation, or when"
					+ " the engine reuses a turn identity",
					new RunStoreConflictException(
							"Journal entry id identifies different data: " + turn.runId() + "/" + turn.turnId()));
		}
		if (response == null) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId()
					+ " was issued but its response was never recorded; the turn cannot be replayed automatically");
		}
		validateEntry(response, turn.turnId() + "#resp", RunEntry.Kind.MODEL_CALL, ModelTurnCodec.RESPONSE_TYPE);
		ChatResponse decoded = ModelTurnCodec.decodeResponse(response.payload().json());
		List<RunEntry> expected = preparePlans(turn, decoded, request.tools());
		if (plans.size() != expected.size()) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId() + " has an incomplete or extra tool plan");
		}
		for (RunEntry planned : expected) {
			RunEntry actual = plans.get(planned.id());
			if (actual == null) {
				throw new ModelTurnJournalException("Missing tool plan entry " + planned.id());
			}
			validateEntry(actual, planned.id(), RunEntry.Kind.TASK_CALL, ModelTurnCodec.PLAN_TYPE);
			if (!actual.equals(planned)) {
				throw new ModelTurnJournalException("Recorded tool plan does not match the response and request: "
						+ planned.id());
			}
		}
		return Optional.of(decoded);
	}

	/** Prepares the request fingerprint; the runtime commits it before calling the provider. */
	public List<RunEntry> prepareRequest(Turn turn, ChatRequest request) {
		return List.of(new RunEntry(ENTRY_SCHEMA_VERSION, turn.turnId() + "#req",
				RunEntry.Kind.MODEL_CALL, new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.REQUEST_TYPE,
						ModelTurnCodec.encodeRequest(request))));
	}

	/**
	 * Prepares the terminal response and every tool plan without publishing any records.
	 * The runtime must commit this entire batch with the corresponding checkpoint through
	 * {@link RunStore#commit} before dispatching the first tool. Preparing the batch
	 * does not authorize dispatch.
	 * @param turn the turn being prepared
	 * @param response the terminal response the engine is about to use
	 * @param tools the tool definitions that were disclosed to the model, used to pin each
	 * planned task to the schema the model saw
	 * @return an immutable batch containing the response followed by its tool plans
	 */
	public List<RunEntry> prepareResponse(Turn turn, ChatResponse response, Iterable<ToolDefinition> tools) {
		List<RunEntry> entries = new ArrayList<>();
		entries.add(new RunEntry(ENTRY_SCHEMA_VERSION, turn.turnId() + "#resp", RunEntry.Kind.MODEL_CALL,
				new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.RESPONSE_TYPE,
						ModelTurnCodec.encodeResponse(response))));
		entries.addAll(preparePlans(turn, response, tools));
		return List.copyOf(entries);
	}

	/**
	 * Prepares diagnostic text received while a response is still streaming.
	 *
	 * <p>
	 * These entries are diagnostic: they explain how far a lost stream got. They are never
	 * a source of truth, because a partial response is not a model result and must not
	 * trigger tool execution. The attempt identifier keeps text from an abandoned attempt
	 * from colliding with a later one, and is stable for every chunk of one attempt so the
	 * recorded text stays ordered.
	 *
	 * <p>
	 * The runtime submits these through its single commit coordinator, retaining the
	 * checkpoint, and handles their completion before committing the terminal response.
	 * @param attempt the attempt token obtained from {@link #newAttempt()}
	 * @param seq the chunk ordinal within this attempt
	 * @param text the text delta received
	 */
	public List<RunEntry> prepareDelta(Turn turn, String attempt, int seq, String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		return List.of(new RunEntry(ENTRY_SCHEMA_VERSION,
				turn.turnId() + "#progress." + attempt + "#delta." + seq, RunEntry.Kind.EVENT,
				new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.DELTA_TYPE,
						ModelTurnCodec.encodeDelta(text))));
	}

	/**
	 * A fresh identifier for one streaming attempt. Every subscription that starts a new
	 * provider call must use its own token, so an abandoned attempt cannot overwrite the
	 * text of the attempt that replaced it.
	 */
	public static String newAttempt() {
		return UUID.randomUUID().toString();
	}

	private List<RunEntry> preparePlans(Turn turn, ChatResponse response, Iterable<ToolDefinition> tools) {
		List<RunEntry> entries = new ArrayList<>();
		Map<String, ToolDefinition> byName = new HashMap<>();
		if (tools != null) {
			tools.forEach(definition -> byName.put(definition.name(), definition));
		}
		int ordinal = 0;
		for (ToolCall call : response.toolCalls()) {
			entries.add(new RunEntry(ENTRY_SCHEMA_VERSION, planId(turn, ordinal),
					RunEntry.Kind.TASK_CALL,
					new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.PLAN_TYPE,
							ModelTurnCodec.encodeToolPlan(call, byName.get(call.name()), ordinal))));
			ordinal++;
		}
		return entries;
	}

	private static String planId(Turn turn, int ordinal) {
		return turn.turnId() + PLAN_PREFIX + ordinal;
	}

	/**
	 * Plan entries of this turn, keyed by id. Any entry under the turn's plan prefix counts,
	 * so an extra entry is visible as a size mismatch rather than being silently ignored.
	 * The suffix must be digits: a turn id may itself contain {@code #task.}, and without
	 * that check another turn's entries would leak into this one.
	 */
	private Map<String, RunEntry> recordedPlans(Turn turn, RunSnapshot snapshot) {
		String prefix = turn.turnId() + PLAN_PREFIX;
		Map<String, RunEntry> plans = new HashMap<>();
		snapshot.journal().forEach((id, entry) -> {
			if (id.startsWith(prefix) && isOrdinal(id.substring(prefix.length()))) {
				plans.put(id, entry);
			}
		});
		return plans;
	}

	private static boolean isOrdinal(String suffix) {
		return !suffix.isEmpty() && suffix.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	private void validateEntry(RunEntry entry, String id, RunEntry.Kind kind, String type) {
		if (!id.equals(entry.id()) || entry.schemaVersion() != ENTRY_SCHEMA_VERSION || entry.kind() != kind
				|| !type.equals(entry.payload().type())
				|| entry.payload().schemaVersion() != ModelTurnCodec.SCHEMA_VERSION) {
			throw new ModelTurnJournalException("Incompatible journal entry " + id);
		}
	}

}
