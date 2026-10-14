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
package io.github.agentic.ai.adapter.agentscope;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolChoice;
import io.github.agentic.ai.api.chat.ToolDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScopeChatClientAdapterTests {

	private static final ChatRequest REQUEST = ChatRequest.of(List.of(ChatMessage.user("hello")));

	@Test
	void aggregatesAllDeltasForCallAndAssemblesFragmentedToolArguments() {
		StubModel model = new StubModel();
		model.chunks = Flux.just(chunk(text("hell")), chunk(text("o")),
				chunk(ThinkingBlock.builder().thinking("reasoning").build()),
				chunk(ToolUseBlock.builder().id("call-1").name("search").content("{\"q\":").build()),
				chunk(ToolUseBlock.builder().id("call-1").name("__fragment__").content("\"test\"}").build()),
				ChatResponse.builder()
					.content(List.of())
					.finishReason("tool_calls")
					.usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
					.build());
		var response = new AgentScopeChatClientAdapter(model).callBlocking(REQUEST);
		assertEquals("hello", response.text());
		assertEquals("{\"q\":\"test\"}", response.toolCalls().get(0).arguments());
		assertEquals("call-1", response.toolCalls().get(0).id());
		assertEquals("reasoning", response.messages().get(0).metadata().get(ChatMetadataKeys.REASONING));
		assertEquals(15L, response.usage().totalTokens());
		assertEquals(1, model.calls);
	}

	@Test
	void preservesReasoningAndNativeUsageTotalsInCallAndStream() {
		StubModel model = new StubModel();
		// The SDK getter is authoritative, including implementations with extra tokens.
		var nativeUsage = new ChatUsage(10, 5, 0.0) {
			@Override
			public int getTotalTokens() {
				return 23;
			}
		};
		model.chunks = Flux.just(chunk(ThinkingBlock.builder().thinking("reasoning").build()),
				ChatResponse.builder().content(List.of(text("answer"))).usage(nativeUsage).build());
		var adapter = new AgentScopeChatClientAdapter(model);
		var response = adapter.callBlocking(REQUEST);
		var events = JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals(response, events.get(events.size() - 1).response());
		assertEquals(Map.of(ChatMetadataKeys.REASONING, "reasoning"), response.messages().get(0).metadata());
		assertTrue(!response.metadata().containsKey("thinking"));
		assertEquals(10L, response.usage().inputTokens());
		assertEquals(5L, response.usage().outputTokens());
		assertEquals(23L, response.usage().totalTokens());
	}

	@Test
	void mapsToolResultIdentitySchemasAndNamedToolChoice() {
		StubModel model = new StubModel();
		ChatRequest request = new ChatRequest(
				List.of(ChatMessage.system("instruction"), ChatMessage.tool("call-1", "search", "found")),
				List.of(new ToolDefinition("search", "Search", "{\"type\":\"object\"}")), new GenerationOptions(0.2,
						0.8, 100, null, ToolChoice.named("search"), ResponseFormat.jsonObject(), null),
				Map.of());
		new AgentScopeChatClientAdapter(model).callBlocking(request);
		var result = model.messages.get(1).getFirstContentBlock(ToolResultBlock.class);
		assertEquals("call-1", result.getId());
		assertEquals("search", result.getName());
		assertEquals("object", model.tools.get(0).getParameters().get("type"));
		assertEquals(new io.agentscope.core.model.ToolChoice.Specific("search"), model.options.getToolChoice());
		assertEquals("json_object", model.options.getResponseFormat().getType());
		assertEquals(0.2, model.options.getTemperature());
	}

	@Test
	void streamingIsColdAndDoesNotEmitToolFragments() {
		StubModel model = new StubModel();
		model.chunks = Flux.just(chunk(text("a")), chunk(text("b")),
				chunk(ToolUseBlock.builder().id("id").name("search").input(Map.of("q", "test")).build()));
		var publisher = new AgentScopeChatClientAdapter(model).stream(REQUEST);
		assertEquals(0, model.calls);
		var events = JdkFlowAdapter.flowPublisherToFlux(publisher).collectList().block(Duration.ofSeconds(5));
		assertNotNull(events);
		assertEquals(List.of(ChatEvent.Type.TEXT_DELTA, ChatEvent.Type.TEXT_DELTA, ChatEvent.Type.TOOL_CALL,
				ChatEvent.Type.USAGE, ChatEvent.Type.RESPONSE), events.stream().map(ChatEvent::type).toList());
		assertEquals("ab", events.get(4).response().text());
		JdkFlowAdapter.flowPublisherToFlux(publisher).blockLast(Duration.ofSeconds(5));
		assertEquals(2, model.calls);
	}

	@Test
	void propagatesCancellationErrorsAndEmptyStreams() {
		StubModel model = new StubModel();
		AtomicBoolean cancelled = new AtomicBoolean();
		model.chunks = Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
		var adapter = new AgentScopeChatClientAdapter(model);
		var subscription = JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).subscribe();
		subscription.dispose();
		assertTrue(cancelled.get());
		model.chunks = Flux.error(new IllegalStateException("provider failure"));
		assertThrows(IllegalStateException.class, () -> adapter.callBlocking(REQUEST));
		model.chunks = Flux.empty();
		assertThrows(IllegalStateException.class, () -> adapter.callBlocking(REQUEST));
		assertThrows(IllegalStateException.class,
				() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST)).blockLast(Duration.ofSeconds(5)));
	}

	@Test
	void rejectsUnsupportedOptionsBeforeInvokingTheModel() {
		StubModel model = new StubModel();
		ChatRequest request = new ChatRequest(REQUEST.messages(), List.of(),
				new GenerationOptions(null, null, null, List.of("END"), null, null, null), Map.of());
		assertThrows(UnsupportedOperationException.class,
				() -> new AgentScopeChatClientAdapter(model).callBlocking(request));
		assertEquals(0, model.calls);
	}

	@Test
	void rejectsMalformedAccumulatedArgumentsInsteadOfExecutingEmptyInput() {
		StubModel model = new StubModel();
		var adapter = new AgentScopeChatClientAdapter(model);
		for (String raw : List.of("{\"q\":", "[]", "null")) {
			model.chunks = Flux.just(chunk(ToolUseBlock.builder().id("id").name("search").content(raw).build()));
			assertThrows(IllegalArgumentException.class, () -> adapter.callBlocking(REQUEST));
			List<ChatEvent> events = new java.util.ArrayList<>();
			assertThrows(RuntimeException.class,
					() -> JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
						.doOnNext(events::add)
						.blockLast(Duration.ofSeconds(5)));
			assertTrue(events.stream()
				.noneMatch(
						event -> event.type() == ChatEvent.Type.TOOL_CALL || event.type() == ChatEvent.Type.RESPONSE));
		}
	}

	@Test
	void normalizesIdlessCallsAndRetainsFragmentsWithoutIds() {
		StubModel model = new StubModel();
		model.chunks = Flux.just(chunk(ToolUseBlock.builder().name("first").content("{\"q\":").build()),
				chunk(ToolUseBlock.builder().name("__fragment__").content("1}").build()),
				chunk(ToolUseBlock.builder().name("second").input(Map.of()).build()));
		var response = new AgentScopeChatClientAdapter(model).callBlocking(REQUEST);
		assertEquals(2, response.toolCalls().size());
		assertTrue(!response.toolCalls().get(0).id().equals(response.toolCalls().get(1).id()));
		assertEquals("{\"q\":1}", response.toolCalls().get(0).arguments());
		model.chunks = Flux.just(chunk(ToolUseBlock.builder().name("__fragment__").content("{}").build()));
		assertThrows(IllegalStateException.class, () -> new AgentScopeChatClientAdapter(model).callBlocking(REQUEST));
	}

	@Test
	void associatesLateNativeIdsWithTheOriginalArguments() {
		for (String lateName : List.of("search", "__fragment__")) {
			StubModel model = new StubModel();
			model.chunks = Flux.just(chunk(ToolUseBlock.builder().name("search").content("{\"q\":").build()),
					chunk(ToolUseBlock.builder().id("native-1").name(lateName).content("1").build()),
					chunk(ToolUseBlock.builder().name("__fragment__").content("}").build()));
			var adapter = new AgentScopeChatClientAdapter(model);
			var response = adapter.callBlocking(REQUEST);
			assertEquals(List.of(new ToolCall("native-1", "search", "{\"q\":1}")), response.toolCalls());
			var events = JdkFlowAdapter.flowPublisherToFlux(adapter.stream(REQUEST))
				.collectList()
				.block(Duration.ofSeconds(5));
			assertNotNull(events);
			assertEquals(List.of(ChatEvent.toolCall(response.toolCalls().get(0))),
					events.stream().filter(event -> event.type() == ChatEvent.Type.TOOL_CALL).toList());
			assertEquals(response, events.get(events.size() - 1).response());
		}
	}

	@Test
	void distinguishesSameNamedInvocationsAndRoutesInterleavedFragmentsById() {
		StubModel model = new StubModel();
		model.chunks = Flux.just(chunk(ToolUseBlock.builder().name("search").content("{\"q\":").build()),
				chunk(ToolUseBlock.builder().id("first").name("search").content("1").build()),
				chunk(ToolUseBlock.builder().id("second").name("search").content("{\"q\":2").build()),
				chunk(ToolUseBlock.builder().id("first").name("__fragment__").content("}").build()),
				chunk(ToolUseBlock.builder().id("second").name("__fragment__").content("}").build()));
		assertEquals(
				List.of(new ToolCall("first", "search", "{\"q\":1}"), new ToolCall("second", "search", "{\"q\":2}")),
				new AgentScopeChatClientAdapter(model).callBlocking(REQUEST).toolCalls());
	}

	@Test
	void mapsAssistantHistorySchemaFormatAndProviderMetadata() {
		StubModel model = new StubModel();
		model.chunks = Flux.just(ChatResponse.builder()
			.content(List.of(text("hello")))
			.metadata(Map.of("trace", "abc"))
			.finishReason("stop")
			.build());
		var request = new ChatRequest(List.of(ChatMessage.assistant(null, List.of(new ToolCall("id", "search", "{}")))),
				List.of(), new GenerationOptions(null, null, null, null, ToolChoice.none(),
						ResponseFormat.jsonSchema("answer", "{\"type\":\"object\"}"), null),
				null);
		var adapter = new AgentScopeChatClientAdapter(model);
		var response = adapter.callBlocking(request);
		assertTrue(adapter.capabilities().structuredOutput());
		assertEquals("abc", response.metadata().get("trace"));
		assertEquals(io.github.agentic.ai.api.chat.FinishReason.STOP, response.finishReason());
		assertEquals("id", model.messages.get(0).getFirstContentBlock(ToolUseBlock.class).getId());
		assertEquals("json_schema", model.options.getResponseFormat().getType());
		assertTrue(model.options.getToolChoice() instanceof io.agentscope.core.model.ToolChoice.None);
	}

	@Test
	void rejectsInvalidSchemasAttributesAndUnsupportedStructuredOutput() {
		StubModel model = new StubModel();
		var adapter = new AgentScopeChatClientAdapter(model);
		assertThrows(UnsupportedOperationException.class,
				() -> adapter.callBlocking(new ChatRequest(REQUEST.messages(), null, null, Map.of("trace", "abc"))));
		assertThrows(IllegalArgumentException.class, () -> adapter.callBlocking(new ChatRequest(REQUEST.messages(),
				List.of(new ToolDefinition("search", "Search", "broken")), null, null)));
		model.structuredOutput = false;
		assertThrows(UnsupportedOperationException.class, () -> adapter.callBlocking(new ChatRequest(REQUEST.messages(),
				null, new GenerationOptions(null, null, null, null, null, ResponseFormat.jsonObject(), null), null)));
		assertEquals(0, model.calls);
	}

	private static TextBlock text(String text) {
		return TextBlock.builder().text(text).build();
	}

	private static ChatResponse chunk(ContentBlock block) {
		return ChatResponse.builder().id("response-1").content(List.of(block)).build();
	}

	private static final class StubModel implements Model {

		private Flux<ChatResponse> chunks = Flux.just(chunk(text("hello")));

		private List<Msg> messages;

		private List<ToolSchema> tools;

		private GenerateOptions options;

		private int calls;

		private boolean structuredOutput = true;

		@Override
		public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
			calls++;
			this.messages = messages;
			this.tools = tools;
			this.options = options;
			return chunks;
		}

		@Override
		public String getModelName() {
			return "test-model";
		}

		@Override
		public boolean supportsNativeStructuredOutput() {
			return structuredOutput;
		}

	}

}
