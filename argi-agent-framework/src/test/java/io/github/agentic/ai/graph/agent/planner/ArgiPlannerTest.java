/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.planner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolChoice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.chat.ToolDefinition;

import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.OBJECTIVE;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.fixture;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.plan;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ArgiPlannerTest {

	private final ChatClient model = mock(ChatClient.class, CALLS_REAL_METHODS);

	private final PlanCodec codec = new PlanCodec();

	private final ArgiPlanner planner = new ArgiPlanner(this.model, PlannerFixtures.validator());

	@ParameterizedTest
	@ValueSource(strings = { "serial", "parallel" })
	void fixedModelProducesSchemaValidSerialAndParallelPlans(String name) throws Exception {
		String json = fixture(name);
		when(this.model.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(response(json)));
		PlanSpec planned = this.planner.plan(decompose());
		assertEquals(this.codec.decode(json), planned);
		PlannerFixtures.validator().validateGenerated(planned);
		ArgumentCaptor<ChatRequest> prompt = ArgumentCaptor.forClass(ChatRequest.class);
		verify(this.model).call(prompt.capture());
		String system = prompt.getValue().messages().get(0).text();
		assertTrue(system.contains("tool:search"));
		assertTrue(system.contains("agent:writer"));
		assertTrue(system.contains("Maximum steps: 64; maximum dependency depth: 16"));
		assertTrue(system.contains(this.codec.jsonSchema()));
		assertEquals(OBJECTIVE,
				PlanCodec.MAPPER.readTree(prompt.getValue().messages().get(1).text()).get("objective").asString());
		assertTrue(prompt.getValue().tools().isEmpty());
		assertEquals(ToolChoice.none(), prompt.getValue().options().toolChoice());
	}

	@Test
	void simpleKnownTaskBypassesModelAndNeverClaimsExecution() {
		PlanSpec first = this.planner
			.plan(new ArgiPlanner.Request("Search", 1, ArgiPlanner.Mode.DIRECT, "tool:search"));
		PlanSpec next = this.planner
			.plan(new ArgiPlanner.Request("Search again", 2, ArgiPlanner.Mode.DIRECT, "tool:search"));
		assertEquals("step-1", first.steps().get(0).stepId());
		assertEquals(first.steps().get(0).stepId(), next.steps().get(0).stepId());
		assertEquals(2, next.revision());
		assertEquals(PlanStep.Status.PENDING, first.steps().get(0).status());
		verify(this.model, never()).call(any(ChatRequest.class));
	}

	@Test
	void malformedRequestsAreRejectedBeforeModelCall() {
		for (ArgiPlanner.Request request : List.of(
				new ArgiPlanner.Request(" ", 1, ArgiPlanner.Mode.DIRECT, "tool:search"),
				new ArgiPlanner.Request("Task", 0, ArgiPlanner.Mode.DECOMPOSE, null),
				new ArgiPlanner.Request("Task", 1, null, null),
				new ArgiPlanner.Request("Task", 1, ArgiPlanner.Mode.DECOMPOSE, "tool:search"),
				new ArgiPlanner.Request("Task", 1, ArgiPlanner.Mode.DIRECT, null),
				new ArgiPlanner.Request("Task", 1, ArgiPlanner.Mode.DIRECT, "tool:unknown"))) {
			assertThrows(PlanValidationException.class, () -> this.planner.plan(request));
		}
		assertThrows(NullPointerException.class, () -> this.planner.plan(null));
		assertThrows(NullPointerException.class, () -> new ArgiPlanner(null, PlannerFixtures.validator()));
		assertThrows(NullPointerException.class, () -> new ArgiPlanner(this.model, null));
		verify(this.model, never()).call(any(ChatRequest.class));
	}

	@Test
	void invalidPlansFailBeforeAnyRegisteredToolIsInvoked() throws Exception {
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition("search", "Search documents", PlannerFixtures.INPUT));
		ArgiPlanner onlySearch = new ArgiPlanner(this.model,
				new PlanValidator(List.of(PlanCapability.tool(tool, PlannerFixtures.OUTPUT))));
		String valid = this.codec.encode(plan(step("a")));
		for (String json : List.of(this.codec.encode(plan(step("same"), step("same"))),
				this.codec.encode(plan(step("a", "missing"))), this.codec.encode(plan(step("a", "b"), step("b", "a"))),
				valid.replace("tool:search", "tool:invented"), valid.replace("PENDING", "COMPLETED"),
				valid.replace("\"result\":null", "\"result\":\"done\""),
				valid.replace("\"error\":null", "\"error\":\"failed\""),
				valid.replace("\"revision\":1", "\"revision\":2"), valid.replace(OBJECTIVE, "Another task"), "{}",
				fixture("serial"))) {
			when(this.model.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(response(json)));
			assertThrows(PlanValidationException.class, () -> onlySearch.plan(decompose()));
		}
		verify(tool).definition();
		verifyNoMoreInteractions(tool);
	}

	@Test
	void noRetryOrExecutionOccursOnModelFailure() {
		RuntimeException failure = new IllegalStateException("Provider unavailable");
		when(this.model.call(any(ChatRequest.class))).thenReturn(CompletableFuture.failedFuture(failure));
		assertEquals(failure, assertThrows(IllegalStateException.class, () -> this.planner.plan(decompose())));
		verify(this.model).call(any(ChatRequest.class));
	}

	@Test
	void emptyMultipleAndToolCallResponsesAreRejected() {
		for (ChatResponse response : List.of(response(List.of(), List.of()),
				response(List.of(ChatMessage.assistant("{}"), ChatMessage.assistant("{}")), List.of()),
				response(List.of(ChatMessage.user("{}")), List.of()),
				response(List.of(ChatMessage.assistant("{}", List.of(new ToolCall("call-1", "search", "{}")))),
						List.of()),
				response(List.of(ChatMessage.assistant("{}")), List.of(new ToolCall("call-1", "search", "{}"))),
				response(""))) {
			when(this.model.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(response));
			assertThrows(PlanValidationException.class, () -> this.planner.plan(decompose()));
		}
		when(this.model.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(null));
		assertThrows(PlanValidationException.class, () -> this.planner.plan(decompose()));
	}

	@Test
	void cancellationIsPropagatedWithoutRetry() {
		var failure = new java.util.concurrent.CancellationException("Cancelled");
		when(this.model.call(any(ChatRequest.class))).thenReturn(CompletableFuture.failedFuture(failure));
		assertEquals(failure,
				assertThrows(java.util.concurrent.CancellationException.class, () -> this.planner.plan(decompose())));
		verify(this.model).call(any(ChatRequest.class));
	}

	private static ArgiPlanner.Request decompose() {
		return new ArgiPlanner.Request(OBJECTIVE, 1, ArgiPlanner.Mode.DECOMPOSE, null);
	}

	private static ChatResponse response(String json) {
		return response(List.of(ChatMessage.assistant(json)), List.of());
	}

	private static ChatResponse response(List<ChatMessage> messages, List<ToolCall> calls) {
		return new ChatResponse(null, null, messages, calls, null, null, Map.of());
	}

}
