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
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.runtime.execution.run.RunEntry;
import io.github.agentic.ai.runtime.execution.run.RunPayload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelTurnPlanViewTest {

	private static final ModelTurnRecorder.Turn TURN = new ModelTurnRecorder.Turn("run", "agent:0");

	private static final String EMPTY_ARGUMENTS_HASH =
			"44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

	/** Version-1 wire fixture, independent of the current writer. */
	private static final String PLAN_JSON = """
			{"ordinal":0,"toolName":"unknown","definitionVersion":null,
			"argumentsHash":"44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a","arguments":"{}"}
			""";

	@Test
	void readsTheExistingWireFormatWithoutRewritingIt() {
		RunEntry entry = planEntry(PLAN_JSON);
		PlannedToolCall plan = ModelTurnCodec.decodeToolPlan(TURN, entry);

		assertThat(plan).isEqualTo(new PlannedToolCall(new ToolTaskIdentity("run", "agent:0", 0),
				"unknown", null, EMPTY_ARGUMENTS_HASH, "{}"));
		assertThat(plan.identity().entryId()).isEqualTo(entry.id());
		assertThat(planEntry(ModelTurnCodec.encodeToolPlan(new ToolCall("provider", "unknown", "{}"), null, 0)))
				.isEqualTo(entry);
		RunEntry withDefinition = planEntry(PLAN_JSON.replace("\"definitionVersion\":null",
				"\"definitionVersion\":\"schema-fingerprint\""));
		assertThat(ModelTurnCodec.decodeToolPlan(TURN, withDefinition).definitionFingerprint())
				.isEqualTo("schema-fingerprint");
	}

	@Test
	void readsUnknownToolsAndMalformedArgumentsWithoutBusinessValidation() {
		String arguments = "{ not valid JSON";
		RunEntry entry = planEntry(ModelTurnCodec.encodeToolPlan(
				new ToolCall("provider", "not-disclosed", arguments), null, 0));

		PlannedToolCall plan = ModelTurnCodec.decodeToolPlan(TURN, entry);

		assertThat(plan.toolName()).isEqualTo("not-disclosed");
		assertThat(plan.definitionFingerprint()).isNull();
		assertThat(plan.arguments()).isEqualTo(arguments);
	}

	@Test
	void rejectsIncompatibleKindsPayloadsAndEntryIdentities() {
		RunEntry entry = planEntry(PLAN_JSON);
		List<RunEntry> incompatible = List.of(
				new RunEntry(1, entry.id(), RunEntry.Kind.MODEL_CALL, entry.payload()),
				new RunEntry(1, entry.id(), entry.kind(), new RunPayload(2, ModelTurnCodec.PLAN_TYPE, PLAN_JSON)),
				new RunEntry(1, entry.id(), entry.kind(), new RunPayload(1, "other.type", PLAN_JSON)),
				new RunEntry(1, "agent:0#task.1", entry.kind(), entry.payload()),
				new RunEntry(1, "other#task.0", entry.kind(), entry.payload()));

		for (RunEntry invalid : incompatible) {
			assertThatThrownBy(() -> ModelTurnCodec.decodeToolPlan(TURN, invalid))
					.isInstanceOf(ModelTurnJournalException.class);
		}
	}

	@Test
	void rejectsInvalidStoredFieldsAndInconsistentHashes() {
		List<String> invalidPayloads = List.of("{}",
				PLAN_JSON.replace("\"ordinal\":0", "\"ordinal\":-1"),
				PLAN_JSON.replace("\"ordinal\":0", "\"ordinal\":\"0\""),
				PLAN_JSON.replace("\"ordinal\":0", "\"ordinal\":0.5"),
				PLAN_JSON.replace("\"ordinal\":0", "\"ordinal\":2147483648"),
				PLAN_JSON.replace("\"toolName\":\"unknown\"", "\"toolName\":null"),
				PLAN_JSON.replace("\"definitionVersion\":null", "\"definitionVersion\":{}"),
				PLAN_JSON.replace("\"arguments\":\"{}\"", "\"arguments\":{}"),
				PLAN_JSON.replace(EMPTY_ARGUMENTS_HASH, "different"));

		for (String invalid : invalidPayloads) {
			assertThatThrownBy(() -> ModelTurnCodec.decodeToolPlan(TURN, planEntry(invalid)))
					.isInstanceOf(ModelTurnJournalException.class);
		}
	}

	@Test
	void identitiesRemainRunScopedAndPreserveTurnIdsWithDelimiters() {
		ToolTaskIdentity first = new ToolTaskIdentity("run", "parent#task.0", 0);
		assertThat(first.entryId()).isEqualTo("parent#task.0#task.0");
		assertThat(first).isNotEqualTo(new ToolTaskIdentity("other-run", first.turnId(), 0));
		assertThat(first).isNotEqualTo(new ToolTaskIdentity("run", first.turnId(), 1));
		assertThatThrownBy(() -> new ToolTaskIdentity("run", "turn", -1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	void rejectsBlankIdentityComponents(String blank) {
		assertThatThrownBy(() -> new ToolTaskIdentity(blank, "turn", 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ToolTaskIdentity("run", blank, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void recordedTurnCopiesAndOrdersPlansWithoutChangingTheSourceList() {
		PlannedToolCall first = ModelTurnCodec.decodeToolPlan(TURN, planEntry(PLAN_JSON));
		PlannedToolCall second = new PlannedToolCall(new ToolTaskIdentity("run", "agent:0", 1),
				first.toolName(), null, first.argumentsHash(), first.arguments());
		List<PlannedToolCall> source = new ArrayList<>(List.of(second, first));
		ChatResponse response = new ChatResponse("response", "model", List.of(ChatMessage.assistant("done")),
				List.of(), FinishReason.STOP, null, Map.of());

		RecordedModelTurn recorded = new RecordedModelTurn(response, source);

		assertThat(source).containsExactly(second, first);
		source.clear();
		assertThat(recorded.plans()).containsExactly(first, second);
		assertThat(recorded.response()).isSameAs(response);
		assertThatThrownBy(() -> recorded.plans().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	private static RunEntry planEntry(String json) {
		return new RunEntry(1, "agent:0#task.0", RunEntry.Kind.TASK_CALL,
				new RunPayload(1, ModelTurnCodec.PLAN_TYPE, json));
	}

}
