/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.interceptor;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the registration contract of {@link BusinessOutcomeMapperRegistry}: results
 * of unregistered tools are passed through untouched (no guessing), registered mappers
 * are applied, {@link BusinessOutcomeAware} tools are picked up by
 * {@code fromTools}, and a mapper returning null is rejected loudly.
 */
@DisplayName("BusinessOutcomeMapperRegistry registration contract")
class BusinessOutcomeMapperRegistryTest {

	private static final ToolCall CALL = new ToolCall("call-1", "payment", "{}");

	private static ToolResult text(String text) {
		return ToolResult.text(text);
	}

	@Test
	void unregisteredToolResultIsPassedThroughUntouched() {
		BusinessOutcomeMapperRegistry registry = BusinessOutcomeMapperRegistry.empty();
		ToolResult raw = text("declined");

		assertThat(registry.mapOutcome(CALL, raw)).isSameAs(raw);
		assertThat(registry.hasMapper("payment")).isFalse();
	}

	@Test
	void registeredMapperIsAppliedToItsToolOnly() {
		BusinessOutcomeMapper decline = (call, result) -> new ToolResult(
				ToolResult.error("payment declined").content(), true, Map.of("errorCode", "PAYMENT_DECLINED"));
		BusinessOutcomeMapperRegistry registry = BusinessOutcomeMapperRegistry.builder()
			.declare("payment", decline)
			.build();

		ToolResult mapped = registry.mapOutcome(CALL, text("{\"status\":\"declined\"}"));
		assertThat(mapped.error()).isTrue();
		assertThat(mapped.metadata()).containsEntry("errorCode", "PAYMENT_DECLINED");

		ToolCall otherCall = new ToolCall("call-2", "weather", "{}");
		ToolResult untouched = text("sunny");
		assertThat(registry.mapOutcome(otherCall, untouched)).isSameAs(untouched);
	}

	@Test
	void fromToolsPicksUpBusinessOutcomeAwareToolsOnly() {
		Tool aware = new OutcomeAwareTool("payment", (call, result) -> ToolResult.error("declined"));
		Tool plain = new PlainTool("weather");

		BusinessOutcomeMapperRegistry registry = BusinessOutcomeMapperRegistry.fromTools(List.of(aware, plain));

		assertThat(registry.hasMapper("payment")).isTrue();
		assertThat(registry.hasMapper("weather")).isFalse();
	}

	@Test
	void mergeGivesPrecedenceToTheOtherRegistry() {
		BusinessOutcomeMapper first = (call, result) -> result;
		BusinessOutcomeMapper second = (call, result) -> ToolResult.error("declined");
		BusinessOutcomeMapperRegistry merged = BusinessOutcomeMapperRegistry.builder()
			.declare("payment", first)
			.build()
			.merge(BusinessOutcomeMapperRegistry.builder().declare("payment", second).build());

		assertThat(merged.mapOutcome(CALL, text("ok")).error()).isTrue();
	}

	@Test
	void mapperReturningNullIsRejected() {
		BusinessOutcomeMapperRegistry registry = BusinessOutcomeMapperRegistry.builder()
			.declare("payment", (call, result) -> null)
			.build();

		assertThatThrownBy(() -> registry.mapOutcome(CALL, text("ok")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("payment");
	}

	@Test
	void builderRejectsBlankToolNameAndNullMapper() {
		assertThatThrownBy(() -> BusinessOutcomeMapperRegistry.builder().declare(" ", (c, r) -> r))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BusinessOutcomeMapperRegistry.builder().declare("payment", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private record PlainTool(String name) implements Tool {

		@Override
		public ToolDefinition definition() {
			return new ToolDefinition(name, name, "{}");
		}

		@Override
		public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
			return CompletableFuture.completedFuture(ToolResult.text("sunny"));
		}

	}

	private static final class OutcomeAwareTool implements Tool, BusinessOutcomeAware {

		private final String name;

		private final BusinessOutcomeMapper mapper;

		OutcomeAwareTool(String name, BusinessOutcomeMapper mapper) {
			this.name = name;
			this.mapper = mapper;
		}

		@Override
		public ToolDefinition definition() {
			return new ToolDefinition(name, name, "{}");
		}

		@Override
		public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
			return CompletableFuture.completedFuture(ToolResult.text("done"));
		}

		@Override
		public BusinessOutcomeMapper outcomeMapper() {
			return this.mapper;
		}

	}

}
