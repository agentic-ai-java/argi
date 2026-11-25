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
package io.github.agentic.ai.agent.orchestration.a2a;

import io.github.agentic.ai.workflow.graph.GraphResponse;
import io.github.agentic.ai.workflow.graph.NodeOutput;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.workflow.graph.streaming.StreamingOutput;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #212 (OPT-21): the A2A streaming producer must apply demand-driven backpressure
 * instead of buffering events without bound. A subscriber that stops requesting must make
 * the producer stall (bounded wait, cancellable) and then fail the stream with an explicit
 * backpressure error; the terminal state must never be lost behind buffered events, and a
 * consumer that resumes within the configured wait must still receive every event in
 * order.
 */
class A2aStreamBackpressureTest {

	private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(10);

	@Test
	void stalledConsumerFailsWithExplicitBackpressureError() throws Exception {
		CountDownLatch releaseServer = new CountDownLatch(1);
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try {
				// A chatty peer: enough events to overflow any internal buffer.
				for (int i = 0; i < 100; i++) {
					exchange.getResponseBody()
						.write(("data: " + chunk("c" + i) + "\n\n").getBytes(StandardCharsets.UTF_8));
					exchange.getResponseBody().flush();
				}
				// Keep the connection open so that only the local backpressure policy can
				// terminate the stream.
				awaitQuietly(releaseServer);
			}
			catch (IOException ignored) {
				// the client is expected to abort the exchange once it fails the stream
			}
			finally {
				exchange.close();
			}
		});
		try {
			Flux<GraphResponse<NodeOutput>> flux = streamingFlux(server, Duration.ofMillis(250));

			AtomicReference<Throwable> error = new AtomicReference<>();
			CountDownLatch terminal = new CountDownLatch(1);
			AtomicInteger items = new AtomicInteger();
			BaseSubscriber<GraphResponse<NodeOutput>> subscriber = new BaseSubscriber<>() {
				@Override
				protected void hookOnSubscribe(Subscription subscription) {
					subscription.request(1);
				}

				@Override
				protected void hookOnNext(GraphResponse<NodeOutput> value) {
					items.incrementAndGet();
				}

				@Override
				protected void hookOnError(Throwable throwable) {
					error.set(throwable);
					terminal.countDown();
				}

				@Override
				protected void hookOnComplete() {
					terminal.countDown();
				}
			};
			flux.subscribe(subscriber);

			assertTrue(terminal.await(5, TimeUnit.SECONDS), "the stalled stream must terminate in bounded time");
			assertEquals(1, items.get(), "only the first chunk fits the initial demand");
			assertTrue(error.get() instanceof A2aBackpressureException, "got: " + error.get());
			assertTrue(error.get() instanceof IllegalStateException, "legacy callers keep working");
			assertTrue(error.get().getMessage().contains("requested no event for"), "got: " + error.get());

			assertTrue(awaitWorkerOutOfNode(3, TimeUnit.SECONDS), "the stalled worker must be released");
		}
		finally {
			releaseServer.countDown();
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void slowConsumerReceivesEveryChunkAndDoneInOrder() throws Exception {
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			for (String text : new String[] { "a", "b", "c", "d" }) {
				exchange.getResponseBody().write(("data: " + chunk(text) + "\n\n")
					.getBytes(StandardCharsets.UTF_8));
				exchange.getResponseBody().flush();
			}
			exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			exchange.close();
		});
		try {
			Flux<GraphResponse<NodeOutput>> flux = streamingFlux(server, Duration.ofSeconds(2));
			StepVerifier.create(flux, 1)
				.expectNextMatches(response -> "a".equals(streamingText(response)))
				.thenAwait(Duration.ofMillis(30))
				.thenRequest(1)
				.expectNextMatches(response -> "b".equals(streamingText(response)))
				.thenAwait(Duration.ofMillis(30))
				.thenRequest(1)
				.expectNextMatches(response -> "c".equals(streamingText(response)))
				.thenAwait(Duration.ofMillis(30))
				.thenRequest(1)
				.expectNextMatches(response -> "d".equals(streamingText(response)))
				.thenAwait(Duration.ofMillis(30))
				.thenRequest(1)
				.expectNextMatches(response -> {
					assertTrue(response.isDone());
					assertEquals("abcd", ((Map<?, ?>) response.resultValue().orElseThrow()).get("reply"));
					return true;
				})
				.expectComplete()
				.verify(VERIFY_TIMEOUT);
		}
		finally {
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void cancellingWhileWaitingForDemandReleasesWorkerWithoutTerminalSignals() throws Exception {
		CountDownLatch releaseServer = new CountDownLatch(1);
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try {
				for (int i = 0; i < 10; i++) {
					exchange.getResponseBody()
						.write(("data: " + chunk("c" + i) + "\n\n").getBytes(StandardCharsets.UTF_8));
					exchange.getResponseBody().flush();
				}
				awaitQuietly(releaseServer);
			}
			catch (IOException ignored) {
				// expected once the subscription is disposed
			}
			finally {
				exchange.close();
			}
		});
		try {
			AtomicReference<Throwable> error = new AtomicReference<>();
			AtomicInteger completions = new AtomicInteger();
			CountDownLatch firstChunk = new CountDownLatch(1);
			BaseSubscriber<GraphResponse<NodeOutput>> subscriber = new BaseSubscriber<>() {
				@Override
				protected void hookOnSubscribe(Subscription subscription) {
					subscription.request(1);
				}

				@Override
				protected void hookOnNext(GraphResponse<NodeOutput> value) {
					firstChunk.countDown();
				}

				@Override
				protected void hookOnError(Throwable throwable) {
					error.set(throwable);
				}

				@Override
				protected void hookOnComplete() {
					completions.incrementAndGet();
				}
			};
			streamingFlux(server, Duration.ofSeconds(30)).subscribe(subscriber);

			assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "the first chunk should reach the subscriber");
			assertTrue(awaitWorkerWaitingForDemand(3, TimeUnit.SECONDS),
					"the producer should park waiting for the next request");
			subscriber.dispose();

			assertTrue(awaitWorkerOutOfNode(3, TimeUnit.SECONDS), "dispose must release the parked worker");
			assertNull(error.get(), "cancellation must not surface a terminal error");
			assertEquals(0, completions.get(), "cancellation must not surface a terminal completion");
		}
		finally {
			releaseServer.countDown();
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void pausedConsumerResumingWithinTimeoutReceivesEveryChunk() throws Exception {
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			for (String text : new String[] { "a", "b", "c" }) {
				exchange.getResponseBody().write(("data: " + chunk(text) + "\n\n")
					.getBytes(StandardCharsets.UTF_8));
				exchange.getResponseBody().flush();
			}
			exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			exchange.close();
		});
		try {
			Flux<GraphResponse<NodeOutput>> flux = streamingFlux(server, Duration.ofSeconds(5));
			StepVerifier.create(flux, 1)
				.expectNextMatches(response -> "a".equals(streamingText(response)))
				.thenAwait(Duration.ofMillis(500))
				.thenRequest(3)
				.expectNextMatches(response -> "b".equals(streamingText(response)))
				.expectNextMatches(response -> "c".equals(streamingText(response)))
				.expectNextMatches(GraphResponse::isDone)
				.expectComplete()
				.verify(VERIFY_TIMEOUT);
		}
		finally {
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void stallingBeforeTheDoneResponseFailsExplicitly() throws Exception {
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody()
				.write(("data: " + chunk("only") + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			exchange.close();
		});
		try {
			Flux<GraphResponse<NodeOutput>> flux = streamingFlux(server, Duration.ofMillis(250));

			AtomicReference<Throwable> error = new AtomicReference<>();
			CountDownLatch terminal = new CountDownLatch(1);
			AtomicInteger items = new AtomicInteger();
			BaseSubscriber<GraphResponse<NodeOutput>> subscriber = new BaseSubscriber<>() {
				@Override
				protected void hookOnSubscribe(Subscription subscription) {
					subscription.request(1);
				}

				@Override
				protected void hookOnNext(GraphResponse<NodeOutput> value) {
					items.incrementAndGet();
				}

				@Override
				protected void hookOnError(Throwable throwable) {
					error.set(throwable);
					terminal.countDown();
				}

				@Override
				protected void hookOnComplete() {
					terminal.countDown();
				}
			};
			flux.subscribe(subscriber);

			assertTrue(terminal.await(5, TimeUnit.SECONDS),
					"a consumer that stops requesting before the done response must not hang");
			assertEquals(1, items.get());
			assertTrue(error.get() instanceof A2aBackpressureException, "got: " + error.get());
			assertTrue(awaitWorkerOutOfNode(3, TimeUnit.SECONDS), "the stalled worker must be released");
		}
		finally {
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void zeroTimeoutWaitsIndefinitelyForDemand() throws Exception {
		CountDownLatch releaseServer = new CountDownLatch(1);
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try {
				for (int i = 0; i < 5; i++) {
					exchange.getResponseBody()
						.write(("data: " + chunk("c" + i) + "\n\n").getBytes(StandardCharsets.UTF_8));
					exchange.getResponseBody().flush();
				}
				awaitQuietly(releaseServer);
			}
			catch (IOException ignored) {
				// expected once the subscription is disposed
			}
			finally {
				exchange.close();
			}
		});
		try {
			AtomicReference<Throwable> error = new AtomicReference<>();
			AtomicInteger completions = new AtomicInteger();
			CountDownLatch firstChunk = new CountDownLatch(1);
			BaseSubscriber<GraphResponse<NodeOutput>> subscriber = new BaseSubscriber<>() {
				@Override
				protected void hookOnSubscribe(Subscription subscription) {
					subscription.request(1);
				}

				@Override
				protected void hookOnNext(GraphResponse<NodeOutput> value) {
					firstChunk.countDown();
				}

				@Override
				protected void hookOnError(Throwable throwable) {
					error.set(throwable);
				}

				@Override
				protected void hookOnComplete() {
					completions.incrementAndGet();
				}
			};
			streamingFlux(server, Duration.ZERO).subscribe(subscriber);

			assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "the first chunk should reach the subscriber");
			Thread.sleep(600);

			assertNull(error.get(), "ZERO disables the timeout instead of failing immediately");
			assertEquals(0, completions.get());

			subscriber.dispose();
			assertTrue(awaitWorkerOutOfNode(3, TimeUnit.SECONDS), "dispose must release the waiting worker");
		}
		finally {
			releaseServer.countDown();
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void demandTimeoutConfigIsValidated() {
		A2aNodeActionWithConfig node = new A2aNodeActionWithConfig(agentCard("http://127.0.0.1:1/"), "remote-agent",
				false, "reply", "instruction", true);
		assertThrows(NullPointerException.class, () -> node.streamDemandTimeout(null));
		assertThrows(IllegalArgumentException.class, () -> node.streamDemandTimeout(Duration.ofMillis(-1)));
		// ZERO explicitly disables the timeout (legacy wait-forever behaviour)
		node.streamDemandTimeout(Duration.ZERO);
	}

	private static String chunk(String text) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"artifact-update\",\"artifact\":"
				+ "{\"parts\":[{\"text\":\"" + text + "\"}]}}}";
	}

	@SuppressWarnings("unchecked")
	private static Flux<GraphResponse<NodeOutput>> streamingFlux(HttpServer server, Duration demandTimeout)
			throws Exception {
		A2aNodeActionWithConfig node = new A2aNodeActionWithConfig(agentCard(baseUrl(server)), "remote-agent", false,
				"reply", "instruction", true).streamDemandTimeout(demandTimeout);
		Map<String, Object> result = node.apply(new OverAllState(),
				RunnableConfig.builder().threadId("backpressure-test").build());
		return (Flux<GraphResponse<NodeOutput>>) result.get("reply");
	}

	private static String streamingText(GraphResponse<NodeOutput> response) {
		assertTrue(!response.isDone());
		StreamingOutput<?> output = (StreamingOutput<?>) response.getOutput().getNow(null);
		return output.text();
	}

	private static boolean awaitWorkerOutOfNode(long timeout, TimeUnit unit) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (System.nanoTime() < deadline) {
			if (!workerInNode()) {
				return true;
			}
			Thread.sleep(20);
		}
		return !workerInNode();
	}

	private static boolean awaitWorkerWaitingForDemand(long timeout, TimeUnit unit) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (System.nanoTime() < deadline) {
			if (workerWaitingForDemand()) {
				return true;
			}
			Thread.sleep(20);
		}
		return workerWaitingForDemand();
	}

	private static boolean workerWaitingForDemand() {
		Set<Thread> threads = new HashSet<>(Thread.getAllStackTraces().keySet());
		String emissionGate = A2aNodeActionWithConfig.class.getName() + "$StreamEmission";
		for (Thread thread : threads) {
			if (!thread.getName().startsWith("boundedElastic")) {
				continue;
			}
			for (StackTraceElement frame : thread.getStackTrace()) {
				if (emissionGate.equals(frame.getClassName())) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean workerInNode() {
		Set<Thread> threads = new HashSet<>(Thread.getAllStackTraces().keySet());
		for (Thread thread : threads) {
			if (!thread.getName().startsWith("boundedElastic")) {
				continue;
			}
			for (StackTraceElement frame : thread.getStackTrace()) {
				if (A2aNodeActionWithConfig.class.getName().equals(frame.getClassName())) {
					return true;
				}
			}
		}
		return false;
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static HttpServer startServer(ExecutorService executor,
			com.sun.net.httpserver.HttpHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", handler);
		server.setExecutor(executor);
		server.start();
		return server;
	}

	private static String baseUrl(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
	}

	private static AgentCardWrapper agentCard(String url) {
		AgentCard agentCard = mock(AgentCard.class);
		when(agentCard.name()).thenReturn("test-agent");
		when(agentCard.url()).thenReturn(url);
		return new AgentCardWrapper(agentCard);
	}

}
