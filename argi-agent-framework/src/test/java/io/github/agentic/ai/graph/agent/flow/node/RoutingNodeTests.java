/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.flow.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.Agent;
import io.github.agentic.ai.graph.agent.flow.agent.LlmRoutingAgent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingNodeTests {

	private static final String VALID = "{\"agents\":[{\"agent\":\"writer\",\"query\":\"Write a summary\"}]}";

	@Test
	void routesWithNeutralMessagesAndRetainsCallerHistory() throws Exception {
		ChatClient client = client(VALID);
		List<ChatMessage> history = List.of(ChatMessage.user("Original question"), ChatMessage.assistant("Progress"));
		var command = node(client).apply(new OverAllState(Map.of("messages", history)),
				RunnableConfig.builder().build());

		assertEquals(List.of("writer"), command.gotoNodes());
		assertEquals("Write a summary", command.update().get("writer_input"));
		assertEquals("Original question", command.update().get(RoutingNode.ORIGINAL_QUERY_KEY));
		assertEquals(List.of("writer"), command.update().get(RoutingNode.SELECTED_AGENTS_KEY));
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(request.capture());
		assertEquals(ChatRole.SYSTEM, request.getValue().messages().get(0).role());
		assertSame(history.get(0), request.getValue().messages().get(1));
		assertEquals(2, history.size());
		assertTrue(request.getValue().tools().isEmpty());
	}

	@Test
	void deduplicatesBranchesInFirstSeenOrder() throws Exception {
		ChatClient client = client("""
				{"agents":[{"agent":"writer","query":"first"},
				{"agent":"reviewer","query":"review"},{"agent":"writer","query":"duplicate"}]}
				""");
		var command = node(client).apply(new OverAllState(), RunnableConfig.builder().build());
		assertEquals(List.of("writer", "reviewer"), command.gotoNodes());
		assertEquals("first", command.update().get("writer_input"));
		assertEquals("review", command.update().get("reviewer_input"));
	}

	@Test
	void retriesUnknownAgentWithFeedbackWithoutMutatingPreviousRequest() throws Exception {
		ChatClient client = client("{\"agents\":[{\"agent\":\"unknown\",\"query\":\"q\"}]}");
		when(client.call(any())).thenReturn(response("{\"agents\":[{\"agent\":\"unknown\",\"query\":\"q\"}]}"),
				response(VALID));
		var command = node(client).apply(new OverAllState(), RunnableConfig.builder().build());
		assertEquals(List.of("writer"), command.gotoNodes());
		ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client, times(2)).call(requests.capture());
		List<ChatRequest> values = requests.getAllValues();
		assertEquals(values.get(0).messages().size() + 1, values.get(1).messages().size());
		assertTrue(values.get(1).messages().get(values.get(1).messages().size() - 1).text().contains("unknown"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "null", "{}", "[]", "{\"agents\":[]}", "{\"agents\":{}}", "{\"agents\":[null]}",
			"{\"agents\":[{\"agent\":7,\"query\":\"q\"}]}", "{\"agents\":[{\"agent\":\" \",\"query\":\"q\"}]}",
			"{\"agents\":[{\"agent\":\"writer\",\"query\":null}]}", "{\"agents\":[{\"agent\":\"writer\"}]}",
			"{\"agents\":[{\"agent\":\"writer\",\"query\":{}}]}",
			"{\"agents\":[{\"agent\":\"writer\",\"query\":\"q\"}]} {}", "not JSON" })
	void rejectsMalformedDecisionsAfterThreeAttempts(String json) {
		ChatClient client = client(json);
		assertThrows(Exception.class, () -> node(client).apply(new OverAllState(), RunnableConfig.builder().build()));
		verify(client, times(3)).call(any());
	}

	@Test
	void rejectsUnknownAgentsAfterThreeAttempts() {
		ChatClient client = client("{\"agents\":[{\"agent\":\"unknown\",\"query\":\"q\"}]}");
		assertThrows(IllegalStateException.class,
				() -> node(client).apply(new OverAllState(), RunnableConfig.builder().build()));
		verify(client, times(3)).call(any());
	}

	@Test
	void retriesTransientClientFailure() throws Exception {
		ChatClient client = client(VALID);
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")),
				response(VALID));
		assertEquals(List.of("writer"),
				node(client).apply(new OverAllState(), RunnableConfig.builder().build()).gotoNodes());
		verify(client, times(2)).call(any());
	}

	@Test
	void interruptedCallCancelsPendingFutureAndDoesNotRetry() {
		ChatClient client = mock(ChatClient.class);
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		when(client.call(any())).thenReturn(pending);
		Thread.currentThread().interrupt();
		try {
			assertThrows(InterruptedException.class,
					() -> node(client).apply(new OverAllState(), RunnableConfig.builder().build()));
			assertTrue(Thread.currentThread().isInterrupted());
			assertTrue(pending.isCancelled());
			verify(client).call(any());
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void honorsConfiguredSystemPromptAndInstruction() throws Exception {
		ChatClient client = client(VALID);
		LlmRoutingAgent root = mock(LlmRoutingAgent.class);
		when(root.name()).thenReturn("router");
		when(root.getSystemPrompt()).thenReturn("Choose the writer");
		when(root.getInstruction()).thenReturn("Review the current task");
		new RoutingNode(client, root, List.of(agent("writer"))).apply(new OverAllState(),
				RunnableConfig.builder().build());
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(request.capture());
		assertTrue(request.getValue().messages().get(0).text().contains("Choose the writer"));
		assertEquals("Review the current task", request.getValue().messages().get(1).text());
	}

	private static RoutingNode node(ChatClient client) {
		return new RoutingNode(client, agent("router"), List.of(agent("writer"), agent("reviewer")));
	}

	private static Agent agent(String name) {
		Agent agent = mock(Agent.class);
		when(agent.name()).thenReturn(name);
		when(agent.description()).thenReturn(name + " capabilities");
		return agent;
	}

	private static ChatClient client(String text) {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any())).thenReturn(response(text));
		return client;
	}

	private static CompletableFuture<ChatResponse> response(String text) {
		return CompletableFuture.completedFuture(
				new ChatResponse(null, null, List.of(ChatMessage.assistant(text)), List.of(), null, null, Map.of()));
	}

}
