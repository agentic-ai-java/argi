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

package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.agent.action.ActionDecision;
import io.github.agentic.ai.graph.agent.action.ActionReasonCodes;
import io.github.agentic.ai.graph.agent.action.ActionType;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionFailedException;
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionFailurePolicy;
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionInterceptor;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Model-independent contract tests for the opt-in tool selection failure policies.
 */
class ToolSelectionFailurePolicyContractTest {

	@Test
	void defaultPolicyKeepsEveryToolOnMalformedSelection() {
		ToolSelectionInterceptor interceptor = selection("not json", 1, null,
				ToolSelectionFailurePolicy.FAIL_OPEN_ALL_TOOLS);
		Captured captured = select(interceptor, List.of("a", "b", "c"));
		assertEquals(List.of("a", "b", "c"), captured.tools());
		assertNull(captured.decision());
	}

	@Test
	void defaultPolicyKeepsEveryToolOnSelectionModelFailure() {
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(failingClient())
			.maxTools(1)
			.build();
		Captured captured = select(interceptor, List.of("a", "b", "c"));
		assertEquals(List.of("a", "b", "c"), captured.tools());
		assertNull(captured.decision());
	}

	@Test
	void failClosedAlwaysIncludeKeepsOnlyMandatoryToolsOnMalformedSelection() {
		ToolSelectionInterceptor interceptor = selection("not json", 1, new String[] { "b" },
				ToolSelectionFailurePolicy.FAIL_CLOSED_ALWAYS_INCLUDE);
		Captured captured = select(interceptor, List.of("a", "b", "c"));
		assertEquals(List.of("b"), captured.tools());
		ActionDecision decision = (ActionDecision) captured.decision();
		assertEquals(ActionType.TOOL_CALL, decision.type());
		assertEquals(ActionReasonCodes.TOOL_SELECTION_MALFORMED, decision.reasonCode());
	}

	@Test
	void failClosedAlwaysIncludeRecordsFinalResponseWhenNoMandatoryToolsRemain() {
		ToolSelectionInterceptor interceptor = selection("not json", 1, null,
				ToolSelectionFailurePolicy.FAIL_CLOSED_ALWAYS_INCLUDE);
		Captured captured = select(interceptor, List.of("a", "b"));
		assertEquals(List.of(), captured.tools());
		ActionDecision decision = (ActionDecision) captured.decision();
		assertEquals(ActionType.FINAL_RESPONSE, decision.type());
		assertEquals(ActionReasonCodes.TOOL_SELECTION_MALFORMED, decision.reasonCode());
	}

	@Test
	void failClosedAlwaysIncludeAppliesToSelectionModelFailure() {
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(failingClient())
			.maxTools(1)
			.alwaysInclude("b")
			.failurePolicy(ToolSelectionFailurePolicy.FAIL_CLOSED_ALWAYS_INCLUDE)
			.build();
		Captured captured = select(interceptor, List.of("a", "b", "c"));
		assertEquals(List.of("b"), captured.tools());
		ActionDecision decision = (ActionDecision) captured.decision();
		assertEquals(ActionReasonCodes.TOOL_SELECTION_MODEL_FAILED, decision.reasonCode());
	}

	@Test
	void failClosedAlwaysIncludePreservesAvailabilityOrder() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":\"a\"}", 1, new String[] { "b", "c" },
				ToolSelectionFailurePolicy.FAIL_CLOSED_ALWAYS_INCLUDE);
		Captured captured = select(interceptor, List.of("c", "b", "a"));
		assertEquals(List.of("c", "b"), captured.tools());
	}

	@Test
	void failClosedThrowAbortsOnMalformedSelection() {
		ToolSelectionInterceptor interceptor = selection("not json", 1, null,
				ToolSelectionFailurePolicy.FAIL_CLOSED_THROW);
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		ToolSelectionFailedException failure = assertThrows(ToolSelectionFailedException.class,
				() -> interceptor.interceptModel(request(List.of("a", "b")), filtered -> {
					captured.set(filtered);
					return ModelResponse.of(ChatMessage.assistant("done"));
				}));
		assertEquals(ActionType.STOP, failure.getDecision().type());
		assertEquals(ActionReasonCodes.TOOL_SELECTION_MALFORMED, failure.getDecision().reasonCode());
		assertNotNull(failure.getCause());
		assertNull(captured.get());
	}

	@Test
	void failClosedThrowAbortsOnSelectionModelFailure() {
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(failingClient())
			.maxTools(1)
			.failurePolicy(ToolSelectionFailurePolicy.FAIL_CLOSED_THROW)
			.build();
		ToolSelectionFailedException failure = assertThrows(ToolSelectionFailedException.class,
				() -> interceptor.interceptModel(request(List.of("a", "b")), filtered -> ModelResponse
						.of(ChatMessage.assistant("done"))));
		assertEquals(ActionType.STOP, failure.getDecision().type());
		assertEquals(ActionReasonCodes.TOOL_SELECTION_MODEL_FAILED, failure.getDecision().reasonCode());
		assertNotNull(failure.getCause());
	}

	@Test
	void legalEmptySelectionIsNotAFailureUnderStrictThrowPolicy() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[]}", 1, null,
				ToolSelectionFailurePolicy.FAIL_CLOSED_THROW);
		Captured captured = select(interceptor, List.of("a", "b"));
		assertEquals(List.of(), captured.tools());
		ActionDecision decision = (ActionDecision) captured.decision();
		assertEquals(ActionType.FINAL_RESPONSE, decision.type());
		assertEquals(ActionReasonCodes.TOOL_SELECTION_EMPTY, decision.reasonCode());
	}

	@Test
	void successfulSelectionDoesNotAttachADecision() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"a\"]}", 2, null,
				ToolSelectionFailurePolicy.FAIL_CLOSED_THROW);
		Captured captured = select(interceptor, List.of("a", "b", "c"));
		assertEquals(List.of("a"), captured.tools());
		assertNull(captured.decision());
	}

	private static ToolSelectionInterceptor selection(String json, int max, String[] mandatory,
			ToolSelectionFailurePolicy policy) {
		return ToolSelectionInterceptor.builder()
			.chatClient(new SelectionModel(json))
			.maxTools(max)
			.alwaysInclude(mandatory != null ? mandatory : new String[] {})
			.failurePolicy(policy)
			.build();
	}

	private static ChatClient failingClient() {
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")));
		return client;
	}

	private static ModelRequest request(List<String> tools) {
		return ModelRequest.builder().messages(List.of(ChatMessage.user("Choose relevant tools"))).tools(tools).build();
	}

	private static Captured select(ToolSelectionInterceptor interceptor, List<String> tools) {
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request(tools), filtered -> {
			captured.set(filtered);
			return ModelResponse.of(ChatMessage.assistant("done"));
		});
		ModelRequest request = captured.get();
		return new Captured(request.getTools(), request.getContext().get(ActionDecision.CONTEXT_KEY));
	}

	private record Captured(List<String> tools, Object decision) {
	}

	private static final class SelectionModel implements ChatClient {

		private final String json;

		private SelectionModel(String json) {
			this.json = json;
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest prompt) {
			return CompletableFuture.completedFuture(new ChatResponse(null, null, List.of(ChatMessage.assistant(json)),
					List.of(), null, null, Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new AssertionError("Tool selection must use a single non-streaming turn");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
