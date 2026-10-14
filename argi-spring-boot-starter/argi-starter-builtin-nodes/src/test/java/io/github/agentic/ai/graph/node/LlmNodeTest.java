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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.streaming.GraphFlux;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmNodeTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	static ChatResponse response(String text) {
		return new ChatResponse("id", "model", List.of(ChatMessage.assistant(text)), List.of(), null, null, Map.of());
	}

	private static ChatClient client() {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response("answer")));
		return client;
	}

	@Test
	void resolvesLocalTemplatesHistoryDeclarationsAndGenerationOptions() {
		ChatClient client = client();
		ToolDefinition tool = new ToolDefinition("lookup", "Lookup", "{}");
		GenerationOptions options = new GenerationOptions(0.1, null, 32, List.of(), null,
				ResponseFormat.jsonSchema("result", "{\"type\":\"object\"}"), Map.of());
		LlmNode node = LlmNode.builder()
			.chatClient(client)
			.systemPromptTemplate("You are {role}")
			.userPromptTemplate("Hello {name}")
			.params(Map.of("role", "an assistant", "name", "null"))
			.tools(List.of(tool))
			.options(options)
			.outputKey("answer")
			.build();
		Map<String, Object> result = node
			.apply(new OverAllState(Map.of("name", "Ada", "messages", List.of(ChatMessage.user("earlier")))))
			.join();
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(request.capture());
		assertEquals(List.of(ChatMessage.system("You are an assistant"), ChatMessage.user("earlier"),
				ChatMessage.user("Hello Ada")), request.getValue().messages());
		assertEquals(List.of(tool), request.getValue().tools());
		assertSame(options, request.getValue().options());
		assertEquals(response("answer"), result.get(LlmNode.LLM_RESPONSE_KEY));
		assertEquals(List.of(ChatMessage.assistant("answer")), result.get("messages"));
		assertSame(result.get("messages"), result.get("answer"));
	}

	@Test
	void resolvesStateKeysWithoutMutatingDefaults() {
		ChatClient client = client();
		LlmNode node = LlmNode.builder()
			.chatClient(client)
			.systemPromptTemplate("default system")
			.systemPromptTemplateKey("system")
			.userPromptTemplate("default {name}")
			.userPromptTemplateKey("prompt")
			.params(Map.of("name", "default"))
			.paramsKey("parameters")
			.messages(List.of(ChatMessage.user("default history")))
			.messagesKey("history")
			.build();
		node.apply(new OverAllState(Map.of("system", "system {name}", "prompt", "state {name}", "parameters",
				Map.of("name", "state"), "history", ChatMessage.user("state history"))))
			.join();
		node.apply(new OverAllState()).join();
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client, times(2)).call(request.capture());
		assertEquals(List.of(ChatMessage.system("system state"), ChatMessage.user("state history"),
				ChatMessage.user("state state")), request.getAllValues().get(0).messages());
		assertEquals(List.of(ChatMessage.system("default system"), ChatMessage.user("default history"),
				ChatMessage.user("default default")), request.getAllValues().get(1).messages());
	}

	@Test
	void supportsHistoryOnlyAndEmptyHistoryOverride() {
		ChatClient client = client();
		LlmNode node = LlmNode.builder().chatClient(client).messages(List.of(ChatMessage.user("default"))).build();
		node.apply(new OverAllState()).join();
		assertThrows(CompletionException.class,
				() -> node.apply(new OverAllState(Map.of("messages", List.of()))).join());
		verify(client, times(1)).call(any());
	}

	@Test
	void defensivelyCopiesConfigurationAndAcceptsRegistryDeclarations() {
		ChatClient client = client();
		List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("original")));
		LlmNode node = LlmNode.builder()
			.chatClient(client)
			.messages(messages)
			.toolRegistry(new ToolRegistry(List.of()))
			.build();
		messages.clear();
		node.apply(new OverAllState()).join();
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(request.capture());
		assertEquals(List.of(ChatMessage.user("original")), request.getValue().messages());
	}

	@Test
	void concurrentCallsKeepTheirOwnStateAndCancellationReachesModel() {
		ChatClient client = mock(ChatClient.class);
		CompletableFuture<ChatResponse> first = new CompletableFuture<>();
		CompletableFuture<ChatResponse> second = new CompletableFuture<>();
		when(client.call(any())).thenReturn(first, second);
		LlmNode node = LlmNode.builder().chatClient(client).userPromptTemplate("{name}").build();
		var left = node.apply(new OverAllState(Map.of("name", "left")));
		var right = node.apply(new OverAllState(Map.of("name", "right")));
		assertFalse(left.isDone());
		assertFalse(right.isDone());
		right.cancel(true);
		assertTrue(second.isCancelled());
		assertFalse(first.isCancelled());
		first.complete(response("left answer"));
		assertEquals(response("left answer"), left.join().get(LlmNode.LLM_RESPONSE_KEY));
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client, times(2)).call(request.capture());
		assertEquals("left", request.getAllValues().get(0).messages().get(0).text());
		assertEquals("right", request.getAllValues().get(1).messages().get(0).text());
	}

	@Test
	void propagatesSynchronousAsynchronousAndNullModelFailures() {
		ChatClient client = mock(ChatClient.class);
		LlmNode node = LlmNode.builder().chatClient(client).userPromptTemplate("hello").build();
		when(client.call(any())).thenThrow(new IllegalStateException("sync"))
			.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("async")))
			.thenReturn(CompletableFuture.completedFuture(null));
		assertEquals("sync",
				assertThrows(CompletionException.class, () -> node.apply(new OverAllState()).join()).getCause()
					.getMessage());
		assertEquals("async",
				assertThrows(CompletionException.class, () -> node.apply(new OverAllState()).join()).getCause()
					.getMessage());
		assertThrows(CompletionException.class, () -> node.apply(new OverAllState()).join());
	}

	@Test
	void rejectsInvalidResponsesAndInputTypes() {
		ChatClient client = client();
		LlmNode node = LlmNode.builder()
			.chatClient(client)
			.userPromptTemplate("hello")
			.systemPromptTemplateKey("system")
			.userPromptTemplateKey("prompt")
			.paramsKey("params")
			.build();
		for (Map<String, Object> invalid : List.of(Map.<String, Object>of("system", 42),
				Map.<String, Object>of("prompt", 42), Map.<String, Object>of("params", "bad"),
				Map.<String, Object>of("params", Map.of(1, "bad")), Map.<String, Object>of("messages", "bad"),
				Map.<String, Object>of("messages", List.of("bad")))) {
			assertThrows(CompletionException.class, () -> node.apply(new OverAllState(invalid)).join());
		}
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(new ChatResponse(null, null,
				List.of(ChatMessage.user("not an assistant")), List.of(), null, null, null)));
		assertThrows(CompletionException.class, () -> node.apply(new OverAllState()).join());
		when(client.call(any())).thenReturn(CompletableFuture
			.completedFuture(new ChatResponse(null, null, List.of(), List.of(), null, null, null)));
		assertThrows(CompletionException.class, () -> node.apply(new OverAllState()).join());
		assertThrows(CompletionException.class, () -> node.apply(null).join());
	}

	@Test
	void failsMissingAndInvalidTemplateVariablesBeforeCallingModel() {
		ChatClient client = client();
		LlmNode missing = LlmNode.builder().chatClient(client).userPromptTemplate("{missing}").build();
		assertThrows(CompletionException.class, () -> missing.apply(new OverAllState()).join());
		LlmNode invalid = LlmNode.builder().chatClient(client).userPromptTemplate("{invalid").build();
		assertThrows(CompletionException.class, () -> invalid.apply(new OverAllState()).join());
		verify(client, never()).call(any());
	}

	@Test
	void streamIsColdAndCommitsAnAggregatedResponse() {
		ChatClient client = mock(ChatClient.class);
		AtomicInteger subscriptions = new AtomicInteger();
		when(client.stream(any())).thenAnswer(ignored -> JdkFlowAdapter.publisherToFlowPublisher(Flux.defer(() -> {
			subscriptions.incrementAndGet();
			return Flux.just(ChatEvent.textDelta("an"), ChatEvent.textDelta("swer"),
					ChatEvent.response(response("answer")));
		})));
		LlmNode node = LlmNode.builder().chatClient(client).userPromptTemplate("hello").stream(true).build();
		GraphFlux<?> graphFlux = (GraphFlux<?>) node.apply(new OverAllState()).join().get(LlmNode.LLM_RESPONSE_KEY);
		assertEquals(0, subscriptions.get());
		var events = node.stream(new OverAllState()).collectList().block(TIMEOUT);
		assertEquals(3, events.size());
		assertEquals(response("answer"), events.get(2).response());
		graphFlux.getFlux().blockLast(TIMEOUT);
		assertEquals(2, subscriptions.get());
		verify(client, never()).call(any());
	}

	@Test
	void rejectsMissingDuplicateLateAndFailedStreamTerminals() {
		ChatClient client = mock(ChatClient.class);
		LlmNode node = LlmNode.builder().chatClient(client).userPromptTemplate("hello").build();
		for (Flux<ChatEvent> invalid : List.of(Flux.<ChatEvent>empty(), Flux.just(ChatEvent.textDelta("partial")),
				Flux.just(ChatEvent.response(response("answer")), ChatEvent.response(response("answer"))),
				Flux.just(ChatEvent.response(response("answer")), ChatEvent.textDelta("late")),
				Flux.just(ChatEvent.response(response("answer")))
					.concatWith(Flux.error(new IllegalStateException("late error"))),
				Flux.just(new ChatEvent(ChatEvent.Type.RESPONSE, null, null, null, null, Map.of())))) {
			when(client.stream(any())).thenReturn(JdkFlowAdapter.publisherToFlowPublisher(invalid));
			List<ChatEvent> observed = new ArrayList<>();
			assertThrows(RuntimeException.class,
					() -> node.stream(new OverAllState()).doOnNext(observed::add).blockLast(TIMEOUT));
			assertTrue(observed.stream().noneMatch(event -> event.type() == ChatEvent.Type.RESPONSE));
		}
	}

	@Test
	void streamCancellationReachesNativePublisher() {
		ChatClient client = mock(ChatClient.class);
		AtomicInteger cancelled = new AtomicInteger();
		when(client.stream(any())).thenReturn(JdkFlowAdapter
			.publisherToFlowPublisher(Flux.<ChatEvent>never().doOnCancel(cancelled::incrementAndGet)));
		LlmNode node = LlmNode.builder().chatClient(client).userPromptTemplate("hello").build();
		var subscription = node.stream(new OverAllState()).subscribe();
		subscription.dispose();
		assertEquals(1, cancelled.get());
	}

	@Test
	void validatesBuilderAndAllowsDisablingHistoryStateLookup() {
		assertThrows(NullPointerException.class, () -> LlmNode.builder().build());
		assertThrows(IllegalArgumentException.class,
				() -> LlmNode.builder().chatClient(client()).outputKey(LlmNode.LLM_RESPONSE_KEY).build());
		assertThrows(IllegalArgumentException.class,
				() -> LlmNode.builder().chatClient(client()).outputKey(NodeOutput.TOKEN_USAGE_KEY).build());
		LlmNode node = LlmNode.builder()
			.chatClient(client())
			.messagesKey(null)
			.messages(List.of(ChatMessage.user("fixed")))
			.outputKey(" ")
			.build();
		assertEquals(Set.of("messages", LlmNode.LLM_RESPONSE_KEY, NodeOutput.TOKEN_USAGE_KEY),
				node.apply(new OverAllState(Map.of("messages", "ignored"))).join().keySet());
	}

}
