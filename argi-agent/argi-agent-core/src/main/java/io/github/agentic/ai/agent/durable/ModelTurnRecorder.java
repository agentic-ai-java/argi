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
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Prepares journal data for a model turn and validates recorded results on recovery.
 *
 * <p>
 * Stateless: every preparation method returns immutable records and writes nothing. The
 * runtime owns the store, lease, revision and committed snapshot, and decides whether
 * journaling is in use at all. It commits the request fingerprint and attempt before
 * calling the provider, and the response with all its plans and the checkpoint in one
 * {@link RunStore#commit} before dispatching a tool. Store exceptions propagate unchanged;
 * they are not model failures.
 *
 * <p>
 * Entry ids are generated and recognized by {@link ModelTurnEntryIds}:
 * <ul>
 * <li>{@code <turnId>#req} - the request fingerprint</li>
 * <li>{@code <turnId>#attempt.<ordinal>} - a model-call reservation, starting at 1</li>
 * <li>{@code <turnId>#progress.<attempt>#delta.<seq>} - streamed text, diagnostic only</li>
 * <li>{@code <turnId>#resp} - the complete terminal response</li>
 * <li>{@code <turnId>#task.<ordinal>} - one planned tool call</li>
 * </ul>
 *
 * <p>
 * A matching request without a response or plan may be re-asked within its per-turn attempt
 * limit. By default two calls are allowed in total, including the first. Every invocation
 * requires a committed reservation from {@link #prepareAttempt(Turn, ChatRequest, RunSnapshot)};
 * restarting never resets the count. Even a reservation whose call was not issued consumes
 * an attempt. Run-wide, token and cost budgets remain runtime responsibilities.
 */
public final class ModelTurnRecorder {

	/** Total model-call reservations per turn, including the first invocation. */
	public static final int DEFAULT_MAX_ATTEMPTS = 2;

	private static final int ENTRY_SCHEMA_VERSION = 1;

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
	 * Returns only the response from {@link #replayTurn(Turn, ChatRequest, RunSnapshot)},
	 * with the same journal consistency checks and no storage side effects.
	 */
	public Optional<ChatResponse> replay(Turn turn, ChatRequest request, RunSnapshot snapshot) {
		return replayTurn(turn, request, snapshot).map(RecordedModelTurn::response);
	}

	/**
	 * Returns the recorded response and ordered tool plans of this turn when the request
	 * is the one that produced them. Both initial execution after a successful response
	 * commit and recovery can consume this same view of the committed snapshot.
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
	 * prepare and commit an attempt using this snapshot's revision before calling the model.
	 * An empty result does not grant permission to call the model. A conflict
	 * requires reloading and repeating replay, not merely retrying with a newer revision.
	 * @param turn the engine-supplied identity of this logical call
	 * @param request the request about to be issued
	 * @param snapshot the runtime's consistent committed view of the run
	 * @return the response and plans, or empty when the turn has no committed result or plan
	 * @throws ModelTurnJournalException when recorded data is incomplete, incompatible or
	 * does not match the run or request
	 */
	public Optional<RecordedModelTurn> replayTurn(Turn turn, ChatRequest request, RunSnapshot snapshot) {
		requireNonNull(turn, "turn cannot be null");
		requireNonNull(request, "request cannot be null");
		requireNonNull(snapshot, "snapshot cannot be null");
		if (!turn.runId().equals(snapshot.spec().runId())) {
			throw new ModelTurnJournalException("Run snapshot does not match model turn " + turn.turnId());
		}
		RunEntry recorded = snapshot.journal().get(ModelTurnEntryIds.request(turn));
		RunEntry response = snapshot.journal().get(ModelTurnEntryIds.response(turn));
		Map<String, RunEntry> plans = recordedEntries(snapshot,
				id -> ModelTurnEntryIds.planOrdinal(turn, id).isPresent());
		Map<String, RunEntry> attempts = recordedEntries(snapshot,
				id -> ModelTurnEntryIds.attemptOrdinal(turn, id).isPresent());
		if (recorded == null && response == null && plans.isEmpty() && attempts.isEmpty()) {
			return Optional.empty();
		}
		if (recorded == null) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId() + " is missing its request fingerprint");
		}
		validateEntry(recorded, ModelTurnEntryIds.request(turn), RunEntry.Kind.MODEL_CALL, ModelTurnCodec.REQUEST_TYPE);
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
		reservedAttempts(turn, attempts, hash);
		if (response == null) {
			if (!plans.isEmpty()) {
				throw new ModelTurnJournalException("Model turn " + turn.turnId() + " has tool plans without a response");
			}
			return Optional.empty();
		}
		validateEntry(response, ModelTurnEntryIds.response(turn), RunEntry.Kind.MODEL_CALL, ModelTurnCodec.RESPONSE_TYPE);
		ChatResponse decoded = ModelTurnCodec.decodeResponse(response.payload().json());
		List<RunEntry> expected = preparePlans(turn, decoded, request.tools());
		if (plans.size() != expected.size()) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId() + " has an incomplete or extra tool plan");
		}
		List<PlannedToolCall> decodedPlans = new ArrayList<>();
		for (RunEntry planned : expected) {
			RunEntry actual = plans.get(planned.id());
			if (actual == null) {
				throw new ModelTurnJournalException("Missing tool plan entry " + planned.id());
			}
			PlannedToolCall decodedPlan = ModelTurnCodec.decodeToolPlan(turn, actual);
			if (!actual.equals(planned)) {
				throw new ModelTurnJournalException("Recorded tool plan does not match the response and request: "
						+ planned.id());
			}
			decodedPlans.add(decodedPlan);
		}
		return Optional.of(new RecordedModelTurn(decoded, decodedPlans));
	}

	/**
	 * Prepares only the request fingerprint. This low-level operation does not reserve a
	 * model call; an invocation path must use
	 * {@link #prepareAttempt(Turn, ChatRequest, RunSnapshot)} before calling the provider.
	 */
	public List<RunEntry> prepareRequest(Turn turn, ChatRequest request) {
		return List.of(new RunEntry(ENTRY_SCHEMA_VERSION, ModelTurnEntryIds.request(turn),
				RunEntry.Kind.MODEL_CALL, new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.REQUEST_TYPE,
						ModelTurnCodec.encodeRequest(request))));
	}

	/** Prepares a model-call reservation using {@link #DEFAULT_MAX_ATTEMPTS}. */
	public List<RunEntry> prepareAttempt(Turn turn, ChatRequest request, RunSnapshot snapshot) {
		return prepareAttempt(turn, request, snapshot, DEFAULT_MAX_ATTEMPTS);
	}

	/**
	 * Prepares the next model-call reservation and, for a new turn, its request fingerprint.
	 * The runtime must commit the entire batch with this snapshot's revision, retaining the
	 * checkpoint, before invoking the provider. Only a successful commit authorizes one call;
	 * preparation alone never consumes or grants an attempt. On conflict, reload and replay.
	 *
	 * <p>
	 * The limit includes the first call. A legacy request without attempt records counts as
	 * one reservation. Reservations are never refunded for a lost response or an interrupted
	 * invocation; changing the limit does not reset the stored count. Streaming delta tokens
	 * are unrelated to this count. This limit does not replace runtime-owned run budgets.
	 * @param turn the logical model call
	 * @param request the effective provider request
	 * @param snapshot the committed view used for replay and the reservation commit
	 * @param maxAttempts positive total invocation limit; 1 disables recovery re-asking
	 * @return immutable entries to commit before at most one provider invocation
	 * @throws ModelTurnJournalException if the turn already has a result, its data is
	 * incompatible, or its model-call budget is exhausted
	 * @throws IllegalArgumentException if maxAttempts is not positive
	 */
	public List<RunEntry> prepareAttempt(Turn turn, ChatRequest request, RunSnapshot snapshot, int maxAttempts) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be positive");
		}
		if (replay(turn, request, snapshot).isPresent()) {
			throw new ModelTurnJournalException("Model turn " + turn.turnId() + " already has a recorded response");
		}
		String hash = ModelTurnCodec.requestHash(request);
		boolean hasRequest = snapshot.journal().containsKey(ModelTurnEntryIds.request(turn));
		int used = hasRequest ? reservedAttempts(turn,
				recordedEntries(snapshot, id -> ModelTurnEntryIds.attemptOrdinal(turn, id).isPresent()), hash) : 0;
		if (used >= maxAttempts) {
			throw new ModelTurnJournalException(
					"Model-call budget exhausted for turn " + turn.turnId() + " (maxAttempts=" + maxAttempts + ")");
		}
		List<RunEntry> entries = new ArrayList<>();
		if (!hasRequest) {
			entries.addAll(prepareRequest(turn, request));
		}
		int ordinal = used + 1;
		entries.add(new RunEntry(ENTRY_SCHEMA_VERSION, ModelTurnEntryIds.attempt(turn, ordinal),
				RunEntry.Kind.MODEL_CALL, new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.ATTEMPT_TYPE,
						ModelTurnCodec.encodeAttempt(ordinal, hash))));
		return List.copyOf(entries);
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
		entries.add(new RunEntry(ENTRY_SCHEMA_VERSION, ModelTurnEntryIds.response(turn), RunEntry.Kind.MODEL_CALL,
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
				ModelTurnEntryIds.delta(turn, attempt, seq), RunEntry.Kind.EVENT,
				new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.DELTA_TYPE,
						ModelTurnCodec.encodeDelta(text))));
	}

	/**
	 * A fresh diagnostic token for one streaming attempt, not a model-call reservation.
	 * Every subscription that starts a new provider call must use its own token, so an
	 * abandoned attempt cannot overwrite the text of the attempt that replaced it.
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
			entries.add(new RunEntry(ENTRY_SCHEMA_VERSION, ModelTurnEntryIds.plan(turn, ordinal),
					RunEntry.Kind.TASK_CALL,
					new RunPayload(ModelTurnCodec.SCHEMA_VERSION, ModelTurnCodec.PLAN_TYPE,
							ModelTurnCodec.encodeToolPlan(call, byName.get(call.name()), ordinal))));
			ordinal++;
		}
		return entries;
	}

	/** Matching entries keyed by id; extra ordinals remain visible to validation. */
	private Map<String, RunEntry> recordedEntries(RunSnapshot snapshot, Predicate<String> matches) {
		Map<String, RunEntry> entries = new HashMap<>();
		snapshot.journal().forEach((id, entry) -> {
			if (matches.test(id)) {
				entries.put(id, entry);
			}
		});
		return entries;
	}

	private int reservedAttempts(Turn turn, Map<String, RunEntry> attempts, String requestHash) {
		// A request written before attempt journaling still reserves its first invocation.
		int used = attempts.size() + (attempts.containsKey(ModelTurnEntryIds.attempt(turn, 1)) ? 0 : 1);
		attempts.forEach((id, entry) -> {
			validateEntry(entry, id, RunEntry.Kind.MODEL_CALL, ModelTurnCodec.ATTEMPT_TYPE);
			int ordinal = ModelTurnCodec.decodeAttemptOrdinal(entry.payload().json(), requestHash);
			if (ordinal > used || !id.equals(ModelTurnEntryIds.attempt(turn, ordinal))) {
				throw new ModelTurnJournalException("Invalid or non-contiguous model attempt entry " + id);
			}
		});
		return used;
	}

	private void validateEntry(RunEntry entry, String id, RunEntry.Kind kind, String type) {
		if (!id.equals(entry.id()) || entry.schemaVersion() != ENTRY_SCHEMA_VERSION || entry.kind() != kind
				|| !type.equals(entry.payload().type())
				|| entry.payload().schemaVersion() != ModelTurnCodec.SCHEMA_VERSION) {
			throw new ModelTurnJournalException("Incompatible journal entry " + id);
		}
	}

}
