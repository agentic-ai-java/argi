/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ReactAgentCheckpointExecutionQueueTest {

	private static final List<String> SEED_MESSAGES = List.of("seed", "answer-seed");

	@Test
	void cancellingWaitingTurnDoesNotCaptureOrRewindCheckpoint() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.seed();
			fixture.subscribe("A");
			fixture.model.awaitEntered("A");
			final int readsBefore = fixture.saver.reads.get();
			final int writesBefore = fixture.saver.writes.get();

			final Disposable waiting = fixture.subscribe("B");
			waiting.dispose();

			assertEquals(readsBefore, fixture.saver.reads.get(), "A waiting turn must not capture a rewind baseline");
			assertEquals(writesBefore, fixture.saver.writes.get(), "Cancelling a waiting turn must not rewind the active turn");
			assertEquals(1, fixture.model.calls.get("B").entered.getCount());

			fixture.model.complete("A");
			fixture.awaitCompleted("A");
			assertEquals(List.of("seed", "answer-seed", "A", "answer-A"), fixture.latestMessages());
			assertTrue(fixture.errors.isEmpty());
		}
	}

	@Test
	void queuedTurnCapturesRewindBaselineAfterPreviousTurnCommits() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.seed();
			fixture.subscribe("A");
			fixture.model.awaitEntered("A");
			final Disposable waiting = fixture.subscribe("B");

			fixture.model.complete("A");
			fixture.awaitCompleted("A");
			fixture.model.awaitEntered("B");
			assertEquals(List.of("seed", "answer-seed", "A", "answer-A", "B"),
					texts(fixture.model.calls.get("B").messages));

			fixture.saver.expectRewind(List.of("seed", "answer-seed", "A", "answer-A"), false);
			waiting.dispose();
			fixture.saver.awaitRewind();

			assertEquals(List.of("seed", "answer-seed", "A", "answer-A"), fixture.latestMessages(),
					"Cancelling B must keep the turn that completed while B was queued");
			assertTrue(fixture.errors.isEmpty());
		}
	}

	@Test
	void activeTurnFinishesCancellationRewindBeforeWaitingTurnStarts() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.seed();
			final Disposable active = fixture.subscribe("A");
			fixture.model.awaitEntered("A");
			fixture.subscribe("B");
			fixture.saver.expectRewind(SEED_MESSAGES, true);

			final CompletableFuture<Void> cancellation = CompletableFuture.runAsync(active::dispose);
			assertTrue(fixture.saver.rewindEntered.await(10, TimeUnit.SECONDS), "Cancellation must reach the rewind write");
			assertFalse(fixture.model.calls.get("B").entered.await(200, TimeUnit.MILLISECONDS),
					"The next turn must stay queued while its predecessor is still rewinding");

			fixture.saver.releaseRewind.countDown();
			cancellation.get(10, TimeUnit.SECONDS);
			fixture.saver.awaitRewind();
			fixture.model.awaitEntered("B");
			assertEquals(List.of("seed", "answer-seed", "B"), texts(fixture.model.calls.get("B").messages),
					"B must load the restored baseline and must not inherit cancelled A");

			fixture.model.complete("B");
			fixture.awaitCompleted("B");
			assertEquals(List.of("seed", "answer-seed", "B", "answer-B"), fixture.latestMessages());
			assertTrue(fixture.errors.isEmpty());
		}
	}

	private static List<String> texts(List<Message> messages) {
		return messages.stream().filter(message -> message instanceof UserMessage || message instanceof AssistantMessage)
			.map(Message::getText).toList();
	}

	private static List<String> checkpointTexts(Checkpoint checkpoint) {
		final Object value = checkpoint.getState().get("messages");
		if (!(value instanceof List<?> messages)) {
			return List.of();
		}
		return texts(messages.stream().filter(Message.class::isInstance).map(Message.class::cast).toList());
	}

	private static ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static final class ControlledModel implements ChatModel {

		private final Map<String, Call> calls = Map.of("A", new Call(), "B", new Call());

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("The test uses streaming model calls");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.defer(() -> {
				final List<Message> messages = prompt.getInstructions();
				final String request = messages.stream().filter(UserMessage.class::isInstance)
					.map(Message::getText).reduce((first, second) -> second).orElseThrow();
				if ("seed".equals(request)) {
					return Flux.just(response("answer-seed"));
				}
				final Call call = calls.get(request);
				call.messages = List.copyOf(messages);
				call.entered.countDown();
				return call.result.asMono().flux();
			});
		}

		private void awaitEntered(String request) throws InterruptedException {
			assertTrue(calls.get(request).entered.await(10, TimeUnit.SECONDS), "The model call must start: " + request);
		}

		private void complete(String request) {
			assertEquals(Sinks.EmitResult.OK, calls.get(request).result.tryEmitValue(response("answer-" + request)));
		}

		private static final class Call {

			private final CountDownLatch entered = new CountDownLatch(1);

			private final Sinks.One<ChatResponse> result = Sinks.one();

			private volatile List<Message> messages = List.of();

		}

	}

	private static final class RecordingSaver implements BaseCheckpointSaver {

		private final MemorySaver delegate = new MemorySaver();

		private final AtomicInteger reads = new AtomicInteger();

		private final AtomicInteger writes = new AtomicInteger();

		private final CountDownLatch rewindEntered = new CountDownLatch(1);

		private final CountDownLatch releaseRewind = new CountDownLatch(1);

		private final CountDownLatch rewindCompleted = new CountDownLatch(1);

		private volatile List<String> expectedRewind;

		private volatile boolean blockRewind;

		@Override
		public Optional<Checkpoint> get(RunnableConfig config) {
			reads.incrementAndGet();
			return delegate.get(config);
		}

		@Override
		public Collection<Checkpoint> list(RunnableConfig config) {
			return delegate.list(config);
		}

		@Override
		public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
			writes.incrementAndGet();
			final boolean rewind = expectedRewind != null && expectedRewind.equals(checkpointTexts(checkpoint));
			if (rewind) {
				rewindEntered.countDown();
				if (blockRewind && !releaseRewind.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("The test must release the rewind write");
				}
			}
			final RunnableConfig result = delegate.put(config, checkpoint);
			if (rewind) {
				rewindCompleted.countDown();
			}
			return result;
		}

		@Override
		public Tag release(RunnableConfig config) throws Exception {
			return delegate.release(config);
		}

		private void expectRewind(List<String> messages, boolean block) {
			expectedRewind = messages;
			blockRewind = block;
		}

		private void awaitRewind() throws InterruptedException {
			assertTrue(rewindCompleted.await(10, TimeUnit.SECONDS), "The cancellation rewind must complete");
		}

	}

	private static final class Fixture implements AutoCloseable {

		private final RecordingSaver saver = new RecordingSaver();

		private final ControlledModel model = new ControlledModel();

		private final RunnableConfig config = RunnableConfig.builder().threadId("queued-turns").build();

		private final ReactAgent agent = ReactAgent.builder().name("queued-turn-agent").model(model).saver(saver).build();

		private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

		private final List<Throwable> errors = new CopyOnWriteArrayList<>();

		private final Map<String, CountDownLatch> completed = Map.of("A", new CountDownLatch(1), "B", new CountDownLatch(1));

		private void seed() throws Exception {
			agent.stream("seed", config).collectList().block(Duration.ofSeconds(10));
			assertEquals(SEED_MESSAGES, latestMessages());
		}

		private Disposable subscribe(String request) throws Exception {
			final Disposable subscription = agent.stream(request, config)
				.doOnComplete(() -> completed.get(request).countDown()).subscribe(output -> { }, errors::add);
			subscriptions.add(subscription);
			return subscription;
		}

		private void awaitCompleted(String request) throws InterruptedException {
			assertTrue(completed.get(request).await(10, TimeUnit.SECONDS), "The turn must complete: " + request);
		}

		private List<String> latestMessages() {
			return checkpointTexts(saver.delegate.get(config).orElseThrow());
		}

		@Override
		public void close() {
			saver.releaseRewind.countDown();
			subscriptions.forEach(Disposable::dispose);
		}

	}

}
