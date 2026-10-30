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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.AgentResult;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.agent.result.StructuredOutputFinalizer;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery consistency of the structured output boundary in the independent ReAct
 * agent: whatever message actually passed validation is the message finally
 * delivered, for the outcome snapshot and for stream consumers alike.
 */
class ReActAgentStructuredDeliveryTest {

	private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"answer\"],"
			+ "\"additionalProperties\":false,\"properties\":{\"answer\":{\"type\":\"string\"}}}";

	private static final String REPAIRED = "{\"answer\":\"fixed\"}";

	@Test
	void validOriginalOutputIsDeliveredUnchanged() throws Exception {
		AtomicInteger repairs = new AtomicInteger();
		ReActAgent agent = ReActAgent.builder(new ScriptedClient("{\"answer\":\"direct\"}"))
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(failingRepairClient(repairs)))
			.build();

		AgentOutcome outcome = agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);

		assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		assertEquals("{\"answer\":\"direct\"}", outcome.messages().get(outcome.messages().size() - 1).text());
		assertEquals("{\"answer\":\"direct\"}", outcome.outputs().get("structuredOutput").toString());
		assertEquals(0, repairs.get());
	}

	@Test
	void successfulRepairDeliversRepairedMessageAsFinal() throws Exception {
		ReActAgent agent = ReActAgent.builder(new ScriptedClient("not-json"))
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(scriptedRepairClient(REPAIRED)))
			.build();

		AgentOutcome outcome = agent.invoke(request(), context()).get(3, TimeUnit.SECONDS);

		assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		// The repaired message is the final delivered message, agreeing with the data.
		assertEquals(REPAIRED, outcome.messages().get(outcome.messages().size() - 1).text());
		assertEquals(REPAIRED, outcome.outputs().get("structuredOutput").toString());
		// The failed original stays in history right before it, not silently dropped.
		assertEquals("not-json", outcome.messages().get(outcome.messages().size() - 2).text());
	}

	@Test
	void noRepairClientKeepsValidationFailedState() throws Exception {
		ReActAgent agent = ReActAgent.builder(new ScriptedClient("not-json"))
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer())
			.build();

		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> agent.invoke(request(), context()).get(3, TimeUnit.SECONDS));

		assertTrue(failure.getCause() instanceof AgentExecutionException);
		AgentExecutionException delivery = (AgentExecutionException) failure.getCause();
		assertEquals(AgentResult.Status.VALIDATION_FAILED, delivery.result().status());
		assertTrue(delivery.result().issues().stream().anyMatch(
				issue -> AgentResult.ERROR_CODE_INVALID_JSON.equals(issue.errorCode())));
	}

	@Test
	void repairStillInvalidKeepsFailureStateDistinct() throws Exception {
		ReActAgent agent = ReActAgent.builder(new ScriptedClient("not-json"))
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(scriptedRepairClient("{\"wrong\":\"shape\"}")))
			.build();

		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> agent.invoke(request(), context()).get(3, TimeUnit.SECONDS));

		assertTrue(failure.getCause() instanceof AgentExecutionException);
		AgentExecutionException delivery = (AgentExecutionException) failure.getCause();
		assertEquals(AgentResult.Status.VALIDATION_FAILED, delivery.result().status());
		assertTrue(delivery.result().issues().stream().anyMatch(
				issue -> AgentResult.ERROR_CODE_REPAIRED_STILL_INVALID.equals(issue.errorCode())));
	}

	@Test
	void repairClientFailureMarksRepairCallFailed() throws Exception {
		ReActAgent agent = ReActAgent.builder(new ScriptedClient("not-json"))
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(brokenRepairClient()))
			.build();

		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> agent.invoke(request(), context()).get(3, TimeUnit.SECONDS));

		assertTrue(failure.getCause() instanceof AgentExecutionException);
		AgentExecutionException delivery = (AgentExecutionException) failure.getCause();
		assertEquals(AgentResult.Status.VALIDATION_FAILED, delivery.result().status());
		assertTrue(delivery.result().issues().stream().anyMatch(
				issue -> AgentResult.ERROR_CODE_REPAIR_CALL_FAILED.equals(issue.errorCode())));
	}

	@Test
	void streamingCompletionExposesRepairedFinalMessage() throws Exception {
		ReActAgent agent = ReActAgent.builder(new StreamingClient("not-json"))
			.streamModel(true)
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(scriptedRepairClient(REPAIRED)))
			.build();
		List<AgentEvent> events = new ArrayList<>();
		CompletableFuture<AgentOutcome> finished = new CompletableFuture<>();
		try (AgentExecutionContext context = context()) {
			agent.stream(request(), context).subscribe(new Flow.Subscriber<>() {
				public void onSubscribe(Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}

				public void onNext(AgentEvent event) {
					events.add(event);
					if (event instanceof AgentEvent.Finished done) {
						finished.complete(done.outcome());
					}
				}

				public void onError(Throwable error) {
					finished.completeExceptionally(error);
				}

				public void onComplete() {
				}
			});
			AgentOutcome outcome = finished.get(3, TimeUnit.SECONDS);
			assertEquals(REPAIRED, outcome.messages().get(outcome.messages().size() - 1).text());
			assertEquals(REPAIRED, outcome.outputs().get("structuredOutput").toString());
		}
		// The repaired message reaches stream consumers before the finished snapshot,
		// through the existing complete-message event contract.
		int repairIndex = -1;
		int finishIndex = -1;
		for (int index = 0; index < events.size(); index++) {
			AgentEvent event = events.get(index);
			if (event instanceof AgentEvent.Message message && "model-repair".equals(message.messageId())) {
				repairIndex = index;
				assertEquals(REPAIRED, message.message().text());
			}
			if (event instanceof AgentEvent.Finished) {
				finishIndex = index;
			}
		}
		assertTrue(repairIndex >= 0, "stream consumers must see the repaired final message");
		assertTrue(finishIndex > repairIndex, "the repaired message must precede the finished event");
	}

	private static AgentRequest request() {
		return AgentRequest.messages(List.of(ChatMessage.user("hello")));
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run").correlationId("correlation").budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none()).build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	private static ChatResponse response(ChatMessage message) {
		return new ChatResponse(null, "test", List.of(message), List.of(), FinishReason.STOP, new Usage(1L, 1L, 1L),
				Map.of());
	}

	private static ChatClient scriptedRepairClient(String json) {
		return new ChatClient() {
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(response(ChatMessage.assistant(json)));
			}

			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				throw new UnsupportedOperationException();
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
	}

	private static ChatClient brokenRepairClient() {
		return new ChatClient() {
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.failedFuture(new IllegalStateException("repair backend down"));
			}

			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				throw new UnsupportedOperationException();
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
	}

	private static ChatClient failingRepairClient(AtomicInteger calls) {
		return new ChatClient() {
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				calls.incrementAndGet();
				throw new AssertionError("the repair client must not be called");
			}

			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				throw new UnsupportedOperationException();
			}

			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
	}

	private static class ScriptedClient implements ChatClient {

		private final ChatResponse reply;

		ScriptedClient(String terminalText) {
			this.reply = response(ChatMessage.assistant(terminalText));
		}

		public CompletionStage<ChatResponse> call(ChatRequest request) {
			return CompletableFuture.completedFuture(reply);
		}

		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException();
		}

		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

	private static final class StreamingClient extends ScriptedClient {

		StreamingClient(String terminalText) {
			super(terminalText);
		}

		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					subscriber.onNext(ChatEvent.textDelta("not-json"));
					subscriber.onNext(ChatEvent.response(response(ChatMessage.assistant("not-json"))));
					subscriber.onComplete();
				}

				public void cancel() {
				}
			});
		}

	}

}
