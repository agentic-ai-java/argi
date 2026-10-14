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
package io.github.agentic.ai.adapter.googleadk;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.Claude;
import com.google.adk.models.Gemini;
import com.google.adk.models.ApigeeLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolChoice;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleAdkChatClientAdapterTests {

	private static final ChatRequest REQUEST = ChatRequest.of(List.of(ChatMessage.user("hello")));

	@Test
	void mapsInstructionsSchemasToolResultAndNamedChoiceWithoutExecutors() {
		StubModel model = new StubModel();
		ChatRequest request = new ChatRequest(
				List.of(ChatMessage.system("instruction"), ChatMessage.tool("id", "search", "found")),
				List.of(new ToolDefinition("search", "Search", "{\"type\":\"object\"}")), new GenerationOptions(0.2,
						0.8, 100, List.of("END"), ToolChoice.named("search"), ResponseFormat.jsonObject(), null),
				Map.of());
		assertEquals("hello", adapter(model).callBlocking(request).text());
		assertFalse(model.streaming);
		assertTrue(model.request.tools().isEmpty());
		assertEquals("instruction", model.request.getFirstSystemInstruction().orElseThrow());
		var result = model.request.contents().get(0).parts().orElseThrow().get(0).functionResponse().orElseThrow();
		assertEquals("id", result.id().orElseThrow());
		assertEquals("search", result.name().orElseThrow());
		var config = model.request.config().orElseThrow();
		assertEquals(100, config.maxOutputTokens().orElseThrow());
		assertEquals("application/json", config.responseMimeType().orElseThrow());
		var choice = config.toolConfig().orElseThrow().functionCallingConfig().orElseThrow();
		assertEquals(List.of("search"), choice.allowedFunctionNames().orElseThrow());
		assertEquals("ANY", choice.mode().orElseThrow().toString());
		assertEquals("search",
				config.tools().orElseThrow().get(0).functionDeclarations().orElseThrow().get(0).name().orElseThrow());
	}

	@Test
	void emitsFinalSnapshotOnlyOnceWithoutDuplicatingDeltas() {
		StubModel model = new StubModel();
		Part tool = Part.builder()
			.functionCall(FunctionCall.builder().id("id").name("search").args(Map.of("q", "test")).build())
			.build();
		model.chunks = Flowable.just(textResponse("hell", true), textResponse("o", true),
				LlmResponse.builder()
					.content(Content.builder().role("model").parts(Part.builder().text("hello").build(), tool).build())
					.build());
		var publisher = adapter(model).stream(REQUEST);
		assertEquals(0, model.calls);
		var events = JdkFlowAdapter.flowPublisherToFlux(publisher).collectList().block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertTrue(model.streaming);
		assertEquals("hello",
				events.stream()
					.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
					.map(ChatEvent::text)
					.reduce("", String::concat));
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.TOOL_CALL).count());
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.RESPONSE).count());
		assertEquals("hello", events.get(events.size() - 1).response().text());
		JdkFlowAdapter.flowPublisherToFlux(publisher).blockLast(Duration.ofSeconds(5));
		assertEquals(2, model.calls);
	}

	@Test
	void mergesTextCommitFinishToolsAndTrailingUsage() {
		StubModel model = new StubModel();
		Part tool = Part.builder()
			.functionCall(FunctionCall.builder().id("id").name("search").args(Map.of()).build())
			.build();
		model.chunks = Flowable.just(textResponse("hell", true), textResponse("o", true), textResponse("hello", false),
				LlmResponse.builder()
					.finishReason(new com.google.genai.types.FinishReason("STOP"))
					.content(Content.builder().role("model").parts(tool).build())
					.build(),
				LlmResponse.builder()
					.partial(true)
					.modelVersion("version")
					.usageMetadata(GenerateContentResponseUsageMetadata.builder()
						.promptTokenCount(10)
						.candidatesTokenCount(5)
						.totalTokenCount(15)
						.build())
					.build());

		var events = JdkFlowAdapter.flowPublisherToFlux(adapter(model).stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertNotNull(events);
		assertEquals("hello",
				events.stream()
					.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
					.map(ChatEvent::text)
					.reduce("", String::concat));
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.TOOL_CALL).count());
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.RESPONSE).count());
		var response = events.get(events.size() - 1).response();
		assertEquals("hello", response.text());
		assertEquals("version", response.model());
		assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
		assertEquals(new Usage(10L, 5L, 15L), response.usage());
	}

	@Test
	void handlesMetadataBeforeContentAndFinishWithoutTextCommit() {
		StubModel model = new StubModel();
		model.chunks = Flowable.just(LlmResponse.builder().modelVersion("version").build(), textResponse("hello", true),
				LlmResponse.builder().turnComplete(true).build());
		var events = JdkFlowAdapter.flowPublisherToFlux(adapter(model).stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals("hello", events.get(events.size() - 1).response().text());
		assertEquals("version", events.get(events.size() - 1).response().model());
	}

	@Test
	void defersPartialToolsAndPreservesGeminiAggregateThoughts() {
		StubModel model = new StubModel();
		Part tool = Part.builder()
			.functionCall(FunctionCall.builder().id("id").name("search").args(Map.of()).build())
			.build();
		Part thought = Part.builder().text("reasoning").thought(true).build();
		model.chunks = Flowable.just(
				LlmResponse.builder()
					.partial(true)
					.content(Content.builder().role("model").parts(thought, tool).build())
					.build(),
				textResponse("hello", true),
				LlmResponse.builder()
					.turnComplete(true)
					.content(Content.builder().role("model").parts(thought, Part.fromText("hello"), tool).build())
					.build());
		var events = JdkFlowAdapter.flowPublisherToFlux(adapter(model).stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.TOOL_CALL).count());
		assertEquals("hello",
				events.stream()
					.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
					.map(ChatEvent::text)
					.reduce("", String::concat));
		assertEquals("reasoning",
				events.get(events.size() - 1).response().messages().get(0).metadata().get(ChatMetadataKeys.REASONING));
	}

	@Test
	void requiresContentCommitOrFinishForMetadataOnlyStreams() {
		StubModel model = new StubModel();
		var adapter = adapter(model);
		model.chunks = Flowable.just(LlmResponse.builder().modelVersion("version").build());
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
		model.chunks = Flowable.just(LlmResponse.builder().turnComplete(true).build());
		var events = JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals("", events.get(events.size() - 1).response().text());
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.RESPONSE).count());
	}

	@Test
	void propagatesNativeErrorResponsesAndRequiresFinalResponse() {
		StubModel model = new StubModel();
		var adapter = adapter(model);
		model.chunks = Flowable.just(LlmResponse.builder().errorMessage("provider failure").build());
		assertThrows(IllegalStateException.class, () -> adapter.callBlocking(REQUEST));
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
		model.chunks = Flowable.just(textResponse("partial", true));
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
		model.chunks = Flowable.empty();
		assertThrows(java.util.NoSuchElementException.class, () -> adapter.callBlocking(REQUEST));
	}

	@Test
	void cancellationReachesRxJavaSubscription() throws InterruptedException {
		StubModel model = new StubModel();
		CountDownLatch subscribed = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		model.chunks = Flowable.<LlmResponse>never()
			.doOnSubscribe(ignored -> subscribed.countDown())
			.doOnCancel(cancelled::countDown);
		var subscription = JdkFlowAdapter.flowPublisherToFlux(adapter(model).stream(REQUEST)).subscribe();
		try {
			assertTrue(subscribed.await(5, TimeUnit.SECONDS));
		}
		finally {
			subscription.dispose();
		}
		assertTrue(cancelled.await(5, TimeUnit.SECONDS));
	}

	@Test
	void unknownImplementationsOptInToAdvancedCapabilities() {
		StubModel model = new StubModel();
		var conservative = new GoogleAdkChatClientAdapter(model);
		assertEquals(ChatClientCapabilities.basic(), conservative.capabilities());
		assertEquals("hello", conservative.callBlocking(REQUEST).text());
		assertThrows(UnsupportedOperationException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(conservative.stream(REQUEST))
					.blockLast(Duration.ofSeconds(5)));
		model.calls = 0;
		var toolRequest = new ChatRequest(REQUEST.messages(), List.of(new ToolDefinition("search", null, "{}")), null,
				null);
		assertThrows(UnsupportedOperationException.class, () -> conservative.callBlocking(toolRequest));
		assertThrows(UnsupportedOperationException.class, () -> conservative.callBlocking(
				ChatRequest.of(List.of(ChatMessage.assistant("", List.of(new ToolCall("id", "search", "{}")))))));
		assertThrows(UnsupportedOperationException.class,
				() -> conservative.callBlocking(ChatRequest.of(List.of(ChatMessage.tool("id", "search", "found")))));
		var structuredRequest = new ChatRequest(REQUEST.messages(), null,
				new GenerationOptions(null, null, null, null, null, ResponseFormat.jsonObject(), null), null);
		assertThrows(UnsupportedOperationException.class, () -> conservative.callBlocking(structuredRequest));
		assertEquals(0, model.calls);
		assertEquals("hello", adapter(model).callBlocking(toolRequest).text());
	}

	@Test
	void recognizesGeminiAndApigeeButDoesNotOverstateClaude() {
		var gemini = new GoogleAdkChatClientAdapter(new Gemini("gemini-test", "not-a-real-key"));
		var apigee = new GoogleAdkChatClientAdapter(ApigeeLlm.builder()
			.modelName("apigee/openai/test")
			.proxyUrl("http://localhost:1")
			.customHeaders(Map.of("Authorization", "Bearer not-a-real-key"))
			.build());
		assertEquals(advancedCapabilities(), gemini.capabilities());
		assertEquals(advancedCapabilities(), apigee.capabilities());
		var claude = new GoogleAdkChatClientAdapter(new Claude("claude-test", unusedAnthropicClient()));
		assertEquals(ChatClientCapabilities.basic(), claude.capabilities());
		assertThrows(UnsupportedOperationException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(claude.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
		assertThrows(IllegalArgumentException.class,
				() -> new GoogleAdkChatClientAdapter(new Claude("claude-test", unusedAnthropicClient()),
						advancedCapabilities()));
		for (GenerationOptions options : List.of(new GenerationOptions(0.2, null, null, null, null, null, null),
				new GenerationOptions(null, 0.8, null, null, null, null, null),
				new GenerationOptions(null, null, 100, null, null, null, null),
				new GenerationOptions(null, null, null, List.of("END"), null, null, null))) {
			var request = new ChatRequest(REQUEST.messages(), null, options, null);
			assertThrows(UnsupportedOperationException.class, () -> claude.callBlocking(request));
		}
	}

	@Test
	void rejectsImpossibleAdapterDeclarationsAndNulls() {
		StubModel model = new StubModel();
		assertThrows(NullPointerException.class, () -> new GoogleAdkChatClientAdapter(null));
		assertThrows(NullPointerException.class, () -> new GoogleAdkChatClientAdapter(model, null));
		assertThrows(IllegalArgumentException.class, () -> new GoogleAdkChatClientAdapter(model,
				new ChatClientCapabilities(true, true, true, true, true, true)));
		assertThrows(IllegalArgumentException.class, () -> new GoogleAdkChatClientAdapter(model,
				new ChatClientCapabilities(true, true, true, false, false, true)));
		assertThrows(NullPointerException.class, () -> adapter(model).call(null));
		assertThrows(NullPointerException.class, () -> adapter(model).stream(null));
	}

	@Test
	void blockingNativeInvocationDoesNotBlockCaller() throws InterruptedException {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Thread> invocationThread = new AtomicReference<>();
		BaseLlm blocking = new StubModel() {
			@Override
			public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
				invocationThread.set(Thread.currentThread());
				entered.countDown();
				try {
					if (!release.await(5, TimeUnit.SECONDS)) {
						return Flowable.error(new IllegalStateException("Test invocation was not released"));
					}
				}
				catch (InterruptedException error) {
					Thread.currentThread().interrupt();
					return Flowable.error(error);
				}
				return Flowable.just(textResponse("hello", false));
			}
		};
		var response = new GoogleAdkChatClientAdapter(blocking).call(REQUEST).toCompletableFuture();
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			assertFalse(response.isDone());
			assertFalse(Thread.currentThread() == invocationThread.get());
		}
		finally {
			release.countDown();
		}
		assertEquals("hello", response.join().text());
	}

	@Test
	void futureCancellationReachesNativeSubscription() throws InterruptedException {
		StubModel model = new StubModel();
		CountDownLatch subscribed = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		model.chunks = Flowable.<LlmResponse>never()
			.doOnSubscribe(ignored -> subscribed.countDown())
			.doOnCancel(cancelled::countDown);
		var future = adapter(model).call(REQUEST).toCompletableFuture();
		try {
			assertTrue(subscribed.await(5, TimeUnit.SECONDS));
		}
		finally {
			future.cancel(true);
		}
		assertTrue(cancelled.await(5, TimeUnit.SECONDS));
	}

	@Test
	void rejectsMultipleNonStreamingResponsesAndUnknownAttributes() {
		StubModel model = new StubModel();
		model.chunks = Flowable.just(textResponse("a", false), textResponse("b", false));
		assertThrows(IndexOutOfBoundsException.class, () -> adapter(model).callBlocking(REQUEST));
		model.calls = 0;
		ChatRequest request = new ChatRequest(REQUEST.messages(), null, null, Map.of("unknown", true));
		assertThrows(UnsupportedOperationException.class, () -> adapter(model).callBlocking(request));
		assertEquals(0, model.calls);
	}

	@Test
	void rejectsDuplicateFinalsLateDeltasAndInconsistentSnapshots() {
		StubModel model = new StubModel();
		var adapter = adapter(model);
		for (Flowable<LlmResponse> chunks : List.of(
				Flowable.just(textResponse("hello", false), textResponse("hello", false)),
				Flowable.just(textResponse("hello", false), textResponse("late", true)),
				Flowable.just(textResponse("hello", false), LlmResponse.builder().turnComplete(true).build(),
						LlmResponse.builder().turnComplete(true).build()),
				Flowable.just(LlmResponse.builder().turnComplete(true).build(), textResponse("late", true)),
				Flowable.just(textResponse("wrong", true), textResponse("hello", false)))) {
			model.chunks = chunks;
			List<ChatEvent> events = new java.util.ArrayList<>();
			assertThrows(IllegalStateException.class,
					() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
						.doOnNext(events::add)
						.blockLast(Duration.ofSeconds(5)));
			assertTrue(events.stream().noneMatch(event -> event.type() == ChatEvent.Type.RESPONSE));
		}
	}

	@Test
	void mapsAssistantToolsStructuredSchemaThinkingAndMissingIds() {
		StubModel model = new StubModel();
		model.chunks = Flowable.just(LlmResponse.builder()
			.modelVersion("version")
			.content(Content.builder()
				.role("model")
				.parts(Part.builder().text("reasoning").thought(true).build(),
						Part.builder()
							.functionCall(FunctionCall.builder().name("search").args(Map.of("q", "test")).build())
							.build())
				.build())
			.build());
		var request = new ChatRequest(
				List.of(ChatMessage.assistant("prior", List.of(new ToolCall("id", "search", "{}")))),
				List.of(new ToolDefinition("search", null, "{}")), new GenerationOptions(null, null, null, null,
						ToolChoice.required(), ResponseFormat.jsonSchema("answer", "{\"type\":\"object\"}"), null),
				null);
		var adapter = adapter(model);
		var response = adapter.callBlocking(request);
		assertTrue(adapter.capabilities().structuredOutput());
		assertEquals("reasoning", response.messages().get(0).metadata().get(ChatMetadataKeys.REASONING));
		assertEquals("version", response.model());
		assertTrue(!response.toolCalls().get(0).id().isBlank());
		assertTrue(model.request.config().orElseThrow().responseJsonSchema().isPresent());
		assertEquals("id",
				model.request.contents()
					.get(0)
					.parts()
					.orElseThrow()
					.get(1)
					.functionCall()
					.orElseThrow()
					.id()
					.orElseThrow());
	}

	@Test
	void rejectsBrokenSchemasAndIncompleteArguments() {
		StubModel model = new StubModel();
		var adapter = adapter(model);
		var request = new ChatRequest(REQUEST.messages(), List.of(new ToolDefinition("search", "Search", "[invalid]")),
				null, null);
		assertThrows(IllegalArgumentException.class, () -> adapter.callBlocking(request));
		assertEquals(0, model.calls);
		model.chunks = Flowable.just(LlmResponse.builder()
			.content(Content.builder()
				.role("model")
				.parts(Part.builder()
					.functionCall(FunctionCall.builder().name("search").willContinue(true).build())
					.build())
				.build())
			.build());
		assertThrows(IllegalStateException.class, () -> adapter.callBlocking(REQUEST));
	}

	private static LlmResponse textResponse(String text, boolean partial) {
		return LlmResponse.builder()
			.partial(partial)
			.content(Content.builder().role("model").parts(Part.builder().text(text).build()).build())
			.build();
	}

	private static GoogleAdkChatClientAdapter adapter(StubModel model) {
		return new GoogleAdkChatClientAdapter(model, advancedCapabilities());
	}

	private static ChatClientCapabilities advancedCapabilities() {
		return new ChatClientCapabilities(true, true, true, false, true, true);
	}

	private static com.anthropic.client.AnthropicClient unusedAnthropicClient() {
		return (com.anthropic.client.AnthropicClient) Proxy.newProxyInstance(
				GoogleAdkChatClientAdapterTests.class.getClassLoader(),
				new Class<?>[] { com.anthropic.client.AnthropicClient.class }, (proxy, method, args) -> {
					throw new AssertionError("Claude transport must not be invoked for unsupported requests");
				});
	}

	private static class StubModel extends BaseLlm {

		private Flowable<LlmResponse> chunks = Flowable.just(textResponse("hello", false));

		private LlmRequest request;

		private boolean streaming;

		private int calls;

		private StubModel() {
			super("test-model");
		}

		@Override
		public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
			calls++;
			this.request = request;
			this.streaming = stream;
			return chunks;
		}

		@Override
		public BaseLlmConnection connect(LlmRequest request) {
			throw new AssertionError("Live connections must not be used for chat turns");
		}

	}

}
