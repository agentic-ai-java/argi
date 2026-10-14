/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuestionClassifierNodeTest {

	private final ChatClient client = mock(ChatClient.class);

	private QuestionClassifierNode.Builder builder() {
		return QuestionClassifierNode.builder()
			.chatClient(client)
			.inputTextKey("input")
			.categories(Map.of("positive", "Positive", "very_positive", "Very Positive"))
			.outputKey("output");
	}

	static OverAllState state(Map<String, Object> values) {
		OverAllState state = new OverAllState();
		state.updateState(values);
		return state;
	}

	static ChatResponse response(String json) {
		return new ChatResponse("result", "model", List.of(ChatMessage.assistant(json)), List.of(), FinishReason.STOP,
				new Usage(12L, 7L, 19L), Map.of());
	}

	@Test
	void matchesExactCategoryAndPreservesMessageOrderAndUsage() {
		ChatResponse response = response("{\"keywords\":[\"service\"],\"category_name\":\"Very Positive\"}");
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response));
		Map<String, Object> result = builder().build()
			.apply(state(Map.of("input", "Great service", "messages", List.of(ChatMessage.user("Earlier")))))
			.join();
		assertEquals("very_positive", result.get("output"));
		assertEquals(response.messages(), result.get("messages"));
		assertEquals(response.usage(), result.get(NodeOutput.TOKEN_USAGE_KEY));
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(request.capture());
		assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT,
				ChatRole.USER), request.getValue().messages().stream().map(ChatMessage::role).toList());
		assertEquals("Great service", request.getValue().messages().get(5).text());
		assertTrue(request.getValue().tools().isEmpty());
		assertTrue(request.getValue().messages().get(0).text().contains("Very Positive"));
		for (int index = 1; index < 5; index++) {
			assertTrue(StructuredChatSupport.object(request.getValue().messages().get(index).text()).isObject());
		}
	}

	@Test
	void resolvesTemplatesPerInvocationAndCopiesConfiguration() {
		Map<String, String> categories = new HashMap<>(Map.of("a", "{first} feedback", "b", "{second} feedback"));
		List<String> instructions = new ArrayList<>(List.of("{instruction}", "Missing: {absent}"));
		QuestionClassifierNode node = builder().categories(categories).classificationInstructions(instructions).build();
		categories.clear();
		instructions.clear();
		when(client.call(any()))
			.thenReturn(CompletableFuture.completedFuture(response("{\"category_name\":\"Good feedback\"}")));
		assertEquals("a", node
			.apply(state(Map.of("input", "Great", "first", "Good", "second", "Bad", "instruction", "Evaluate service")))
			.join()
			.get("output"));
		assertEquals("b", node
			.apply(state(Map.of("input", "Great", "first", "Bad", "second", "Good", "instruction", "Evaluate product")))
			.join()
			.get("output"));
		ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client, times(2)).call(requests.capture());
		assertTrue(requests.getAllValues().get(0).messages().get(0).text().contains("Evaluate service"));
		assertTrue(requests.getAllValues().get(1).messages().get(0).text().contains("Evaluate product"));
	}

	@Test
	void overlappingCallsDoNotShareInputOrRenderedCategories() {
		CompletableFuture<ChatResponse> first = new CompletableFuture<>();
		CompletableFuture<ChatResponse> second = new CompletableFuture<>();
		when(client.call(any())).thenReturn(first, second);
		QuestionClassifierNode node = builder().categories(Map.of("a", "{category}")).build();
		var firstResult = node.apply(state(Map.of("input", "First", "category", "One")));
		var secondResult = node.apply(state(Map.of("input", "Second", "category", "Two")));
		assertFalse(firstResult.isDone());
		assertFalse(secondResult.isDone());
		second.complete(response("{\"category_name\":\"Two\"}"));
		first.complete(response("{\"category_name\":\"One\"}"));
		assertEquals("a", firstResult.join().get("output"));
		assertEquals("a", secondResult.join().get("output"));
		assertFalse(firstResult.join().containsKey("messages"));
		assertThrows(CompletionException.class, () -> node.apply(state(Map.of("category", "Three"))).join());
		verify(client, times(2)).call(any());
	}

	@Test
	void rejectsInvalidInputAndAmbiguousRenderedCategoriesBeforeCallingModel() {
		QuestionClassifierNode node = builder().build();
		for (Map<String, Object> input : List.<Map<String, Object>>of(Map.of(), Map.of("input", " "),
				Map.of("input", 2))) {
			assertThrows(CompletionException.class, () -> node.apply(state(input)).join());
		}
		assertThrows(CompletionException.class,
				() -> builder().categories(Map.of("a", "{missing}"))
					.build()
					.apply(state(Map.of("input", "text")))
					.join());
		assertThrows(CompletionException.class,
				() -> builder().categories(Map.of("a", "Same", "b", "Same"))
					.build()
					.apply(state(Map.of("input", "text")))
					.join());
		verifyNoInteractions(client);
	}

	@Test
	void rejectsInvalidAndUnknownCategories() {
		for (String json : List.of("{}", "{\"category_name\":4}", "{\"category_name\":\"Unlisted\"}",
				"{\"category_name\":\"Very Positive but uncertain\"}", "invalid")) {
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response(json)));
			assertThrows(CompletionException.class,
					() -> builder().build().apply(state(Map.of("input", "text"))).join());
		}
	}

	@Test
	void acceptsEnclosingJsonFence() {
		when(client.call(any()))
			.thenReturn(CompletableFuture.completedFuture(response("```json\n{\"category_name\":\"Positive\"}\n```")));
		assertEquals("positive", builder().build().apply(state(Map.of("input", "text"))).join().get("output"));
	}

	@Test
	void propagatesProviderFailureAndCancelsPendingCall() {
		IllegalStateException failure = new IllegalStateException("Provider failed");
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(failure));
		var node = builder().build();
		assertSame(failure,
				assertThrows(CompletionException.class, () -> node.apply(state(Map.of("input", "text"))).join())
					.getCause());
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		when(client.call(any())).thenReturn(pending);
		var result = node.apply(state(Map.of("input", "text")));
		assertTrue(result.cancel(true));
		assertTrue(pending.isCancelled());
	}

	@Test
	void validatesConfigurationWithoutCompatibilityOverloads() {
		assertThrows(NullPointerException.class, () -> builder().chatClient(null).build());
		assertThrows(IllegalArgumentException.class, () -> builder().inputTextKey(" ").build());
		assertThrows(IllegalArgumentException.class, () -> builder().outputKey("").build());
		assertThrows(IllegalArgumentException.class, () -> builder().outputKey("messages").build());
		assertThrows(IllegalArgumentException.class, () -> builder().outputKey(NodeOutput.TOKEN_USAGE_KEY).build());
		assertThrows(IllegalArgumentException.class, () -> builder().categories(Map.of()).build());
		assertThrows(IllegalArgumentException.class, () -> builder().categories(Map.of("", "Name")).build());
		assertThrows(IllegalArgumentException.class, () -> builder().categories(Map.of("id", " ")).build());
	}

}
