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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelTurnEntryIdsTest {

	private static final ModelTurnRecorder.Turn TURN = new ModelTurnRecorder.Turn("run", "agent:0");

	@Test
	void preservesTheExistingWireIdsAndTaskIdentity() {
		assertThat(ModelTurnEntryIds.request(TURN)).isEqualTo("agent:0#req");
		assertThat(ModelTurnEntryIds.response(TURN)).isEqualTo("agent:0#resp");
		assertThat(ModelTurnEntryIds.attempt(TURN, 1)).isEqualTo("agent:0#attempt.1");
		assertThat(ModelTurnEntryIds.plan(TURN, 0)).isEqualTo("agent:0#task.0");
		assertThat(ModelTurnEntryIds.delta(TURN, "stream-token", 2))
				.isEqualTo("agent:0#progress.stream-token#delta.2");
		assertThat(new ToolTaskIdentity(TURN.runId(), TURN.turnId(), 0).entryId())
				.isEqualTo(ModelTurnEntryIds.plan(TURN, 0));
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 10, Integer.MAX_VALUE })
	void numberedIdsRoundTripWithoutOverflow(int ordinal) {
		assertThat(ModelTurnEntryIds.planOrdinal(TURN, ModelTurnEntryIds.plan(TURN, ordinal)))
				.isEqualTo(OptionalInt.of(ordinal));
		assertThat(ModelTurnEntryIds.attemptOrdinal(TURN, ModelTurnEntryIds.attempt(TURN, ordinal)))
				.isEqualTo(OptionalInt.of(ordinal));
	}

	@Test
	void plansStartAtZeroAndReservationsStartAtOne() {
		assertThat(ModelTurnEntryIds.planOrdinal(TURN, "agent:0#task.0")).isEqualTo(OptionalInt.of(0));
		assertThatThrownBy(() -> ModelTurnEntryIds.plan(TURN, -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ModelTurnEntryIds.attempt(TURN, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ModelTurnEntryIds.attemptOrdinal(TURN, "agent:0#attempt.0"))
				.isInstanceOf(ModelTurnJournalException.class);
	}

	@Test
	void nestedTurnIdsDoNotLeakIntoTheirParent() {
		ModelTurnRecorder.Turn nested = new ModelTurnRecorder.Turn("run", "agent:0#task.0#attempt.1");
		String plan = ModelTurnEntryIds.plan(nested, 2);
		String attempt = ModelTurnEntryIds.attempt(nested, 3);

		assertThat(ModelTurnEntryIds.planOrdinal(nested, plan)).isEqualTo(OptionalInt.of(2));
		assertThat(ModelTurnEntryIds.attemptOrdinal(nested, attempt)).isEqualTo(OptionalInt.of(3));
		assertThat(ModelTurnEntryIds.planOrdinal(TURN, plan)).isEqualTo(OptionalInt.empty());
		assertThat(ModelTurnEntryIds.attemptOrdinal(TURN, attempt)).isEqualTo(OptionalInt.empty());
		assertThat(ModelTurnEntryIds.planOrdinal(TURN, ModelTurnEntryIds.request(nested)))
				.isEqualTo(OptionalInt.empty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "other#task.0", "agent:00#task.0", "agent:0#req", "agent:0#resp",
			"agent:0#task.", "agent:0#task.x", "agent:0#task.1#resp", "agent:0#attempt.",
			"agent:0#attempt.x", "agent:0#attempt.1#req" })
	void unrelatedEntriesAreNotNumberedEntriesOfThisTurn(String entryId) {
		assertThat(ModelTurnEntryIds.planOrdinal(TURN, entryId)).isEqualTo(OptionalInt.empty());
		assertThat(ModelTurnEntryIds.attemptOrdinal(TURN, entryId)).isEqualTo(OptionalInt.empty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "00", "01", "2147483648", "9999999999999999999999999" })
	void corruptNumericSuffixesCannotBeSilentlyDropped(String suffix) {
		assertThatThrownBy(() -> ModelTurnEntryIds.planOrdinal(TURN, "agent:0#task." + suffix))
				.isInstanceOf(ModelTurnJournalException.class);
		assertThatThrownBy(() -> ModelTurnEntryIds.attemptOrdinal(TURN, "agent:0#attempt." + suffix))
				.isInstanceOf(ModelTurnJournalException.class);
	}

}
