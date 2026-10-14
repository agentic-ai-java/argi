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
package io.github.agentic.ai.adapter.langchain4j;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolChoice;
import io.github.agentic.ai.api.chat.ToolDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jChatClientAdapterTests {

	private static final ChatRequest REQUEST = ChatRequest.of(List.of(ChatMessage.user("hello")));

	@Test
	void preservesToolResultIdentitySchemasOptionsAndAttributes() {
		StubModel model = new StubModel();
		var request = new ChatRequest(List.of(ChatMessage.tool("call-1", "search", "found")),
				List.of(new ToolDefinition("search", "Search", "{\"type\":\"object\"}")), new GenerationOptions(0.2,
						0.8, 100, List.of("END"), ToolChoice.required(), ResponseFormat.jsonObject(), Map.of()),
				Map.of("trace", "abc"));
		var response = new LangChain4jChatClientAdapter(model).callBlocking(request);
		var message = (ToolExecutionResultMessage) model.request.messages().get(0);
		assertEquals("call-1", message.id());
		assertEquals("search", message.toolName());
		assertEquals("found", message.text());
		assertEquals("search", model.request.toolSpecifications().get(0).name());
		assertEquals(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED, model.request.toolChoice());
		assertEquals(0.2, model.request.temperature());
		assertEquals(100, model.request.maxOutputTokens());
		assertEquals("abc", model.options.listenerAttributes().get("trace"));
		assertEquals("hello", response.text());
		assertEquals(15L, response.usage().totalTokens());
	}

	@Test
	void fallsBackOnlyForUnsupportedAsyncModelsAndOffloadsBlockingCalls() {
		StubModel model = new StubModel();
		model.asyncSupported = false;
		Thread caller = Thread.currentThread();
		assertEquals("hello", new LangChain4jChatClientAdapter(model).callBlocking(REQUEST).text());
		assertEquals(1, model.blockingCalls);
		assertFalse(caller == model.blockingThread);
		model.asyncSupported = true;
		model.future = CompletableFuture.failedFuture(new IllegalStateException("provider failure"));
		assertThrows(IllegalStateException.class, () -> new LangChain4jChatClientAdapter(model).callBlocking(REQUEST));
		assertEquals(1, model.blockingCalls);
	}

	@Test
	void callIsNonblockingAndCancellationReachesNativeFuture() {
		StubModel model = new StubModel();
		model.future = new CompletableFuture<>();
		var result = new LangChain4jChatClientAdapter(model).call(REQUEST).toCompletableFuture();
		assertFalse(result.isDone());
		result.cancel(true);
		assertTrue(model.future.isCancelled());
	}

	@Test
	void streamIsColdAndEmitsCompleteToolsExactlyOnceAtSuccessfulCompletion() {
		StubModel model = new StubModel();
		StubStream stream = new StubStream();
		var call = ToolExecutionRequest.builder().id("call-1").name("search").arguments("{}").build();
		var response = dev.langchain4j.model.chat.response.ChatResponse.builder()
			.aiMessage(AiMessage.from("hello", List.of(call)))
			.build();
		stream.events = Flux.just(new PartialResponse("hell"), new CompleteToolCall(0, call),
				new CompleteResponse(response));
		var publisher = new LangChain4jChatClientAdapter(model, stream).stream(REQUEST);
		assertEquals(0, stream.calls);
		var events = JdkFlowAdapter.flowPublisherToFlux(publisher).collectList().block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals(List.of(ChatEvent.Type.TEXT_DELTA, ChatEvent.Type.TEXT_DELTA, ChatEvent.Type.TOOL_CALL,
				ChatEvent.Type.USAGE, ChatEvent.Type.RESPONSE), events.stream().map(ChatEvent::type).toList());
		assertEquals("hello", events.get(4).response().text());
		assertEquals("call-1", events.get(2).toolCall().id());
		JdkFlowAdapter.flowPublisherToFlux(publisher).blockLast(Duration.ofSeconds(5));
		assertEquals(2, stream.calls);
	}

	@Test
	void rejectsMissingDuplicateAndInconsistentFinalResponses() {
		StubModel model = new StubModel();
		StubStream stream = new StubStream();
		var adapter = new LangChain4jChatClientAdapter(model, stream);
		var complete = new CompleteResponse(model.response);
		for (Flux<ChatModelStreamingEvent> invalid : List.of(Flux.<ChatModelStreamingEvent>empty(),
				Flux.<ChatModelStreamingEvent>just(new PartialResponse("hell")),
				Flux.<ChatModelStreamingEvent>just(complete, complete),
				Flux.<ChatModelStreamingEvent>just(new PartialResponse("different"), complete))) {
			stream.events = invalid;
			assertThrows(IllegalStateException.class,
					() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
		}
	}

	@Test
	void streamCancellationAndProviderErrorsReachTheSubscriber() {
		StubStream stream = new StubStream();
		AtomicBoolean cancelled = new AtomicBoolean();
		stream.events = Flux.<ChatModelStreamingEvent>never().doOnCancel(() -> cancelled.set(true));
		var adapter = new LangChain4jChatClientAdapter(new StubModel(), stream);
		var subscription = JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).subscribe();
		subscription.dispose();
		assertTrue(cancelled.get());
		stream.events = Flux.error(new IllegalStateException("provider failure"));
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
	}

	@Test
	void rejectsUnavailableStreamingAndUnsupportedInvocationOptions() {
		var adapter = new LangChain4jChatClientAdapter(new StubModel());
		assertFalse(adapter.capabilities().streaming());
		assertThrows(UnsupportedOperationException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
		var attributed = new ChatRequest(REQUEST.messages(), List.of(), null, Map.of("trace", "abc"));
		assertThrows(UnsupportedOperationException.class,
				() -> JdkFlowAdapter
					.flowPublisherToFlux(
							new LangChain4jChatClientAdapter(new StubModel(), new StubStream()).stream(attributed))
					.blockLast(Duration.ofSeconds(5)));
		var named = new ChatRequest(REQUEST.messages(), List.of(new ToolDefinition("search", "Search", "{}")),
				new GenerationOptions(null, null, null, null, ToolChoice.named("search"), null, null), Map.of());
		assertThrows(UnsupportedOperationException.class, () -> adapter.callBlocking(named));
	}

	@Test
	void mapsAllHistoryRolesSchemaFormatsThinkingAndToolIdentity() {
		StubModel model = new StubModel();
		model.future = CompletableFuture.completedFuture(dev.langchain4j.model.chat.response.ChatResponse.builder()
			.aiMessage(AiMessage.builder()
				.thinking("reasoning")
				.attributes(Map.of("trace", "abc"))
				.toolExecutionRequests(List.of(ToolExecutionRequest.builder().name("search").arguments("{}").build()))
				.build())
			.finishReason(dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION)
			.build());
		var request = new ChatRequest(
				List.of(ChatMessage.system("rules"), ChatMessage.user("hi"),
						ChatMessage.assistant(null,
								List.of(new io.github.agentic.ai.api.chat.ToolCall("id", "search", "{}")))),
				List.of(new ToolDefinition("search", "Search", "{}")), new GenerationOptions(null, null, null, null,
						ToolChoice.none(), ResponseFormat.jsonSchema("answer", "{\"type\":\"object\"}"), null),
				null);
		var response = new LangChain4jChatClientAdapter(model).callBlocking(request);
		assertEquals("reasoning", response.messages().get(0).metadata().get(ChatMetadataKeys.REASONING));
		assertEquals("abc", response.messages().get(0).metadata().get("trace"));
		assertTrue(!response.toolCalls().get(0).id().isBlank());
		assertEquals(io.github.agentic.ai.api.chat.FinishReason.TOOL_CALLS, response.finishReason());
		assertNotNull(model.request.responseFormat().jsonSchema());
		assertEquals(3, model.request.messages().size());
	}

	@Test
	void returnsTheSameMessageLevelReasoningForCallAndStream() {
		StubModel model = new StubModel();
		var nativeResponse = dev.langchain4j.model.chat.response.ChatResponse.builder()
			.aiMessage(
					AiMessage.builder().text("answer").thinking("reasoning").attributes(Map.of("trace", "abc")).build())
			.build();
		model.future = CompletableFuture.completedFuture(nativeResponse);
		StubStream stream = new StubStream();
		stream.events = Flux.just(new PartialResponse("ans"), new CompleteResponse(nativeResponse));
		var adapter = new LangChain4jChatClientAdapter(model, stream);
		var response = adapter.callBlocking(REQUEST);
		var events = JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals(response, events.get(events.size() - 1).response());
		assertEquals(Map.of(ChatMetadataKeys.REASONING, "reasoning", "trace", "abc"),
				response.messages().get(0).metadata());
		assertTrue(!response.metadata().containsKey("thinking"));
	}

	@Test
	void mapsFinishReasonsAndRejectsInvalidSchemas() {
		StubModel model = new StubModel();
		var adapter = new LangChain4jChatClientAdapter(model);
		var reasons = Map.of(dev.langchain4j.model.output.FinishReason.STOP,
				io.github.agentic.ai.api.chat.FinishReason.STOP, dev.langchain4j.model.output.FinishReason.LENGTH,
				io.github.agentic.ai.api.chat.FinishReason.LENGTH,
				dev.langchain4j.model.output.FinishReason.CONTENT_FILTER,
				io.github.agentic.ai.api.chat.FinishReason.CONTENT_FILTER);
		reasons.forEach((reason, expected) -> {
			model.future = CompletableFuture.completedFuture(dev.langchain4j.model.chat.response.ChatResponse.builder()
				.aiMessage(AiMessage.from("hello"))
				.finishReason(reason)
				.build());
			var request = new ChatRequest(REQUEST.messages(), null,
					new GenerationOptions(null, null, null, null, null, ResponseFormat.text(), null), null);
			assertEquals(expected, adapter.callBlocking(request).finishReason());
		});
		for (String schema : List.of("broken", "[]")) {
			var request = new ChatRequest(REQUEST.messages(), List.of(new ToolDefinition("search", "Search", schema)),
					null, null);
			assertThrows(IllegalArgumentException.class, () -> adapter.callBlocking(request));
		}
	}

	private static final class StubModel implements ChatModel {

		private final dev.langchain4j.model.chat.response.ChatResponse response = dev.langchain4j.model.chat.response.ChatResponse
			.builder()
			.aiMessage(AiMessage.from("hello"))
			.tokenUsage(new TokenUsage(10, 5, 15))
			.build();

		private CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse> future = CompletableFuture
			.completedFuture(response);

		private dev.langchain4j.model.chat.request.ChatRequest request;

		private ChatRequestOptions options;

		private boolean asyncSupported = true;

		private int blockingCalls;

		private Thread blockingThread;

		@Override
		public CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse> chatAsync(
				dev.langchain4j.model.chat.request.ChatRequest request, ChatRequestOptions options) {
			this.options = options;
			return ChatModel.super.chatAsync(request, options);
		}

		@Override
		public CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse> doChatAsync(
				dev.langchain4j.model.chat.request.ChatRequest request) {
			this.request = request;
			return asyncSupported ? future : ChatModel.super.doChatAsync(request);
		}

		@Override
		public dev.langchain4j.model.chat.response.ChatResponse doChat(
				dev.langchain4j.model.chat.request.ChatRequest request) {
			this.request = request;
			blockingCalls++;
			blockingThread = Thread.currentThread();
			return response;
		}

	}

	private static final class StubStream implements StreamingChatModel {

		private Flux<ChatModelStreamingEvent> events = Flux.empty();

		private int calls;

		@Override
		public Flow.Publisher<ChatModelStreamingEvent> doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
			calls++;
			return JdkFlowAdapter.publisherToFlowPublisher(events);
		}

	}

}
