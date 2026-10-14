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
package io.github.agentic.ai.graph.agent.flow.node;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.agent.BaseAgent;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.github.agentic.ai.graph.agent.flow.node.RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

/**
 * Unit tests for {@link RoutingMergeNode}.
 *
 * <p>
 * Covers the gh-4616 fix: when {@code LlmRoutingAgent} hands off to a single sub-agent,
 * the merge node must return that agent's answer verbatim instead of running it through
 * the synthesis LLM again (which produced a redundant model call and a duplicated,
 * rephrased answer for the user). Genuine multi-agent results are still synthesized.
 * </p>
 */
class RoutingMergeNodeTest {

	/**
	 * When the router delegated to a single sub-agent, the merge node should pass that
	 * agent's answer through unchanged and must NOT call the synthesis LLM.
	 */
	@Test
	void singleRoutedResultIsPassedThroughWithoutSynthesis() throws Exception {
		ChatClient chatClient = mock(ChatClient.class, CALLS_REAL_METHODS);

		BaseAgent poemAgent = mockAgent("poem_writer_agent", "poem_article");
		BaseAgent proseAgent = mockAgent("prose_writer_agent", "prose_article");

		// Router picked only the poem agent, so only its output key is present in state.
		OverAllState state = new OverAllState(
				Map.of("poem_article", ChatMessage.assistant("A short modern poem about spring."), "messages",
						List.<ChatMessage>of(ChatMessage.user("Write a poem about spring"))));

		RoutingMergeNode node = new RoutingMergeNode(chatClient, List.of(poemAgent, proseAgent));
		Map<String, Object> result = node.apply(state);

		assertEquals("A short modern poem about spring.", result.get(DEFAULT_MERGED_OUTPUT_KEY),
				"Single routed result must be returned verbatim, not re-synthesized");
		verify(chatClient, never()).call(any(ChatRequest.class));
	}

	/**
	 * When multiple sub-agents produced results, the merge node should synthesize them
	 * through the LLM and return the synthesized answer.
	 */
	@Test
	void multipleResultsAreSynthesizedViaLlm() throws Exception {
		ChatClient chatClient = mock(ChatClient.class, CALLS_REAL_METHODS);
		when(chatClient.call(any(ChatRequest.class)))
			.thenReturn(CompletableFuture.completedFuture(new ChatResponse(null, null,
					List.of(ChatMessage.assistant("SYNTHESIZED ANSWER")), List.of(), null, null, Map.of())));

		BaseAgent poemAgent = mockAgent("poem_writer_agent", "poem_article");
		BaseAgent proseAgent = mockAgent("prose_writer_agent", "prose_article");

		OverAllState state = new OverAllState(Map.of("poem_article", ChatMessage.assistant("A poem."), "prose_article",
				ChatMessage.assistant("A prose piece."), "messages",
				List.<ChatMessage>of(ChatMessage.user("Write something about spring"))));

		RoutingMergeNode node = new RoutingMergeNode(chatClient, List.of(poemAgent, proseAgent));
		Map<String, Object> result = node.apply(state);

		assertEquals("SYNTHESIZED ANSWER", result.get(DEFAULT_MERGED_OUTPUT_KEY),
				"Multiple results must be synthesized via the LLM");
		verify(chatClient, times(1)).call(any(ChatRequest.class));
	}

	private static BaseAgent mockAgent(String name, String outputKey) {
		BaseAgent agent = mock(BaseAgent.class);
		when(agent.name()).thenReturn(name);
		when(agent.getOutputKey()).thenReturn(outputKey);
		return agent;
	}

