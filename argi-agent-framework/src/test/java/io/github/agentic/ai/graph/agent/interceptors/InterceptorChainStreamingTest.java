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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.agent.interceptor.InterceptorChain;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.StreamingModelInterceptor;

import org.junit.jupiter.api.Test;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import reactor.adapter.JdkFlowAdapter;
import io.github.agentic.ai.api.chat.GenerationOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for {@link InterceptorChain#applyStreamingInterceptors}.
 *
 * <p>
 * Pin down the lifecycle guarantees that the streaming interceptor extension point relies
 * on:
 * <ul>
 * <li>Per-subscription isolation of aggregator state and {@link ModelRequest} copies (so
 * retries / multi-subscribe don't cross-contaminate)</li>
 * <li>{@code onStreamChunk} returning {@code null} drops the chunk from downstream and
 * from the aggregated message</li>
 * <li>Exceptions thrown from {@code beforeStreamCall} fan out to every interceptor's
 * {@code onStreamError} and surface as an upstream error to the subscriber</li>
 * <li>In-place mutation of the request inside {@code beforeStreamCall} does not affect
 * later subscriptions of the same wrapped Flux</li>
 * </ul>
 *
 * <p>
 * These tests use mock chunks (not a real ChatModel) so they run in CI without any model
 * credentials.
 */
class InterceptorChainStreamingTest {

	// ---------- helpers ----------

	private static ChatEvent chunk(String text) {
		return ChatEvent.textDelta(text);
	}

	private static ChatEvent response(String text) {
		return ChatEvent.response(ModelResponse.of(ChatMessage.assistant(text)).getChatResponse());
	}

	private static Flux<ChatEvent> apply(List<StreamingModelInterceptor> interceptors, Flux<ChatEvent> events,
			ModelRequest request) {
		return JdkFlowAdapter.flowPublisherToFlux(InterceptorChain.applyStreamingInterceptors(interceptors,
				JdkFlowAdapter.publisherToFlowPublisher(events), request));
	}

	private static ModelRequest newRequest() {
		return ModelRequest.builder()
			.messages(new ArrayList<>(List.of(ChatMessage.user("hi"))))
			.options(GenerationOptions.defaults())
			.build();
	}

	// ---------- tests ----------

	@Test
	void multiSubscribe_aggregatorIsPerSubscription() {
		AtomicInteger completeCount = new AtomicInteger();
		AtomicReference<String> lastAggregated = new AtomicReference<>();

		StreamingModelInterceptor i = new StreamingModelInterceptor() {
			@Override
			public void afterStreamComplete(ChatResponse m, ModelRequest r) {
				completeCount.incrementAndGet();
				lastAggregated.set(m.text());
			}
		};

		Flux<ChatEvent> wrapped = apply(List.of(i), Flux.just(chunk("a"), chunk("b"), response("ab")), newRequest());

		wrapped.blockLast();
		wrapped.blockLast();

		assertEquals(2, completeCount.get(), "afterStreamComplete should fire once per subscription");
		assertEquals("ab", lastAggregated.get(),
				"second subscription must see 'ab' (its own buffer), not 'abab' (shared buffer)");
	}

	@Test
	void onStreamChunkReturningNull_dropsChunkAndExcludesFromAggregate() {
		StreamingModelInterceptor dropAllB = new StreamingModelInterceptor() {
			@Override
			public ChatEvent onStreamChunk(ChatEvent c, ModelRequest r) {
				if ("b".equals(c.text())) {
					return null;
				}
				return c;
			}
		};

		AtomicReference<String> aggregated = new AtomicReference<>();
		StreamingModelInterceptor capture = new StreamingModelInterceptor() {
			@Override
			public void afterStreamComplete(ChatResponse m, ModelRequest r) {
				aggregated.set(m.text());
			}
		};

		Flux<ChatEvent> wrapped = apply(List.of(dropAllB, capture),
				Flux.just(chunk("a"), chunk("b"), chunk("c"), response("abc")), newRequest());

		List<String> emitted = wrapped.filter(c -> c.type() == ChatEvent.Type.TEXT_DELTA)
			.map(ChatEvent::text)
			.collectList()
			.block();

		assertEquals(List.of("a", "c"), emitted, "dropped chunk must not reach downstream");
		assertEquals("ac", aggregated.get(), "aggregator must not include text from dropped chunks");
	}

	@Test
	void beforeStreamCallThrows_fansOutOnStreamErrorToAllInterceptors_andSignalsErrorDownstream() {
		AtomicInteger errorCalls = new AtomicInteger();
		AtomicReference<Throwable> errorSeen = new AtomicReference<>();

		RuntimeException boom = new RuntimeException("boom");
		StreamingModelInterceptor exploding = new StreamingModelInterceptor() {
			@Override
			public ModelRequest beforeStreamCall(ModelRequest r) {
				throw boom;
			}

			@Override
			public void onStreamError(Throwable t, ModelRequest r) {
				errorCalls.incrementAndGet();
				errorSeen.set(t);
			}
		};
		StreamingModelInterceptor neverInvoked = new StreamingModelInterceptor() {
			@Override
			public void onStreamError(Throwable t, ModelRequest r) {
				errorCalls.incrementAndGet();
			}
		};

		Flux<ChatEvent> wrapped = apply(List.of(exploding, neverInvoked), Flux.just(chunk("x")), newRequest());

		RuntimeException thrown = assertThrows(RuntimeException.class, wrapped::blockLast,
				"the original exception must propagate to the subscriber");
		assertSame(boom, thrown, "downstream subscriber should observe the original throwable");

		assertEquals(2, errorCalls.get(), "every interceptor's onStreamError should fire when beforeStreamCall fails");
		assertSame(boom, errorSeen.get(), "onStreamError should receive the originating exception");
	}

	@Test
	void inPlaceContextMutation_doesNotLeakAcrossSubscriptions() {
		StreamingModelInterceptor mutator = new StreamingModelInterceptor() {
			@Override
			public ModelRequest beforeStreamCall(ModelRequest r) {
				r.getContext().put("counter", (int) r.getContext().getOrDefault("counter", 0) + 1);
				return r;
			}
		};

		AtomicReference<Object> seenCounter = new AtomicReference<>();
		StreamingModelInterceptor reader = new StreamingModelInterceptor() {
			@Override
			public ModelRequest beforeStreamCall(ModelRequest r) {
				seenCounter.set(r.getContext().get("counter"));
				return r;
			}
		};

		ModelRequest shared = newRequest();
		Flux<ChatEvent> wrapped = apply(List.of(mutator, reader), Flux.just(chunk("a"), response("a")), shared);

		wrapped.blockLast();
		Object firstSeen = seenCounter.get();
		wrapped.blockLast();
		Object secondSeen = seenCounter.get();

		assertEquals(1, firstSeen, "first subscription's mutator should bump counter to 1");
		assertEquals(1, secondSeen, "second subscription must start fresh — counter must be 1 again, not 2");
		assertNotNull(shared.getContext(),
				"original request's context should remain (and never grow) across subscriptions");
		assertEquals(0, shared.getContext().size(),
				"original request must not be mutated by interceptor in-place changes");
	}

	@Test
	void transformedTextRetainsTerminalContentToolsUsageAndMetadata() {
		ToolCall tool = new ToolCall("call", "lookup", "{}");
		ChatMessage message = new ChatMessage(ChatRole.ASSISTANT,
				List.of(ContentPart.text("abc"),
						ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2 })),
				List.of(tool), null, null, Map.of("message", "metadata"));
		ChatResponse original = new ChatResponse("id", "model", List.of(message), List.of(tool),
				FinishReason.TOOL_CALLS, new Usage(2L, 3L, 5L), Map.of("response", "metadata"));
		AtomicReference<ChatResponse> completion = new AtomicReference<>();
		StreamingModelInterceptor transform = new StreamingModelInterceptor() {
			@Override
			public ChatEvent onStreamChunk(ChatEvent event, ModelRequest request) {
				if (event.type() == ChatEvent.Type.TEXT_DELTA) {
					return "b".equals(event.text()) ? null
							: ChatEvent.textDelta(event.text().toUpperCase(java.util.Locale.ROOT));
				}
				return event;
			}

			@Override
			public void afterStreamComplete(ChatResponse response, ModelRequest request) {
				completion.set(response);
			}
		};
		List<ChatEvent> output = apply(List.of(transform), Flux.just(chunk("a"), chunk("b"), ChatEvent.toolCall(tool),
				ChatEvent.usage(original.usage()), ChatEvent.response(original)), newRequest())
			.collectList()
			.block();
		assertNotNull(output);
		// The terminal-only suffix "c" must also pass through text transformation.
		assertEquals(List.of("A", "C"),
				output.stream()
					.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
					.map(ChatEvent::text)
					.toList());
		ChatResponse terminal = output.get(output.size() - 1).response();
		assertSame(terminal, completion.get());
		assertEquals("AC", terminal.text());
		assertEquals(original.id(), terminal.id());
		assertEquals(original.model(), terminal.model());
		assertEquals(original.toolCalls(), terminal.toolCalls());
		assertEquals(original.finishReason(), terminal.finishReason());
		assertEquals(original.usage(), terminal.usage());
		assertEquals(original.metadata(), terminal.metadata());
		assertEquals(message.metadata(), terminal.messages().get(0).metadata());
		assertEquals(message.toolCalls(), terminal.messages().get(0).toolCalls());
		assertSame(message.content().get(1), terminal.messages().get(0).content().get(1));
	}

	@Test
	void finalOnlyResponseStillPassesTextThroughEveryInterceptor() {
		StreamingModelInterceptor uppercase = new StreamingModelInterceptor() {
			@Override
			public ChatEvent onStreamChunk(ChatEvent event, ModelRequest request) {
				return event.type() == ChatEvent.Type.TEXT_DELTA
						? chunk(event.text().toUpperCase(java.util.Locale.ROOT)) : event;
			}
		};
		StreamingModelInterceptor decorate = new StreamingModelInterceptor() {
			@Override
			public ChatEvent onStreamChunk(ChatEvent event, ModelRequest request) {
				return event.type() == ChatEvent.Type.TEXT_DELTA ? chunk("[" + event.text() + "]") : event;
			}
		};
		List<ChatEvent> events = apply(List.of(uppercase, decorate), Flux.just(response("answer")), newRequest())
			.collectList()
			.block();
		assertNotNull(events);
		assertEquals("[ANSWER]", events.get(0).text());
		assertEquals("[ANSWER]", events.get(1).response().text());
	}

	@Test
	void invalidStreamProtocolsFailEvenWithoutInterceptors() {
		StepVerifier.create(apply(List.of(), Flux.empty(), newRequest()))
			.expectError(IllegalStateException.class)
			.verify();
		StepVerifier.create(apply(List.of(), Flux.just(chunk("partial")), newRequest()))
			.expectNext(chunk("partial"))
			.expectError(IllegalStateException.class)
			.verify();
		StepVerifier.create(apply(List.of(), Flux.just(chunk("a"), response("b")), newRequest()))
			.expectNext(chunk("a"))
			.expectError(IllegalStateException.class)
			.verify();
		StepVerifier.create(apply(List.of(), Flux.just(response(""), chunk("late")), newRequest()))
			.expectNext(response(""))
			.expectError(IllegalStateException.class)
			.verify();
		StepVerifier.create(apply(List.of(), Flux.just(response(""), response("")), newRequest()))
			.expectNext(response(""))
			.expectError(IllegalStateException.class)
			.verify();
	}

	@Test
	void interceptorsCannotDropRetypeOrRewriteTerminalText() {
		for (ChatEvent replacement : java.util.Arrays.asList(null, chunk("wrong"), response("wrong"))) {
			StreamingModelInterceptor invalid = new StreamingModelInterceptor() {
				@Override
				public ChatEvent onStreamChunk(ChatEvent event, ModelRequest request) {
					return event.type() == ChatEvent.Type.RESPONSE ? replacement : event;
				}
			};
			StepVerifier.create(apply(List.of(invalid), Flux.just(response("answer")), newRequest()))
				.expectNext(chunk("answer"))
				.expectError(IllegalStateException.class)
				.verify();
		}
	}

	@Test
	void cancellationCancelsSourceWithoutCompletionOrErrorCallbacks() {
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicInteger completions = new AtomicInteger();
		AtomicInteger errors = new AtomicInteger();
		StreamingModelInterceptor observer = new StreamingModelInterceptor() {
			@Override
			public void afterStreamComplete(ChatResponse response, ModelRequest request) {
				completions.incrementAndGet();
			}

			@Override
			public void onStreamError(Throwable error, ModelRequest request) {
				errors.incrementAndGet();
			}
		};
		StepVerifier
			.create(apply(List.of(observer), Flux.<ChatEvent>never().doOnCancel(() -> cancelled.set(true)),
					newRequest()))
			.thenCancel()
			.verify();
		assertTrue(cancelled.get());
		assertEquals(0, completions.get());
		assertEquals(0, errors.get());
	}

	@Test
	void errorCallbackFailuresAreSuppressedInsteadOfReplacingOriginalError() {
		RuntimeException original = new RuntimeException("source");
		RuntimeException callback = new RuntimeException("callback");
		StreamingModelInterceptor broken = new StreamingModelInterceptor() {
			@Override
			public void onStreamError(Throwable error, ModelRequest request) {
				throw callback;
			}
		};
		StepVerifier.create(apply(List.of(broken), Flux.error(original), newRequest()))
			.expectErrorMatches(error -> error == original && List.of(error.getSuppressed()).contains(callback))
			.verify();
	}

}
