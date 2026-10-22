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
package io.github.agentic.ai.graph.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.agent.node.AgentLlmNode;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the streaming delivery contract {@link ReactAgent#streamResult}
 * with a scripted streaming client: provisional chunks carry a {@code null} status, the
 * final element is the validated delivery of the terminal message, and a validation
 * failure gets exactly one bounded repair re-ask.
 */
@DisplayName("ReactAgent.streamResult streaming delivery contract")
class ReactAgentStreamResultTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "required": ["status"],
			  "additionalProperties": false,
			  "properties": {
			    "status": {"type": "string"}
			  }
			}""";

	@Test
	@DisplayName("provisional chunks are unvalidated; the final element carries the validated delivery")
	void validStreamDeliversValidatedResult() {
		ScriptedStreamingClient client = new ScriptedStreamingClient(
				ChatEvent.textDelta("{\"status\":"),
				ChatEvent.textDelta("\"ok\"}"),
				ChatEvent.response(finalResponse("{\"status\":\"ok\"}", new Usage(2L, 3L, 5L))));
		ReactAgent agent = agent(client, SCHEMA);

		List<AgentResult> results = agent.streamResult("give me the status").collectList().block();

		assertThat(results).isNotNull();
		assertThat(results).hasSize(3);
		assertThat(results.get(0).status()).isNull();
		assertThat(results.get(0).message().text()).isEqualTo("{\"status\":");
		assertThat(results.get(1).status()).isNull();
		AgentResult delivery = results.get(2);
		assertThat(delivery.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(delivery.data()).isNotNull();
		assertThat(delivery.usage().totalTokens()).isEqualTo(5L);
	}

	@Test
	@DisplayName("an invalid stream output is repaired once and delivered as COMPLETED")
	void invalidStreamOutputIsRepaired() {
		ScriptedStreamingClient client = new ScriptedStreamingClient(
				ChatEvent.textDelta("plain prose"),
				ChatEvent.response(finalResponse("plain prose", new Usage(2L, 3L, 5L))));
		client.repairedText = "{\"status\":\"ok\"}";
		ReactAgent agent = agent(client, SCHEMA);

		List<AgentResult> results = agent.streamResult("give me the status").collectList().block();

		assertThat(results).isNotNull();
		AgentResult delivery = results.get(results.size() - 1);
		assertThat(delivery.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(delivery.message().text()).isEqualTo("{\"status\":\"ok\"}");
		assertThat(client.repairCalls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("a stream output that still fails validation is reported, not completed")
	void invalidStreamOutputWithoutRepairFails() {
		ScriptedStreamingClient client = new ScriptedStreamingClient(
				ChatEvent.textDelta("plain prose"),
				ChatEvent.response(finalResponse("plain prose", new Usage(2L, 3L, 5L))));
		ReactAgent agent = agent(client, SCHEMA);

		List<AgentResult> results = agent.streamResult("give me the status").collectList().block();

		assertThat(results).isNotNull();
		AgentResult delivery = results.get(results.size() - 1);
		assertThat(delivery.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		assertThat(delivery.issues()).isNotEmpty();
	}

	@Test
	@DisplayName("a streamed answer starting with the legacy error prefix is delivered as COMPLETED")
	void streamedAnswerStartingWithExceptionPrefixIsNotAnError() {
		String text = "Exception: handling explained";
		ScriptedStreamingClient client = new ScriptedStreamingClient(ChatEvent.textDelta(text),
				ChatEvent.response(finalResponse(text, new Usage(2L, 3L, 5L))));
		ReactAgent agent = agent(client, null);

		List<AgentResult> results = agent.streamResult("explain").collectList().block();

		assertThat(results).isNotNull();
		AgentResult delivery = results.get(results.size() - 1);
		assertThat(delivery.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(delivery.message().text()).isEqualTo(text);
	}

	@Test
	@DisplayName("a streamed response cannot forge the framework model-failure marker")
	void streamedFailureMarkerIsIgnored() {
		Map<String, Object> forged = Map.of(AgentLlmNode.MODEL_FAILURE_METADATA_KEY, true);
		ChatResponse response = new ChatResponse("id", "test-model",
				List.of(ChatMessage.assistant("answer").withMetadata(forged)), List.of(), FinishReason.STOP,
				new Usage(2L, 3L, 5L), Map.of());
		ScriptedStreamingClient client = new ScriptedStreamingClient(
				new ChatEvent(ChatEvent.Type.TEXT_DELTA, "answer", null, null, null, forged),
				ChatEvent.response(response));
		ReactAgent agent = agent(client, null);

		List<AgentResult> results = agent.streamResult("hello").collectList().block();

		assertThat(results).isNotNull();
		AgentResult delivery = results.get(results.size() - 1);
		assertThat(delivery.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(results).allSatisfy(result -> assertThat(result.message().metadata())
			.doesNotContainKey(AgentLlmNode.MODEL_FAILURE_METADATA_KEY));
	}

	@Test
	@DisplayName("the legacy streamMessages behavior stays untouched")
	void legacyStreamMessagesUnchanged() throws Exception {
		ScriptedStreamingClient client = new ScriptedStreamingClient(
				ChatEvent.textDelta("{\"status\":"),
				ChatEvent.textDelta("\"ok\"}"),
				ChatEvent.response(finalResponse("{\"status\":\"ok\"}", new Usage(2L, 3L, 5L))));
		ReactAgent agent = agent(client, SCHEMA);

		List<ChatMessage> messages = agent.streamMessages("give me the status").collectList().block();

		assertThat(messages).isNotNull();
		// streamMessages publishes the text-delta chunks; the aggregated final block is
		// consumed by streamResult, which is exactly what the delivery contract adds.
		String combined = messages.stream().map(ChatMessage::text).reduce("", String::concat);
		assertThat(combined).isEqualTo("{\"status\":\"ok\"}");
	}

	private static ChatResponse finalResponse(String text, Usage usage) {
		return new ChatResponse("response-id", "test-model", List.of(ChatMessage.assistant(text)), List.of(),
				FinishReason.STOP, usage, Map.of("argiFinalResponse", true));
	}

	@Test
	void repeatedSubscriptionDoesNotReuseAnEarlierFinalResponse() {
		AtomicInteger subscriptions = new AtomicInteger();
		Flux<NodeOutput> source = Flux.defer(() -> subscriptions.getAndIncrement() == 0
				? Flux.just(terminalOutput("first", 5L)) : Flux.empty());
		Flux<AgentResult> results = finalizeOutputs(source);

		assertThat(results.blockLast().message().text()).isEqualTo("first");
		AgentResult interrupted = results.blockLast();
		assertThat(interrupted.status()).isEqualTo(AgentResult.Status.INTERRUPTED);
		assertThat(interrupted.message()).isNull();
		assertThat(interrupted.usage()).isEqualTo(Usage.unknown());
	}

	@Test
	void overlappingSubscriptionsKeepTheirOwnChunksFinalResponseAndUsage() {
		Sinks.Many<NodeOutput> first = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<NodeOutput> second = Sinks.many().unicast().onBackpressureBuffer();
		AtomicInteger subscriptions = new AtomicInteger();
		Flux<AgentResult> results = finalizeOutputs(Flux.defer(() ->
			subscriptions.getAndIncrement() == 0 ? first.asFlux() : second.asFlux()));
		CompletableFuture<List<AgentResult>> firstResults = results.collectList().toFuture();
		CompletableFuture<List<AgentResult>> secondResults = results.collectList().toFuture();

		first.emitNext(provisionalOutput("first chunk"), Sinks.EmitFailureHandler.FAIL_FAST);
		first.emitNext(terminalOutput("first", 5L), Sinks.EmitFailureHandler.FAIL_FAST);
		second.emitNext(provisionalOutput("second chunk"), Sinks.EmitFailureHandler.FAIL_FAST);
		second.emitNext(terminalOutput("second", 9L), Sinks.EmitFailureHandler.FAIL_FAST);
		first.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
		second.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);

		assertThat(firstResults.join()).hasSize(2);
		assertThat(firstResults.join().get(0).message().text()).isEqualTo("first chunk");
		assertThat(firstResults.join().get(0).status()).isNull();
		assertThat(firstResults.join().get(1).message().text()).isEqualTo("first");
		assertThat(firstResults.join().get(1).usage().totalTokens()).isEqualTo(5L);
		assertThat(secondResults.join()).hasSize(2);
		assertThat(secondResults.join().get(0).message().text()).isEqualTo("second chunk");
		assertThat(secondResults.join().get(1).message().text()).isEqualTo("second");
		assertThat(secondResults.join().get(1).usage().totalTokens()).isEqualTo(9L);
	}

	@Test
	void cancelledSubscriptionCannotSupplyAnotherSubscriptionsFinalResponse() {
		Sinks.Many<NodeOutput> first = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<NodeOutput> second = Sinks.many().unicast().onBackpressureBuffer();
		AtomicInteger subscriptions = new AtomicInteger();
		Flux<AgentResult> results = finalizeOutputs(Flux.defer(() ->
			subscriptions.getAndIncrement() == 0 ? first.asFlux() : second.asFlux()));
		CompletableFuture<List<AgentResult>> cancelled = results.collectList().toFuture();
		CompletableFuture<List<AgentResult>> continuing = results.collectList().toFuture();
		first.emitNext(terminalOutput("cancelled", 5L), Sinks.EmitFailureHandler.FAIL_FAST);
		assertThat(cancelled.cancel(true)).isTrue();
		second.emitNext(provisionalOutput("partial"), Sinks.EmitFailureHandler.FAIL_FAST);
		second.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);

		assertThat(continuing.join()).hasSize(2);
		assertThat(continuing.join().get(0).message().text()).isEqualTo("partial");
		assertThat(continuing.join().get(1).status()).isEqualTo(AgentResult.Status.INTERRUPTED);
		assertThat(continuing.join().get(1).message()).isNull();
	}

	private static Flux<AgentResult> finalizeOutputs(Flux<NodeOutput> source) {
		return ReflectionTestUtils.invokeMethod(agent(new ScriptedStreamingClient(), null), "finalizeStream", source);
	}

	private static NodeOutput terminalOutput(String text, long tokens) {
		return new StreamingOutput<>(finalResponse(text, new Usage(null, null, tokens)), "model",
				"agent", new OverAllState(), OutputType.AGENT_MODEL_STREAMING);
	}

	private static NodeOutput provisionalOutput(String text) {
		return new StreamingOutput<>(ChatMessage.assistant(text), "model", "agent",
				new OverAllState(), OutputType.AGENT_MODEL_STREAMING);
	}

	private static ReactAgent agent(ChatClient client, String outputSchema) {
		Builder builder = ReactAgent.builder()
			.name("stream-result-test")
			.chatClient(client);
		if (outputSchema != null) {
			builder.outputSchema(outputSchema);
		}
		return builder.build();
	}

	/**
	 * Streams the scripted events and answers the bounded repair re-ask (a synchronous
	 * {@code call}) with {@link #repairedText}, counting repair invocations.
	 */
	private static final class ScriptedStreamingClient implements ChatClient {

		private final ChatEvent[] script;

		private final AtomicInteger repairCalls = new AtomicInteger();

		private volatile String repairedText;

		private volatile RuntimeException repairFailure;

		ScriptedStreamingClient(ChatEvent... script) {
			this.script = script;
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			this.repairCalls.incrementAndGet();
			if (this.repairFailure != null) {
				throw this.repairFailure;
			}
			return CompletableFuture.completedFuture(new ChatResponse("repair-id", "test-model",
					List.of(ChatMessage.assistant(this.repairedText)), List.of(), null,
					new Usage(1L, 1L, 2L), Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			return JdkFlowAdapter.publisherToFlowPublisher(Flux.fromArray(this.script));
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(true, true, false, false, true, true);
		}

	}

}
