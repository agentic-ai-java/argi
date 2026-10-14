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
package io.github.agentic.ai.adapter.springai;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolChoice;
import io.github.agentic.ai.api.chat.ToolDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiChatClientAdapterTests {

	private static final ChatRequest REQUEST = ChatRequest.of(List.of(ChatMessage.user("hello")));

	@Test
	void returnsToolCallsWithoutRunningTheNativeToolLoop() {
		StubModel model = new StubModel();
		model.response = toolResponse();
		var adapter = new SpringAiChatClientAdapter(model);
		var response = adapter.callBlocking(toolRequest(ToolChoice.auto()));
		assertEquals(1, model.calls.get());
		assertEquals("search", response.toolCalls().get(0).name());
		var options = (ToolCallingChatOptions) model.prompt.getOptions();
		assertEquals("search", options.getToolCallbacks().get(0).getToolDefinition().name());
		assertThrows(IllegalStateException.class, () -> options.getToolCallbacks().get(0).call("{}"));
	}

	@Test
	void preservesToolResultIdAndName() {
		StubModel model = new StubModel();
		var adapter = new SpringAiChatClientAdapter(model);
		adapter.callBlocking(ChatRequest.of(List.of(ChatMessage.tool("call-1", "search", "found"))));
		var message = (ToolResponseMessage) model.prompt.getInstructions().get(0);
		assertEquals("call-1", message.getResponses().get(0).id());
		assertEquals("search", message.getResponses().get(0).name());
		assertEquals("found", message.getResponses().get(0).responseData());
	}

	@Test
	void supportsToolSchemasWhenModelOptionsAreGeneric() {
		StubModel model = new StubModel() {
			@Override
			public ChatOptions getOptions() {
				return ChatOptions.builder().model("configured-model").temperature(0.7).build();
			}
		};
		new SpringAiChatClientAdapter(model).callBlocking(toolRequest(ToolChoice.auto()));
		var options = (ToolCallingChatOptions) model.prompt.getOptions();
		assertEquals("configured-model", options.getModel());
		assertEquals(0.2, options.getTemperature());
		assertEquals("search", options.getToolCallbacks().get(0).getToolDefinition().name());
	}

	@Test
	void retainsClientDefaultsAndOverlaysOnlySpecifiedRequestOptions() {
		StubModel model = new StubModel();
		ChatClient nativeClient = ChatClient.builder(model)
			.defaultOptions(
					ToolCallingChatOptions.builder().model("client-model").temperature(0.7).topP(0.9).maxTokens(200))
			.build();
		var adapter = new SpringAiChatClientAdapter(nativeClient);
		adapter.callBlocking(REQUEST);
		assertEquals("client-model", model.prompt.getOptions().getModel());
		assertEquals(0.7, model.prompt.getOptions().getTemperature());
		adapter.callBlocking(new ChatRequest(REQUEST.messages(), List.of(),
				new GenerationOptions(0.4, null, null, null, null, null, null), Map.of()));
		assertEquals("client-model", model.prompt.getOptions().getModel());
		assertEquals(0.4, model.prompt.getOptions().getTemperature());
		assertEquals(0.9, model.prompt.getOptions().getTopP());
		assertEquals(200, model.prompt.getOptions().getMaxTokens());
	}

	@Test
	void retainsReasoningMetadataOnEmptyTextDeltas() {
		StubModel model = new StubModel();
		model.chunks = Flux.just(new ChatResponse(List.of(new Generation(
				AssistantMessage.builder().content("").properties(Map.of("reasoningContent", "thinking")).build()))),
				textResponse("answer"));
		var events = JdkFlowAdapter.flowPublisherToFlux(new SpringAiChatClientAdapter(model).stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals(ChatEvent.Type.TEXT_DELTA, events.get(0).type());
		assertEquals("", events.get(0).text());
		assertEquals("thinking", events.get(0).metadata().get(ChatMetadataKeys.REASONING));
		assertEquals("answer", events.get(events.size() - 1).response().text());
		assertEquals("thinking",
				events.get(events.size() - 1).response().messages().get(0).metadata().get(ChatMetadataKeys.REASONING));
	}

	@Test
	void rejectsNullGenerationOutputsWithoutSuccessfulCompletion() {
		StubModel model = new StubModel();
		var emptyOutput = new ChatResponse(List.of(new Generation(null)),
				ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build());
		model.chunks = Flux.just(emptyOutput, textResponse("answer"), emptyOutput);
		var adapter = new SpringAiChatClientAdapter(model);
		List<ChatEvent> events = new ArrayList<>();
		var failure = assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
					.doOnNext(events::add)
					.blockLast(Duration.ofSeconds(5)));
		assertTrue(failure.getMessage().contains("Usage-only chunks must use an empty generation list"));
		assertTrue(failure.getCause() instanceof NullPointerException);
		assertTrue(events.stream()
			.noneMatch(event -> event.type() == ChatEvent.Type.RESPONSE || event.type() == ChatEvent.Type.USAGE));
		model.chunks = Flux.just(emptyOutput);
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
	}

	@Test
	void normalizesNativeReasoningOnCallWithoutChangingOtherMetadata() {
		StubModel model = new StubModel();
		model.response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("answer")
			.properties(Map.of("reasoningContent", "reasoning", "trace", "abc"))
			.build())));
		var response = new SpringAiChatClientAdapter(model).callBlocking(REQUEST);
		assertEquals("reasoning", response.messages().get(0).metadata().get(ChatMetadataKeys.REASONING));
		assertEquals("abc", response.messages().get(0).metadata().get("trace"));
		assertTrue(!response.messages().get(0).metadata().containsKey("reasoningContent"));
		assertTrue(response.metadata().isEmpty());
	}

	@Test
	void rejectsAnExplicitNativeToolAdvisorBeforeModelInvocation() {
		StubModel model = new StubModel();
		ChatClient nativeClient = ChatClient.builder(model)
			.defaultAdvisors(ToolCallingAdvisor.builder().build())
			.build();
		assertThrows(IllegalStateException.class,
				() -> new SpringAiChatClientAdapter(nativeClient).callBlocking(toolRequest(ToolChoice.auto())));
		assertEquals(0, model.calls.get());
	}

	@Test
	void streamIsColdAndEmitsOneAggregatedResponseWithCompleteTools() {
		StubModel model = new StubModel();
		model.chunks = Flux.just(textResponse("hell"), textResponse("o"), toolResponse());
		var publisher = new SpringAiChatClientAdapter(model).stream(toolRequest(ToolChoice.auto()));
		assertEquals(0, model.calls.get());
		var events = JdkFlowAdapter.flowPublisherToFlux(publisher).collectList().block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals("hello",
				events.stream()
					.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
					.map(ChatEvent::text)
					.reduce("", String::concat));
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.RESPONSE).count());
		assertEquals(1, events.stream().filter(event -> event.type() == ChatEvent.Type.TOOL_CALL).count());
		assertEquals("hello", events.get(events.size() - 1).response().text());
		JdkFlowAdapter.flowPublisherToFlux(publisher).blockLast(Duration.ofSeconds(5));
		assertEquals(2, model.calls.get());
	}

	@Test
	void propagatesCancellationAndErrorsWithoutSuccessEvents() {
		StubModel model = new StubModel();
		AtomicBoolean cancelled = new AtomicBoolean();
		model.chunks = Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
		var subscription = JdkFlowAdapter.flowPublisherToFlux(new SpringAiChatClientAdapter(model).stream(REQUEST))
			.subscribe();
		subscription.dispose();
		assertTrue(cancelled.get());
		model.chunks = Flux.error(new IllegalStateException("provider failure"));
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(new SpringAiChatClientAdapter(model).stream(REQUEST))
					.blockLast(Duration.ofSeconds(5)));
	}

	@Test
	void noneToolChoiceSuppressesRequestSchemas() {
		StubModel model = new StubModel();
		new SpringAiChatClientAdapter(model).callBlocking(toolRequest(ToolChoice.none()));
		assertTrue(((ToolCallingChatOptions) model.prompt.getOptions()).getToolCallbacks().isEmpty());
	}

	@Test
	void requestToolsReplaceNativeClientDefaultsIncludingNoneChoice() {
		StubModel model = new StubModel();
		var callback = new org.springframework.ai.tool.ToolCallback() {
			@Override
			public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
				return org.springframework.ai.tool.definition.ToolDefinition.builder()
					.name("native_tool")
					.description("Native tool")
					.inputSchema("{}")
					.build();
			}

			@Override
			public String call(String input) {
				throw new AssertionError("Native tools must not run");
			}
		};
		var nativeClient = ChatClient.builder(model).defaultToolCallbacks(callback).build();
		var adapter = new SpringAiChatClientAdapter(nativeClient);
		adapter.callBlocking(toolRequest(ToolChoice.auto()));
		var callbacks = ((ToolCallingChatOptions) model.prompt.getOptions()).getToolCallbacks();
		assertEquals(List.of("search"), callbacks.stream().map(tool -> tool.getToolDefinition().name()).toList());
		adapter.callBlocking(toolRequest(ToolChoice.none()));
		assertTrue(((ToolCallingChatOptions) model.prompt.getOptions()).getToolCallbacks().isEmpty());
	}

	@Test
	void rejectsEmptyStreamsAndMultipleGenerations() {
		StubModel model = new StubModel();
		model.chunks = Flux.empty();
		assertThrows(RuntimeException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(new SpringAiChatClientAdapter(model).stream(REQUEST))
					.blockLast(Duration.ofSeconds(5)));
		model.response = new ChatResponse(
				List.of(new Generation(new AssistantMessage("a")), new Generation(new AssistantMessage("b"))));
		assertThrows(UnsupportedOperationException.class,
				() -> new SpringAiChatClientAdapter(model).callBlocking(REQUEST));
	}

	@Test
	void aggregatesUsageOnlyChunksWithoutTreatingThemAsGenerations() {
		StubModel model = new StubModel();
		ChatResponse usage = new ChatResponse(List.of(),
				ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build());
		model.chunks = Flux.just(textResponse("hello"), usage);
		var adapter = new SpringAiChatClientAdapter(model);
		var events = JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals("hello", events.get(events.size() - 1).response().text());
		assertEquals(15L, events.get(events.size() - 1).response().usage().totalTokens());
		model.chunks = Flux.just(usage);
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
	}

	@Test
	void preservesRoleMetadataAndNormalizesMissingProviderToolIds() {
		StubModel model = new StubModel();
		var metadata = Map.<String, Object>of("trace", "abc");
		List<ChatMessage> messages = List.of(
				new ChatMessage(ChatRole.SYSTEM, List.of(ContentPart.text("rules")), metadata),
				new ChatMessage(ChatRole.USER, List.of(ContentPart.text("question")), metadata),
				new ChatMessage(ChatRole.ASSISTANT, List.of(ContentPart.text("answer")), metadata), new ChatMessage(
						ChatRole.TOOL, List.of(ContentPart.text("result")), List.of(), "id", "search", metadata));
		model.response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("")
			.properties(metadata)
			.toolCalls(List.of(new AssistantMessage.ToolCall(null, "function", "search", "{}")))
			.build())));
		var response = new SpringAiChatClientAdapter(model).callBlocking(ChatRequest.of(messages));
		model.prompt.getInstructions().forEach(message -> assertEquals("abc", message.getMetadata().get("trace")));
		assertEquals("abc", response.messages().get(0).metadata().get("trace"));
		assertTrue(!response.toolCalls().get(0).id().isBlank());
	}

	@Test
	void rejectsUnsupportedRequestsBeforeCallingTheProvider() {
		StubModel model = new StubModel();
		var adapter = new SpringAiChatClientAdapter(model);
		assertTrue(adapter.capabilities().streaming());
		for (GenerationOptions options : List.of(
				new GenerationOptions(null, null, null, null, ToolChoice.required(), null, null),
				new GenerationOptions(null, null, null, null, ToolChoice.named("search"), null, null),
				new GenerationOptions(null, null, null, null, null, ResponseFormat.jsonObject(), null),
				new GenerationOptions(null, null, null, null, null, null, Map.of("unknown", true)))) {
			var request = new ChatRequest(REQUEST.messages(), toolRequest(ToolChoice.auto()).tools(), options,
					Map.of());
			assertThrows(UnsupportedOperationException.class, () -> adapter.callBlocking(request));
		}
		var image = new ChatMessage(ChatRole.USER,
				List.of(ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1 })), null);
		assertThrows(UnsupportedOperationException.class, () -> adapter.callBlocking(ChatRequest.of(List.of(image))));
		assertEquals(0, model.calls.get());
	}

	@Test
	void mapsFinishReasonsAndAcceptsPlainAssistantHistory() {
		StubModel model = new StubModel();
		var adapter = new SpringAiChatClientAdapter(model);
		var reasons = Map.of("stop", FinishReason.STOP, "max_tokens", FinishReason.LENGTH, "tool_calls",
				FinishReason.TOOL_CALLS, "content_filter", FinishReason.CONTENT_FILTER, "other", FinishReason.OTHER);
		reasons.forEach((reason, expected) -> {
			model.response = new ChatResponse(List.of(new Generation(new AssistantMessage("hello"),
					ChatGenerationMetadata.builder().finishReason(reason).build())));
			assertEquals(expected,
					adapter.callBlocking(ChatRequest.of(List.of(ChatMessage.assistant("previous")))).finishReason());
		});
	}

	private static ChatRequest toolRequest(ToolChoice choice) {
		return new ChatRequest(REQUEST.messages(),
				List.of(new ToolDefinition("search", "Search", "{\"type\":\"object\"}")),
				new GenerationOptions(0.2, 0.8, 100, List.of("END"), choice, null, Map.of()), Map.of());
	}

	private static ChatResponse textResponse(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static ChatResponse toolResponse() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "search", "{\"q\":\"test\"}")))
			.build())));
	}

	private static class StubModel implements ChatModel {

		private final AtomicInteger calls = new AtomicInteger();

		private Prompt prompt;

		private ChatResponse response = textResponse("hello");

		private Flux<ChatResponse> chunks = Flux.just(response);

		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompt = prompt;
			calls.incrementAndGet();
			return response;
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			this.prompt = prompt;
			calls.incrementAndGet();
			return chunks;
		}

	}

}
