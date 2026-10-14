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
package io.github.agentic.ai.api.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatContractTests {

	@Test
	void binaryContentHasStableValueEqualityAfterCopying() {
		byte[] bytes = { 1, 2 };
		ContentPart first = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", bytes);
		ContentPart copy = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", bytes);
		int originalHash = first.hashCode();
		bytes[0] = 9;
		first.data()[1] = 9;
		assertEquals(first, first);
		assertEquals(first, copy);
		assertEquals(copy, first);
		assertEquals(originalHash, copy.hashCode());
		assertEquals(originalHash, first.hashCode());
		assertArrayEquals(new byte[] { 1, 2 }, first.data());
		assertEquals(new ChatMessage(ChatRole.ASSISTANT, List.of(first), Map.of()),
				new ChatMessage(ChatRole.ASSISTANT, List.of(copy), Map.of()));
		assertEquals(1, java.util.Set.of(first).size());
		assertTrue(java.util.Set.of(first).contains(copy));
		assertNotEquals(first, null);
		assertNotEquals(first, "image");
		assertNotEquals(first, ContentPart.binary(ContentPart.Type.AUDIO, "image/png", copy.data()));
		assertNotEquals(first, ContentPart.binary(ContentPart.Type.IMAGE, "image/jpeg", copy.data()));
		assertNotEquals(first, ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1 }));
		assertNotEquals(first, new ContentPart(first.type(), "caption", first.mimeType(), first.data(), Map.of()));
		assertNotEquals(first,
				new ContentPart(first.type(), first.text(), first.mimeType(), first.data(), Map.of("id", 1)));
		assertEquals(ContentPart.text("hello"), ContentPart.text("hello"));
		assertEquals(ContentPart.text("hello").hashCode(), ContentPart.text("hello").hashCode());
		assertNotEquals(ContentPart.text("hello"), ContentPart.text("other"));
	}

	@Test
	void appendTextPreservesContentToolsIdentityAndMetadata() {
		ContentPart image = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1 });
		ToolCall call = new ToolCall("call-1", "search", "{}");
		ChatMessage assistant = new ChatMessage(ChatRole.ASSISTANT, List.of(image, ContentPart.text("before")),
				List.of(call), null, null, Map.of("trace", "a"));
		ChatMessage appended = assistant.appendText("after");
		assertEquals("beforeafter", appended.text());
		assertEquals(List.of(image, ContentPart.text("before"), ContentPart.text("after")), appended.content());
		assertEquals(List.of(call), appended.toolCalls());
		assertEquals(assistant.metadata(), appended.metadata());
		assertEquals(2, assistant.content().size());
		ChatMessage tool = new ChatMessage(ChatRole.TOOL, List.of(image), List.of(), "call-1", "search",
				Map.of("trace", "b"));
		ChatMessage appendedTool = tool.appendText("result");
		assertEquals("call-1", appendedTool.toolCallId());
		assertEquals("search", appendedTool.toolName());
		assertEquals(tool.metadata(), appendedTool.metadata());
		assertThrows(UnsupportedOperationException.class, () -> appended.content().clear());
		assertThrows(NullPointerException.class, () -> tool.appendText(null));
	}

	@Test
	void messageBuilderAndMutationPreserveMultimodalContentAndIdentity() {
		ContentPart image = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1 });
		ContentPart json = new ContentPart(ContentPart.Type.JSON, "{}", "application/json", null, Map.of());
		ToolCall call = new ToolCall("call-1", "search", "{}");
		List<ContentPart> parts = new ArrayList<>(List.of(ContentPart.text("before"), json, image));
		Map<String, Object> metadata = new HashMap<>(Map.of("trace", "first"));
		ChatMessage original = ChatMessage.builder(ChatRole.ASSISTANT)
			.content(parts)
			.toolCalls(List.of(call))
			.metadata(metadata)
			.build();
		parts.clear();
		metadata.clear();
		assertEquals("before{}", original.text());
		assertTrue(original.hasToolCalls());
		ChatMessage changed = original.mutate().text("after").metadata(Map.of("trace", "second")).build();
		assertEquals(List.of(ContentPart.text("after"), image), changed.content());
		assertEquals(original.toolCalls(), changed.toolCalls());
		assertEquals(Map.of("trace", "first"), original.metadata());
		assertEquals(Map.of("trace", "second"), changed.metadata());
		assertEquals(List.of(image), original.mutate().text(null).build().content());
		ChatMessage tool = ChatMessage.builder(ChatRole.TOOL).text("result").toolResult("call-1", "search").build();
		assertEquals(tool, tool.mutate().build());
		assertEquals("call-1", tool.mutate().text("updated").build().toolCallId());
		assertEquals("search", tool.mutate().text("updated").build().toolName());
		assertEquals(ChatMessage.user("hello"), ChatMessage.builder(ChatRole.USER).text("hello").build());
		assertThrows(UnsupportedOperationException.class, () -> changed.content().clear());
		assertThrows(UnsupportedOperationException.class, () -> changed.toolCalls().clear());
		assertThrows(UnsupportedOperationException.class, () -> changed.metadata().clear());
	}

	@Test
	void immutableMessageUpdatesReplaceOnlyTheirRequestedFields() {
		ContentPart image = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1 });
		ContentPart json = new ContentPart(ContentPart.Type.JSON, "{}", "application/json", null, Map.of());
		ToolCall call = new ToolCall("call-1", "search", "{}");
		ChatMessage original = new ChatMessage(ChatRole.ASSISTANT, List.of(ContentPart.text("before"), json, image),
				List.of(call), null, null, Map.of("trace", "first"));
		ChatMessage changed = original.withText("after");
		assertEquals(List.of(ContentPart.text("after"), image), changed.content());
		assertEquals(List.of(image), original.withText(null).content());
		assertEquals(original.toolCalls(), changed.toolCalls());
		assertEquals(original.metadata(), changed.metadata());
		Map<String, Object> metadata = new HashMap<>(Map.of("trace", "second"));
		ChatMessage updatedMetadata = original.withMetadata(metadata);
		metadata.clear();
		assertEquals(Map.of("trace", "second"), updatedMetadata.metadata());
		assertEquals(original.content(), updatedMetadata.content());
		assertEquals(original.toolCalls(), updatedMetadata.toolCalls());
		ChatMessage noTools = original.withToolCalls(null);
		assertFalse(noTools.hasToolCalls());
		assertEquals(original.content(), noTools.content());
		assertEquals(original.metadata(), noTools.metadata());
		assertEquals("before{}", original.text());
		ChatMessage tool = ChatMessage.tool("call-1", "search", "result").withText("updated");
		assertEquals("call-1", tool.toolCallId());
		assertEquals("search", tool.toolName());
		assertEquals("updated", tool.text());
	}

	@Test
	void messageBuilderSnapshotsInputsAndNormalizesNullCollections() {
		List<ContentPart> parts = new ArrayList<>(List.of(ContentPart.text("hello")));
		List<ToolCall> calls = new ArrayList<>(List.of(new ToolCall("call-1", "search", "{}")));
		Map<String, Object> metadata = new HashMap<>(Map.of("trace", "first"));
		ChatMessage.Builder builder = ChatMessage.builder(ChatRole.ASSISTANT)
			.content(parts)
			.toolCalls(calls)
			.metadata(metadata);
		parts.clear();
		calls.clear();
		metadata.clear();
		ChatMessage original = builder.build();
		assertEquals("hello", original.text());
		assertEquals(1, original.toolCalls().size());
		assertEquals(Map.of("trace", "first"), original.metadata());
		ChatMessage empty = builder.content(null).text(null).toolCalls(null).metadata(null).build();
		assertEquals(List.of(), empty.content());
		assertEquals(List.of(), empty.toolCalls());
		assertEquals(Map.of(), empty.metadata());
		assertEquals("hello", original.text());
		assertEquals(ChatMessage.assistant("next"), builder.content(null).text("next").build());
	}

	@Test
	void builderAndImmutableUpdatesEnforceMessageRoleConstraints() {
		ToolCall call = new ToolCall("call-1", "search", "{}");
		assertThrows(NullPointerException.class, () -> ChatMessage.builder(null));
		assertThrows(IllegalArgumentException.class, () -> ChatMessage.builder(ChatRole.TOOL).text("result").build());
		assertThrows(IllegalArgumentException.class,
				() -> ChatMessage.builder(ChatRole.USER).toolResult("call-1", "search").build());
		assertThrows(IllegalArgumentException.class,
				() -> ChatMessage.builder(ChatRole.USER).toolCalls(List.of(call)).build());
		assertThrows(IllegalArgumentException.class, () -> ChatMessage.user("hello").withToolCalls(List.of(call)));
		assertEquals(List.of(), ChatMessage.builder(ChatRole.USER).content(null).build().content());
		assertEquals(Map.of(), ChatMessage.builder(ChatRole.USER).metadata(null).build().metadata());
	}

	@Test
	void requiresToolResultIdentityAndAssistantToolCalls() {
		assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool(null, "search", "result"));
		assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool("id", " ", "result"));
		assertThrows(IllegalArgumentException.class,
				() -> new ChatMessage(ChatRole.USER, List.of(), List.of(), "id", "search", Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new ChatMessage(ChatRole.USER, List.of(),
				List.of(new ToolCall("id", "search", "{}")), null, null, Map.of()));
		ChatMessage result = ChatMessage.tool("id", "search", "result");
		assertEquals("id", result.toolCallId());
		assertEquals("search", result.toolName());
		assertEquals("result", result.text());
	}

	@Test
	void validatesToolSelectionAgainstRequestDefinitions() {
		ToolDefinition tool = new ToolDefinition("search", "Search", "{\"type\":\"object\"}");
		assertThrows(IllegalArgumentException.class, () -> ChatRequest.of(List.of()));
		assertThrows(IllegalArgumentException.class,
				() -> new ChatRequest(List.of(ChatMessage.user("hello")), List.of(tool, tool), null, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> request(List.of(), ToolChoice.required()));
		assertThrows(IllegalArgumentException.class, () -> request(List.of(tool), ToolChoice.named("missing")));
		assertEquals("search", request(List.of(tool), ToolChoice.named("search")).options().toolChoice().toolName());
		assertThrows(IllegalArgumentException.class, () -> ToolChoice.named(" "));
	}

	@Test
	void validatesGenerationNumbers() {
		for (double value : new double[] { Double.NaN, Double.POSITIVE_INFINITY, -1 }) {
			assertThrows(IllegalArgumentException.class, () -> options(value, null, null));
		}
		for (double value : new double[] { Double.NaN, -0.1, 1.1 }) {
			assertThrows(IllegalArgumentException.class, () -> options(null, value, null));
		}
		assertThrows(IllegalArgumentException.class, () -> options(null, null, 0));
		assertDoesNotThrow(() -> options(0.0, 1.0, 1));
	}

	@Test
	void defensivelyCopiesMessagesAndBinaryContent() {
		List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("hello")));
		ChatRequest request = ChatRequest.of(messages);
		messages.clear();
		assertEquals(1, request.messages().size());
		assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
		byte[] bytes = { 1, 2 };
		ContentPart part = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", bytes);
		bytes[0] = 9;
		part.data()[0] = 8;
		assertArrayEquals(new byte[] { 1, 2 }, part.data());
		assertThrows(IllegalArgumentException.class,
				() -> ContentPart.binary(ContentPart.Type.TEXT, "text/plain", bytes));
	}

	@Test
	void normalizesDefaultsAndAggregatesOnlyTextualContent() {
		ChatMessage message = new ChatMessage(ChatRole.ASSISTANT, List.of(ContentPart.text("a"), ContentPart.json("{}"),
				ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1 })), Map.of());
		assertEquals("a{}", message.text());
		ChatResponse response = new ChatResponse(null, null, List.of(message), null, null, null, null);
		assertEquals("a{}", response.text());
		assertEquals(Usage.unknown(), response.usage());
		assertEquals(ToolChoice.auto(), ChatRequest.of(List.of(ChatMessage.user("hello"))).options().toolChoice());
		assertThrows(IllegalArgumentException.class, () -> ResponseFormat.jsonSchema("test", " "));
		assertEquals(ChatEvent.Type.RESPONSE, ChatEvent.response(response).type());
	}

	@Test
	void validatesInvocationIdentityAndExposesCompleteEvents() {
		for (String identity : new String[] { null, "", " " }) {
			assertThrows(IllegalArgumentException.class, () -> new ToolCall(identity, "search", "{}"));
			assertThrows(IllegalArgumentException.class, () -> new ToolCall("id", identity, "{}"));
		}
		ToolCall call = new ToolCall("id", "search", null);
		assertEquals("{}", call.arguments());
		assertEquals(call, ChatEvent.toolCall(call).toolCall());
		assertEquals("delta", ChatEvent.textDelta("delta").text());
		assertEquals(Usage.unknown(), ChatEvent.usage(Usage.unknown()).usage());
		assertEquals(ResponseFormat.Type.TEXT, ResponseFormat.text().type());
		assertEquals(ResponseFormat.Type.JSON_OBJECT, ResponseFormat.jsonObject().type());
		assertEquals(ToolChoice.Mode.NONE, ToolChoice.none().mode());
		assertThrows(IllegalArgumentException.class, () -> new ToolChoice(ToolChoice.Mode.AUTO, "search"));
	}

	@Test
	void blockingConvenienceUsesTheAsyncClientAndRetainsUnknownUsage() {
		ChatResponse expected = new ChatResponse("id", "model", List.of(ChatMessage.assistant("ok")), List.of(),
				FinishReason.STOP, null, Map.of());
		ChatClient client = new ChatClient() {
			@Override
			public CompletableFuture<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(expected);
			}

			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				return subscriber -> subscriber.onError(new UnsupportedOperationException("Streaming is unavailable"));
			}

			@Override
			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
		assertEquals(expected,
				client.callBlocking(ChatRequest.of(List.of(ChatMessage.system("rules"), ChatMessage.user("hi")))));
		assertEquals(ChatClientCapabilities.basic(), client.capabilities());
		assertThrows(NullPointerException.class, () -> client.callBlocking(null));
		assertEquals(ChatRole.ASSISTANT,
				ChatMessage.assistant(null, List.of(new ToolCall("id", "search", "{}"))).role());
		assertEquals("", new ChatMessage(ChatRole.USER, null, null).text());
		assertEquals(null, ContentPart.text("hi").data());
	}

	private static ChatRequest request(List<ToolDefinition> tools, ToolChoice choice) {
		return new ChatRequest(List.of(ChatMessage.user("hello")), tools,
				new GenerationOptions(null, null, null, null, choice, null, null), Map.of());
	}

	@Test
	void generationBuilderAndMutationKeepOptionsIndependent() {
		List<String> stops = new ArrayList<>(List.of("stop"));
		Map<String, Object> metadata = new HashMap<>(Map.of("key", "value"));
		var builder = GenerationOptions.builder()
			.temperature(0.2)
			.topP(0.8)
			.maxTokens(100)
			.stopSequences(stops)
			.metadata(metadata)
			.toolChoice(ToolChoice.none())
			.responseFormat(ResponseFormat.jsonObject());
		stops.clear();
		metadata.clear();
		GenerationOptions original = builder.build();
		GenerationOptions changed = original.mutate().temperature(0.9).maxTokens(200).build();
		builder.stopSequences(null).metadata(null).toolChoice(null);
		assertEquals(0.2, original.temperature());
		assertEquals(0.9, changed.temperature());
		assertEquals(100, original.maxTokens());
		assertEquals(200, changed.maxTokens());
		assertEquals(List.of("stop"), changed.stopSequences());
		assertEquals(Map.of("key", "value"), changed.metadata());
		assertEquals(ResponseFormat.jsonObject(), changed.responseFormat());
		assertEquals(ToolChoice.none(), changed.toolChoice());
		assertEquals(ToolChoice.auto(), builder.build().toolChoice());
		assertThrows(UnsupportedOperationException.class, () -> original.stopSequences().clear());
		assertThrows(UnsupportedOperationException.class, () -> original.metadata().clear());
		assertThrows(IllegalArgumentException.class, () -> GenerationOptions.builder().maxTokens(0).build());
		assertThrows(IllegalArgumentException.class, () -> original.mutate().temperature(Double.NaN).build());
	}

	@Test
	void blockingConveniencePropagatesProviderFailuresAndCancellation() {
		RuntimeException runtime = new IllegalStateException("Unavailable");
		Error fatal = new AssertionError("Fatal");
		Exception checked = new Exception("Checked");
		ChatRequest request = ChatRequest.of(List.of(ChatMessage.user("hi")));
		assertSame(runtime, assertThrows(IllegalStateException.class,
				() -> pendingClient(CompletableFuture.failedFuture(runtime)).callBlocking(request)));
		assertSame(fatal, assertThrows(AssertionError.class,
				() -> pendingClient(CompletableFuture.failedFuture(fatal)).callBlocking(request)));
		assertSame(checked, assertThrows(CompletionException.class,
				() -> pendingClient(CompletableFuture.failedFuture(checked)).callBlocking(request))
			.getCause());
		CompletableFuture<ChatResponse> cancelled = new CompletableFuture<>();
		cancelled.cancel(true);
		assertThrows(CancellationException.class, () -> pendingClient(cancelled).callBlocking(request));
	}

	@Test
	void interruptingBlockingCallCancelsPendingTurnAndRestoresInterrupt() throws Exception {
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicReference<Boolean> interrupted = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				pendingClient(pending).callBlocking(ChatRequest.of(List.of(ChatMessage.user("hi"))));
			}
			catch (Throwable error) {
				failure.set(error);
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		});
		worker.start();
		try {
			worker.interrupt();
			worker.join(5000);
			assertFalse(worker.isAlive());
			assertTrue(pending.isCancelled());
			assertTrue(failure.get() instanceof CancellationException);
			assertEquals(Boolean.TRUE, interrupted.get());
		}
		finally {
			worker.interrupt();
			pending.cancel(true);
			worker.join(5000);
		}
	}

	private static ChatClient pendingClient(CompletableFuture<ChatResponse> pending) {
		return new ChatClient() {
			@Override
			public CompletableFuture<ChatResponse> call(ChatRequest request) {
				return pending;
			}

			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				throw new UnsupportedOperationException("Streaming is unavailable");
			}

			@Override
			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
	}

	private static GenerationOptions options(Double temperature, Double topP, Integer tokens) {
		return new GenerationOptions(temperature, topP, tokens, null, null, null, null);
	}

}