	@Test
	void doesNotSynthesizeStaleResultsFromPreviousBranches() throws Exception {
		ChatClient client = mock(ChatClient.class);
		var state = new OverAllState(Map.of("fresh", ChatMessage.assistant("fresh answer"), "stale",
				ChatMessage.assistant("previous invocation"), RoutingNode.SELECTED_AGENTS_KEY, List.of("fresh_agent")));
		var node = new RoutingMergeNode(client,
				List.of(mockAgent("fresh_agent", "fresh"), mockAgent("old_agent", "stale")));
		assertEquals("fresh answer", node.apply(state).get(DEFAULT_MERGED_OUTPUT_KEY));
		verify(client, never()).call(any());
	}

	@Test
	void usesCapturedOriginalQuestionInsteadOfLaterSubAgentMessage() throws Exception {
		ChatClient client = mock(ChatClient.class, CALLS_REAL_METHODS);
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(new ChatResponse(null, null,
				List.of(ChatMessage.assistant("merged")), List.of(), null, null, Map.of())));
		var state = new OverAllState(Map.of("one", "first result", "two", "second result",
				RoutingNode.ORIGINAL_QUERY_KEY, "original question", "messages",
				List.of(ChatMessage.user("targeted sub-query"), ChatMessage.assistant("progress"))));
		var node = new RoutingMergeNode(client, List.of(mockAgent("one", "one"), mockAgent("two", "two")),
				"custom_output");
		assertEquals("merged", node.apply(state).get("custom_output"));
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(request.capture());
		assertTrue(request.getValue().messages().get(0).text().contains("original question"));
		assertTrue(request.getValue().messages().get(1).text().contains("first result"));
	}

	@Test
	void noUsableResultsDoesNotCallModel() throws Exception {
		ChatClient client = mock(ChatClient.class);
		var node = new RoutingMergeNode(client,
				List.of(mockAgent("blank", "blank"), mockAgent("no_output", null), mockAgent("missing", "missing")));
		assertEquals("No results found from any knowledge source.",
				node.apply(new OverAllState(Map.of("blank", " "))).get(DEFAULT_MERGED_OUTPUT_KEY));
		verify(client, never()).call(any());
	}

	@Test
	void extractsNeutralAndWrappedOutputsIncludingMissingKeys() {
		assertEquals("answer", RoutingMergeNode
			.extractText(GraphResponse.done(Map.of("key", ChatMessage.assistant("answer"))), "key"));
		assertEquals("native",
				RoutingMergeNode.extractText(GraphResponse.done(ChatMessage.assistant("native")), "key"));
		assertEquals("", RoutingMergeNode.extractText(GraphResponse.done(Map.of("other", "ignored")), "key"));
		assertEquals("", RoutingMergeNode.extractText(GraphResponse.done(), "key"));
		assertEquals("", RoutingMergeNode.extractText(null, "key"));
		var response = new ChatResponse(null, null, List.of(ChatMessage.assistant("response")), List.of(), null, null,
				Map.of());
		assertEquals("response", RoutingMergeNode.extractText(response, "key"));
	}

	@Test
	void synthesisFailuresAreNotReplacedBySuccessfulEmptyResults() {
		ChatClient client = mock(ChatClient.class, CALLS_REAL_METHODS);
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")));
		var node = new RoutingMergeNode(client, List.of(mockAgent("one", "one"), mockAgent("two", "two")));
		assertThrows(Exception.class, () -> node.apply(new OverAllState(Map.of("one", "a", "two", "b"))));
	}

	@Test
	void interruptionCancelsPendingSynthesisAndRestoresInterruptFlag() {
		ChatClient client = mock(ChatClient.class);
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		when(client.call(any())).thenReturn(pending);
		var node = new RoutingMergeNode(client, List.of(mockAgent("one", "one"), mockAgent("two", "two")));
		Thread.currentThread().interrupt();
		try {
			assertThrows(InterruptedException.class,
					() -> node.apply(new OverAllState(Map.of("one", "a", "two", "b"))));
			assertTrue(pending.isCancelled());
			assertTrue(Thread.currentThread().isInterrupted());
			verify(client, times(1)).call(any());
		}
		finally {
			Thread.interrupted();
		}
	}

}
