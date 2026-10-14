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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.agent.ReactAgent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentChatProtocolTest {

	private static final ChatRequest REQUEST = ChatRequest.of(List.of(ChatMessage.user("test")));

	@Test
	void toolResultsKeepTheirIndividualInvocationIdsAndMetadata() {
		var messages = AgentChatProtocol.toolResponse(
				List.of(ChatMessage.tool("first", "lookup", "one"), ChatMessage.tool("second", "lookup", "two")),
				Map.of("source", "tool-node"));
		ChatRequest request = new ChatRequest(messages, List.of(), null, Map.of("thread", "a"));
		assertThat(request.messages()).extracting(ChatMessage::toolCallId).containsExactly("first", "second");
		assertThat(request.messages()).extracting(ChatMessage::toolName).containsOnly("lookup");
		assertThat(request.messages()).extracting(ChatMessage::text).containsExactly("one", "two");
		assertThat(request.messages()).allSatisfy(result -> {
			assertThat(result.role()).isEqualTo(ChatRole.TOOL);
			assertThat(result.metadata()).containsEntry("source", "tool-node");
		});
		assertThat(request.attributes()).containsEntry("thread", "a");
	}

	@Test
	void finalAggregateEmitsOnlyTheUnobservedSuffixAndRetainsUsageAndTools() {
		ToolCall tool = new ToolCall("call-1", "lookup", "{}");
		ChatResponse response = response("hello world", List.of(tool));
		ChatClient client = streaming(ChatEvent.textDelta("hell"), ChatEvent.textDelta("o"), ChatEvent.toolCall(tool),
				ChatEvent.usage(response.usage()), ChatEvent.response(response));
		var chunks = AgentChatProtocol.stream(client, REQUEST).collectList().block();
		assertThat(chunks).extracting(chunk -> chunk.messages().get(0).text())
			.containsExactly("hell", "o", "hello world");
		var last = chunks.get(chunks.size() - 1);
		assertThat(last.metadata()).containsEntry("argiTextDelta", " world");
		assertThat(last.toolCalls()).extracting(ToolCall::id).containsExactly("call-1");
		assertThat(last.usage().totalTokens()).isEqualTo(5);
		assertThat(last.id()).isEqualTo("response-id");
		assertThat(last.model()).isEqualTo("test-model");
	}

	@Test
	void finalAggregateWithoutDeltasEmitsTheCompleteText() {
		StepVerifier
			.create(AgentChatProtocol.stream(streaming(ChatEvent.response(response("answer", List.of()))), REQUEST))
			.assertNext(chunk -> assertThat(chunk.messages().get(0).text()).isEqualTo("answer"))
			.verifyComplete();
	}

	@Test
	void streamIsColdAcrossMultipleSubscriptions() {
		AtomicInteger turns = new AtomicInteger();
		ChatClient client = new TestClient() {
			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				turns.incrementAndGet();
				return JdkFlowAdapter
					.publisherToFlowPublisher(Flux.just(ChatEvent.response(response("answer", List.of()))));
			}
		};
		var output = AgentChatProtocol.stream(client, REQUEST);
		assertThat(turns).hasValue(0);
		output.blockLast();
		output.blockLast();
		assertThat(turns).hasValue(2);
	}

	@Test
	void cancellationReachesTheClientSubscription() {
		AtomicBoolean cancelled = new AtomicBoolean();
		ChatClient client = new TestClient() {
			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				return JdkFlowAdapter
					.publisherToFlowPublisher(Flux.<ChatEvent>never().doOnCancel(() -> cancelled.set(true)));
			}
		};
		StepVerifier.create(AgentChatProtocol.stream(client, REQUEST)).thenCancel().verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void successfulStreamMustContainAFinalResponse() {
		StepVerifier.create(AgentChatProtocol.stream(streaming(ChatEvent.textDelta("partial")), REQUEST))
			.expectNextCount(1)
			.expectErrorMessage("ChatClient stream ended without a final response")
			.verify();
	}

	@Test
	void finalResponseCannotDisagreeWithAlreadyEmittedText() {
		StepVerifier
			.create(AgentChatProtocol.stream(
					streaming(ChatEvent.textDelta("prefix"), ChatEvent.response(response("different", List.of()))),
					REQUEST))
			.expectNextCount(1)
			.expectErrorMessage("Final response text does not match streamed deltas")
			.verify();
	}

	@Test
	void eventsAfterTheFinalResponseAreRejected() {
		StepVerifier
			.create(AgentChatProtocol.stream(
					streaming(ChatEvent.response(response("answer", List.of())), ChatEvent.textDelta("extra")),
					REQUEST))
			.expectNextCount(1)
			.expectErrorMessage("ChatClient emitted events after its final response")
			.verify();
	}

	@Test
	void modelFailureRetainsTheOriginalRuntimeException() {
		RuntimeException failure = new IllegalStateException("provider unavailable");
		ChatClient client = new TestClient() {
			@Override
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.failedFuture(failure);
			}
		};
		assertThatThrownBy(() -> AgentChatProtocol.call(client, REQUEST)).isSameAs(failure);
	}

	@Test
	void interruptionCancelsPendingModelWorkAndPreservesTheInterruptFlag() throws Exception {
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		ChatClient client = new TestClient() {
			@Override
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return pending;
			}
		};
		Thread worker = new Thread(() -> {
			Thread.currentThread().interrupt();
			try {
				AgentChatProtocol.call(client, REQUEST);
			}
			catch (Throwable error) {
				failure.set(error);
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		});
		worker.start();
		worker.join(2000);
		assertThat(worker.isAlive()).isFalse();
		assertThat(failure.get()).isInstanceOf(CancellationException.class);
		assertThat(pending.isCancelled()).isTrue();
		assertThat(interrupted).isTrue();
	}

	@Test
	void reactAgentExecutesToolsBetweenTwoNeutralClientTurns() throws Exception {
		AtomicInteger turns = new AtomicInteger();
		AtomicReference<ChatRequest> second = new AtomicReference<>();
		LookupTools tools = new LookupTools();
		ChatClient client = new TestClient() {
			@Override
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				if (turns.incrementAndGet() == 1) {
					assertThat(request.tools()).extracting(definition -> definition.name()).containsExactly("lookup");
					return CompletableFuture
						.completedFuture(response("", List.of(new ToolCall("call-1", "lookup", "{}"))));
				}
				second.set(request);
				return CompletableFuture.completedFuture(response("completed", List.of()));
			}
		};
		var answer = ReactAgent.builder()
			.name("neutral-tool-agent")
			.chatClient(client)
			.toolRegistry(
					io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(ToolCallbacks.from(tools)))
			.build()
			.call("look up the answer");
		assertThat(answer.text()).isEqualTo("completed");
		assertThat(turns).hasValue(2);
		assertThat(tools.executions).hasValue(1);
		assertThat(second.get().messages()).filteredOn(message -> message.role() == ChatRole.TOOL)
			.singleElement()
			.satisfies(message -> {
				assertThat(message.toolCallId()).isEqualTo("call-1");
				assertThat(message.toolName()).isEqualTo("lookup");
				assertThat(message.text()).contains("tool-result");
			});
	}

	@Test
	void streamingAgentDoesNotDuplicateTheFinalAggregate() throws Exception {
		var client = streaming(ChatEvent.textDelta("hello"), ChatEvent.textDelta(" world"),
				ChatEvent.response(response("hello world", List.of())));
		var messages = ReactAgent.builder()
			.name("neutral-stream-agent")
			.chatClient(client)
			.build()
			.streamMessages("test")
			.collectList()
			.block();
		assertThat(messages).isNotEmpty();
		assertThat(messages).extracting(ChatMessage::text).containsExactly("hello", " world");
	}

	@Test
	void syncOnlyClientProducesAnAnswerThroughTheStreamingAgentApi() throws Exception {
		var client = new TestClient() {
			@Override
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(response("sync answer", List.of()));
			}
		};
		var messages = ReactAgent.builder()
			.name("sync-client")
			.chatClient(client)
			.build()
			.streamMessages(ChatMessage.user("question"))
			.collectList()
			.block();
		assertThat(messages).extracting(ChatMessage::text).containsExactly("sync answer");
	}

	@Test
	void responsePreservesStructuredContentMediaMetadataAndLargeUsage() {
		var message = new ChatMessage(ChatRole.ASSISTANT,
				List.of(ContentPart.json("{\"ok\":true}"),
						ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2 })),
				Map.of("source", "model"));
		var usage = new Usage(3_000_000_000L, 2L, 3_000_000_002L);
		var response = new ChatResponse("id", "model", List.of(message), List.of(), FinishReason.STOP, usage, Map.of());
		ChatClient client = new TestClient() {
			@Override
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(response);
			}
		};
		var actual = AgentChatProtocol.call(client, REQUEST);
		var restored = actual.messages().get(0);
		assertThat(restored.content().get(0)).isEqualTo(message.content().get(0));
		assertThat(restored.content().get(1).data()).containsExactly(1, 2);
		assertThat(restored.metadata()).containsEntry("source", "model");
		assertThat(actual.usage()).isEqualTo(usage);
	}

	@Test
	void streamingMediaOnlyResponseIsNotDropped() throws Exception {
		var image = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2 });
		var response = new ChatResponse("id", "model",
				List.of(new ChatMessage(ChatRole.ASSISTANT, List.of(image), Map.of())), List.of(), FinishReason.STOP,
				Usage.unknown(), Map.of());
		var messages = ReactAgent.builder()
			.name("image-client")
			.chatClient(streaming(ChatEvent.response(response)))
			.build()
			.streamMessages("question")
			.collectList()
			.block();
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0).content().get(0).data()).containsExactly(1, 2);
	}

	@Test
	void streamingTextThenStructuredMediaRetainsTheFinalStateWithoutTextReplay() throws Exception {
		var image = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2 });
		var expected = new ChatMessage(ChatRole.ASSISTANT, List.of(ContentPart.json("{\"ok\":true}"), image),
				Map.of("source", "model"));
		var usage = new Usage(3_000_000_000L, 2L, 3_000_000_002L);
		var response = new ChatResponse("id", "model", List.of(expected), List.of(), FinishReason.STOP, usage,
				Map.of());
		var runtime = ReactAgent.builder()
			.name("structured-stream")
			.chatClient(streaming(ChatEvent.textDelta("{\"ok\":"), ChatEvent.textDelta("true}"),
					ChatEvent.response(response)))
			.build();

		var outputs = runtime.stream("question").collectList().block(java.time.Duration.ofSeconds(5));

		assertThat(outputs).isNotEmpty();
		var visible = outputs.stream()
			.filter(io.github.agentic.ai.graph.streaming.StreamingOutput.class::isInstance)
			.map(output -> (io.github.agentic.ai.graph.streaming.StreamingOutput<?>) output)
			.filter(output -> output
				.getOutputType() == io.github.agentic.ai.graph.streaming.OutputType.AGENT_MODEL_STREAMING)
			.flatMap(output -> output.messages().stream())
			.toList();
		assertThat(visible.stream().map(ChatMessage::text).collect(java.util.stream.Collectors.joining()))
			.isEqualTo(expected.text());
		assertThat(visible.stream()
			.flatMap(message -> message.content().stream())
			.filter(part -> part.type() == ContentPart.Type.IMAGE)
			.toList()).hasSize(1);
		@SuppressWarnings("unchecked")
		var history = (List<ChatMessage>) outputs.get(outputs.size() - 1).state().value("messages").orElseThrow();
		assertThat(history.get(history.size() - 1)).isEqualTo(expected);
		assertThat(outputs.stream().map(io.github.agentic.ai.graph.NodeOutput::tokenUsage)).contains(usage);
	}

	static class LookupTools {

		final AtomicInteger executions = new AtomicInteger();

		@Tool(name = "lookup", description = "Looks up a value")
		public String lookup() {
			executions.incrementAndGet();
			return "tool-result";
		}

	}

	private static ChatResponse response(String text, List<ToolCall> tools) {
		return new ChatResponse("response-id", "test-model", List.of(ChatMessage.assistant(text, tools)), tools,
				tools.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS, new Usage(2L, 3L, 5L), Map.of());
	}

	private static ChatClient streaming(ChatEvent... events) {
		return new TestClient() {
			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				return JdkFlowAdapter.publisherToFlowPublisher(Flux.fromArray(events));
			}

			@Override
			public ChatClientCapabilities capabilities() {
				return new ChatClientCapabilities(true, true, false, false, true, true);
			}
		};
	}

	private abstract static class TestClient implements ChatClient {

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("Synchronous call not configured"));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			return JdkFlowAdapter
				.publisherToFlowPublisher(Flux.error(new UnsupportedOperationException("Stream not configured")));
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(false, true, false, false, true, true);
		}

	}

}
