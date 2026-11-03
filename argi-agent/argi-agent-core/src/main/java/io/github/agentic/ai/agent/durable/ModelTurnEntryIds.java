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

import io.github.agentic.ai.agent.durable.ModelTurnRecorder.Turn;

import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

/**
 * Canonical journal entry ids of one model turn. This is the single definition of their
 * wire format. Entry ids are local to a run; they do not encode its namespace or run id.
 * Payload versions, journal consistency and execution policy remain separate concerns.
 */
public final class ModelTurnEntryIds {

	private static final String PLAN_PREFIX = "#task.";

	private static final String ATTEMPT_PREFIX = "#attempt.";

	private ModelTurnEntryIds() {
	}

	/** The effective request fingerprint. */
	public static String request(Turn turn) {
		return turn.turnId() + "#req";
	}

	/** The complete terminal response. */
	public static String response(Turn turn) {
		return turn.turnId() + "#resp";
	}

	/** A model-call reservation, numbered from one. */
	public static String attempt(Turn turn, int ordinal) {
		if (ordinal < 1) {
			throw new IllegalArgumentException("attempt ordinal must be positive");
		}
		return turn.turnId() + ATTEMPT_PREFIX + ordinal;
	}

	/** A planned tool call, numbered from zero in response order. */
	public static String plan(Turn turn, int ordinal) {
		if (ordinal < 0) {
			throw new IllegalArgumentException("plan ordinal must be non-negative");
		}
		return turn.turnId() + PLAN_PREFIX + ordinal;
	}

	/** Diagnostic text within a stream attempt; the token is not a call reservation. */
	public static String delta(Turn turn, String streamAttempt, int seq) {
		return turn.turnId() + "#progress." + streamAttempt + "#delta." + seq;
	}

	/**
	 * Reads a plan ordinal of this turn, or empty for an unrelated entry. Matching digit
	 * suffixes with leading zeroes or values outside the int range are corrupt journal ids
	 * and throw rather than disappearing from replay's complete-plan check.
	 */
	public static OptionalInt planOrdinal(Turn turn, String entryId) {
		return ordinal(turn, entryId, PLAN_PREFIX, 0);
	}

	/**
	 * Reads a reservation ordinal of this turn, or empty for an unrelated entry. Matching
	 * digit suffixes must be canonical positive ints; invalid ones throw so a corrupt
	 * reservation cannot silently disappear from the attempt budget.
	 */
	public static OptionalInt attemptOrdinal(Turn turn, String entryId) {
		return ordinal(turn, entryId, ATTEMPT_PREFIX, 1);
	}

	private static OptionalInt ordinal(Turn turn, String entryId, String suffix, int minimum) {
		requireNonNull(entryId, "entryId cannot be null");
		String prefix = turn.turnId() + suffix;
		if (!entryId.startsWith(prefix)) {
			return OptionalInt.empty();
		}
		String digits = entryId.substring(prefix.length());
		// Turn ids may contain these delimiters: another turn's entry must not leak in.
		if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return OptionalInt.empty();
		}
		if (digits.length() > 1 && digits.charAt(0) == '0') {
			throw new ModelTurnJournalException("Non-canonical journal entry ordinal: " + entryId);
		}
		try {
			int value = Integer.parseInt(digits);
			if (value < minimum) {
				throw new ModelTurnJournalException("Invalid journal entry ordinal: " + entryId);
			}
			return OptionalInt.of(value);
		}
		catch (NumberFormatException ex) {
			throw new ModelTurnJournalException("Journal entry ordinal exceeds the int range: " + entryId, ex);
		}
	}

}
