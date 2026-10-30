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

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cancellation of a streaming A2A call must reach the transport. Cancelling the
 * subscription only stops event delivery: the worker can stay blocked in a synchronous
 * HTTP read until the remote peer sends another byte or closes, which keeps the
 * connection and the bulkhead thread alive for as long as the peer stays quiet.
 */
class A2aStreamCancellationTest {

	@Test
	void cancelReleasesWorkerBlockedReadingSilentEventStream() throws Exception {
		CountDownLatch replyFlushed = new CountDownLatch(1);
		CountDownLatch releaseServer = new CountDownLatch(1);
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			// A real SSE peer may send a comment and then stay quiet for a long time.
			exchange.getResponseBody().write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			replyFlushed.countDown();
			awaitQuietly(releaseServer);
			exchange.close();
		});
		try {
			Flux<GraphResponse<NodeOutput>> flux = streamingFlux(server);
			Disposable subscription = flux.subscribe(response -> {
			}, error -> {
			}, () -> {
			});

			assertTrue(replyFlushed.await(5, TimeUnit.SECONDS), "test server never started replying");			assertTrue(awaitWorkerInTransport(5, TimeUnit.SECONDS), "worker should be blocked in the HTTP read");

			subscription.dispose();

			assertTrue(awaitWorkerOutOfTransport(2, TimeUnit.SECONDS),
					"cancel must release the blocking HTTP read instead of waiting for the remote peer");
		}
		finally {
			releaseServer.countDown();
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void cancelWhileWaitingForResponseHeadersReleasesWorker() throws Exception {
		CountDownLatch requestReceived = new CountDownLatch(1);
		CountDownLatch releaseServer = new CountDownLatch(1);
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			requestReceived.countDown();
			awaitQuietly(releaseServer);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});
		try {
			Flux<GraphResponse<NodeOutput>> flux = streamingFlux(server);
			Disposable subscription = flux.subscribe(response -> {
			}, error -> {
			}, () -> {
			});

			assertTrue(requestReceived.await(5, TimeUnit.SECONDS), "test server never received the request");
			assertTrue(awaitWorkerInTransport(5, TimeUnit.SECONDS), "worker should be waiting for response headers");

			subscription.dispose();

			assertTrue(awaitWorkerOutOfTransport(2, TimeUnit.SECONDS),
					"cancel before the response headers must abort the pending exchange");
		}
		finally {
			releaseServer.countDown();
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void cancelAfterAChunkDoesNotEmitErrorTextOrDone() throws Exception {
		CountDownLatch releaseServer = new CountDownLatch(1);
		ExecutorService serverExecutor = Executors.newCachedThreadPool();
		HttpServer server = startServer(serverExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write(("data: " + chunk("first") + "\n\n").getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			awaitQuietly(releaseServer);
			exchange.close();
		});
		try {
			List<GraphResponse<NodeOutput>> emitted = new CopyOnWriteArrayList<>();
			Flux<GraphResponse<NodeOutput>> flux = streamingFlux(server);
			Disposable subscription = flux.subscribe(emitted::add, error -> {
			}, () -> {
			});

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (emitted.isEmpty() && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			assertEquals(1, emitted.size(), "the first chunk should reach the subscriber");

			subscription.dispose();
			Thread.sleep(200);

			assertEquals(1, emitted.size(), "cancel must not add an error chunk or a done response");
			assertFalse(emitted.get(0).isDone());
		}
		finally {
			releaseServer.countDown();
			server.stop(0);
			serverExecutor.shutdownNow();
		}
	}

	@Test
	void cancellingOneStreamDoesNotAffectAnotherRun() throws Exception {
		CountDownLatch silentFlushed = new CountDownLatch(1);
		CountDownLatch releaseServer = new CountDownLatch(1);
		ExecutorService silentExecutor = Executors.newCachedThreadPool();
		HttpServer silent = startServer(silentExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			silentFlushed.countDown();
			awaitQuietly(releaseServer);
			exchange.close();
		});
		ExecutorService healthyExecutor = Executors.newCachedThreadPool();
		HttpServer healthy = startServer(healthyExecutor, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody()
				.write(("data: " + chunk("done") + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			exchange.close();
		});
		try {
			Disposable silentSubscription = streamingFlux(silent).subscribe(response -> {
			}, error -> {
			}, () -> {
			});
			assertTrue(silentFlushed.await(5, TimeUnit.SECONDS), "test server never started replying");
			assertTrue(awaitWorkerInTransport(5, TimeUnit.SECONDS), "worker should be blocked in the HTTP read");

			List<GraphResponse<NodeOutput>> emitted = new CopyOnWriteArrayList<>();
			streamingFlux(healthy).subscribe(emitted::add, error -> {
			}, () -> {
			});
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!isDone(emitted) && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			assertTrue(isDone(emitted), "the unrelated run should complete while the other one stays open");

			silentSubscription.dispose();

			assertTrue(awaitWorkerOutOfTransport(2, TimeUnit.SECONDS),
					"cancelling one run must not leave its worker blocked");
			assertEquals(2, emitted.size(), "the unrelated run keeps its chunk and its done response");
		}
		finally {
			releaseServer.countDown();
			silent.stop(0);
			silentExecutor.shutdownNow();
			healthy.stop(0);
			healthyExecutor.shutdownNow();
		}
	}

	private static boolean isDone(List<GraphResponse<NodeOutput>> emitted) {
		return !emitted.isEmpty() && emitted.get(emitted.size() - 1).isDone();
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@SuppressWarnings("unchecked")
	private static Flux<GraphResponse<NodeOutput>> streamingFlux(HttpServer server) throws Exception {
		A2aNodeActionWithConfig node = new A2aNodeActionWithConfig(agentCard(baseUrl(server)), "remote-agent", false,
				"reply", "instruction", true);
		Map<String, Object> result = node.apply(new OverAllState(),
				RunnableConfig.builder().threadId("cancel-test").build());
		return (Flux<GraphResponse<NodeOutput>>) result.get("reply");
	}

	private static String chunk(String text) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"artifact-update\",\"artifact\":"
				+ "{\"parts\":[{\"text\":\"" + text + "\"}]}}}";
	}

	private static boolean awaitWorkerInTransport(long timeout, TimeUnit unit) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (System.nanoTime() < deadline) {
			if (workerBlockedInTransport()) {
				return true;
			}
			Thread.sleep(20);
		}
		return workerBlockedInTransport();
	}

	private static boolean awaitWorkerOutOfTransport(long timeout, TimeUnit unit) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (System.nanoTime() < deadline) {
			if (!workerBlockedInTransport()) {
				return true;
			}
			Thread.sleep(20);
		}
		return !workerBlockedInTransport();
	}

	private static boolean workerBlockedInTransport() {
		for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
			if (!entry.getKey().getName().startsWith("boundedElastic")) {
				continue;
			}
			boolean inNode = false;
			boolean inHttp = false;
			for (StackTraceElement frame : entry.getValue()) {
				if (A2aNodeActionWithConfig.class.getName().equals(frame.getClassName())) {
					inNode = true;
				}
				if (frame.getClassName().startsWith("org.apache.hc.")) {
					inHttp = true;
				}
			}
			if (inNode && inHttp) {
				return true;
			}
		}
		return false;
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
