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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.checkpoint.savers.VersionedMemorySaver;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent executions must load and update each checkpoint thread in subscription
 * order, while independent checkpoint namespaces remain concurrent.
 */
@Timeout(30)
class StateGraphConcurrentExecutionTest {

	@Test
	void sequentialRequestsPreserveBothMessageUpdates() throws Exception {
		final MemorySaver saver = new MemorySaver();
		final CompiledGraph graph = compile(saver, (state, config) -> completedFuture(
				Map.of("messages", List.of(state.<String>value("request").orElseThrow()))));
		final RunnableConfig config = config("shared");

		invoke(graph, "seed", config);
		assertEquals(List.of("seed", "A"), messages(invoke(graph, "A", config)));
		assertEquals(List.of("seed", "A", "B"), messages(invoke(graph, "B", config)));
		assertEquals(List.of("seed", "A", "B"), latestMessages(saver, config));
	}

	@Test
	void sameThreadExecutesThreeRequestsInFifoOrderWithoutLosingUpdates() throws Exception {
		assertFifoUpdates(new MemorySaver());
	}

	@Test
	void versionedSaverPreservesAllQueuedUpdates() throws Exception {
		assertFifoUpdates(VersionedMemorySaver.builder().build());
	}

	@Test
	void compiledGraphsSharingTheSameSaverAndThreadUseOneQueue() throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(new MemorySaver())) {
			final CompiledGraph secondGraph = compile(calls.saver, calls.action());
			final RunnableConfig config = config("shared");
			invoke(calls.graph, "seed", config);

			final CompletableFuture<NodeOutput> requestA = calls.submit(calls.graph, "A", config);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit(secondGraph, "B", config);
			assertEquals(List.of("A"), calls.enteredRequests());

			invocationA.complete();
			assertEquals(List.of("seed", "A"), messages(result(requestA)));
			final Invocation invocationB = calls.awaitInvocation("B");
			assertEquals(List.of("seed", "A"), invocationB.initialMessages());
			invocationB.complete();
			assertEquals(List.of("seed", "A", "B"), messages(result(requestB)));
			assertEquals(List.of("seed", "A", "B"), latestMessages(calls.saver, config));
		}
	}

	@Test
	void differentThreadsStayConcurrent() throws Exception {
		assertIndependent(config("thread-A"), config("thread-B"));
	}

	@Test
	void differentAppsWithTheSameThreadAndUserStayConcurrent() throws Exception {
		assertIndependent(config("app-A", "user", "shared"), config("app-B", "user", "shared"));
	}

	@Test
	void differentUsersWithTheSameThreadAndAppStayConcurrent() throws Exception {
		assertIndependent(config("app", "user-A", "shared"), config("app", "user-B", "shared"));
	}

	@Test
	void distinctSaverInstancesDoNotShareAnExecutionQueue() throws Exception {
		try (final ControlledCalls first = new ControlledCalls(new MemorySaver());
				final ControlledCalls second = new ControlledCalls(new MemorySaver())) {
			final RunnableConfig config = config("shared");
			final CompletableFuture<NodeOutput> requestA = first.submit("A", config);
			final Invocation invocationA = first.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = second.submit("B", config);
			final Invocation invocationB = second.awaitInvocation("B");

			invocationA.complete();
			invocationB.complete();
			assertEquals(List.of("A"), messages(result(requestA)));
			assertEquals(List.of("B"), messages(result(requestB)));
		}
	}

	@Test
	void graphsWithoutASaverKeepSameThreadExecutionsConcurrent() throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(null)) {
			final RunnableConfig config = config("shared");
			final CompletableFuture<NodeOutput> requestA = calls.submit("A", config);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit("B", config);
			final Invocation invocationB = calls.awaitInvocation("B");

			invocationA.complete();
			invocationB.complete();
			assertEquals(List.of("A"), messages(result(requestA)));
			assertEquals(List.of("B"), messages(result(requestB)));
		}
	}

	@Test
	void cancellingAQueuedRequestSkipsItsNodeAndKeepsTheNextRequest() throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(new MemorySaver())) {
			final RunnableConfig config = config("shared");
			invoke(calls.graph, "seed", config);
			final CompletableFuture<NodeOutput> requestA = calls.submit("A", config);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit("B", config);
			final CompletableFuture<NodeOutput> requestC = calls.submit("C", config);

			assertTrue(requestB.cancel(true));
			assertEquals(List.of("A"), calls.enteredRequests());
			invocationA.complete();
			assertEquals(List.of("seed", "A"), messages(result(requestA)));
			final Invocation invocationC = calls.awaitInvocation("C");
			assertEquals(List.of("seed", "A"), invocationC.initialMessages());
			invocationC.complete();
			assertEquals(List.of("seed", "A", "C"), messages(result(requestC)));
			assertEquals(List.of("A", "C"), calls.enteredRequests());
			assertEquals(List.of("seed", "A", "C"), latestMessages(calls.saver, config));
		}
	}

	@Test
	void cancellingTheActiveRequestReleasesItsSlotAndIgnoresLateCompletion() throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(new MemorySaver())) {
			final RunnableConfig config = config("shared");
			invoke(calls.graph, "seed", config);
			final CompletableFuture<NodeOutput> requestA = calls.submit("A", config);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit("B", config);

			assertTrue(requestA.cancel(true));
			final Invocation invocationB = calls.awaitInvocation("B");
			assertEquals(List.of("seed"), invocationB.initialMessages());
			invocationB.complete();
			assertEquals(List.of("seed", "B"), messages(result(requestB)));
			// The node future deliberately ignores cancellation, like remote work that
			// may finish after the caller has already disconnected.
			invocationA.complete();
			assertEquals(List.of("seed", "B"), latestMessages(calls.saver, config));
		}
	}

	@Test
	void nodeFailureReleasesTheQueueForTheNextRequest() throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(new MemorySaver())) {
			final RunnableConfig config = config("shared");
			invoke(calls.graph, "seed", config);
			final CompletableFuture<NodeOutput> requestA = calls.submit("A", config);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit("B", config);
			final IllegalStateException nodeFailure = new IllegalStateException("node failed");

			invocationA.completion().completeExceptionally(nodeFailure);
			final ExecutionException failure = assertThrows(ExecutionException.class,
					() -> requestA.get(10, TimeUnit.SECONDS));
			assertTrue(hasCause(failure, nodeFailure));
			final Invocation invocationB = calls.awaitInvocation("B");
			assertEquals(List.of("seed"), invocationB.initialMessages());
			invocationB.complete();
			assertEquals(List.of("seed", "B"), messages(result(requestB)));
			assertEquals(List.of("seed", "B"), latestMessages(calls.saver, config));
		}
	}

	@Test
	void eachSubscriptionToTheSameColdStreamGetsFreshCheckpointState() throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(new MemorySaver())) {
			final RunnableConfig config = config("shared");
			invoke(calls.graph, "seed", config);
			final Flux<NodeOutput> stream = calls.graph.stream(Map.of("request", "repeat"), config);
			assertEquals(List.of(), calls.enteredRequests());
			final CompletableFuture<NodeOutput> first = calls.subscribe(stream);
			final Invocation firstInvocation = calls.awaitInvocation("repeat");
			final CompletableFuture<NodeOutput> second = calls.subscribe(stream);
			assertEquals(List.of("repeat"), calls.enteredRequests());

			firstInvocation.complete();
			assertEquals(List.of("seed", "repeat"), messages(result(first)));
			final Invocation secondInvocation = calls.awaitInvocation("repeat");
			assertEquals(List.of("seed", "repeat"), secondInvocation.initialMessages());
			secondInvocation.complete();
			assertEquals(List.of("seed", "repeat", "repeat"), messages(result(second)));
			assertEquals(List.of("seed", "repeat", "repeat"), latestMessages(calls.saver, config));
		}
	}

	@Test
	void omittedThreadIdAndExplicitDefaultThreadIdUseTheSameQueue() throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(new MemorySaver())) {
			final RunnableConfig implicitDefault = RunnableConfig.builder().build();
			final RunnableConfig explicitDefault = config(BaseCheckpointSaver.THREAD_ID_DEFAULT);
			invoke(calls.graph, "seed", implicitDefault);
			final CompletableFuture<NodeOutput> requestA = calls.submit("A", implicitDefault);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit("B", explicitDefault);
			assertEquals(List.of("A"), calls.enteredRequests());

			invocationA.complete();
			assertEquals(List.of("seed", "A"), messages(result(requestA)));
			final Invocation invocationB = calls.awaitInvocation("B");
			assertEquals(List.of("seed", "A"), invocationB.initialMessages());
			invocationB.complete();
			assertEquals(List.of("seed", "A", "B"), messages(result(requestB)));
			assertEquals(List.of("seed", "A", "B"), latestMessages(calls.saver, implicitDefault));
		}
	}

	private static void assertFifoUpdates(BaseCheckpointSaver saver) throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(saver)) {
			final RunnableConfig config = config("shared");
			invoke(calls.graph, "seed", config);
			final CompletableFuture<NodeOutput> requestA = calls.submit("A", config);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit("B", config);
			final CompletableFuture<NodeOutput> requestC = calls.submit("C", config);
			assertEquals(List.of("A"), calls.enteredRequests());
			// Queued requests must not load state or write a START checkpoint early.
			assertEquals("A", saver.get(config).orElseThrow().getState().get("request"));

			invocationA.complete();
			assertEquals(List.of("seed", "A"), messages(result(requestA)));
			final Invocation invocationB = calls.awaitInvocation("B");
			assertEquals(List.of("seed", "A"), invocationB.initialMessages());
			assertEquals(List.of("A", "B"), calls.enteredRequests());

			invocationB.complete();
			assertEquals(List.of("seed", "A", "B"), messages(result(requestB)));
			final Invocation invocationC = calls.awaitInvocation("C");
			assertEquals(List.of("seed", "A", "B"), invocationC.initialMessages());
			invocationC.complete();
			assertEquals(List.of("seed", "A", "B", "C"), messages(result(requestC)));
			assertEquals(List.of("A", "B", "C"), calls.enteredRequests());
			assertEquals(List.of("seed", "A", "B", "C"), latestMessages(saver, config));
			assertEquals(List.of("seed", "A", "B", "C"),
					calls.graph.getInitialState(Map.of(), config).get("messages"));
		}
	}

	private static void assertIndependent(RunnableConfig configA, RunnableConfig configB) throws Exception {
		try (final ControlledCalls calls = new ControlledCalls(new MemorySaver())) {
			invoke(calls.graph, "seed", configA);
			invoke(calls.graph, "seed", configB);
			final CompletableFuture<NodeOutput> requestA = calls.submit("A", configA);
			final Invocation invocationA = calls.awaitInvocation("A");
			final CompletableFuture<NodeOutput> requestB = calls.submit("B", configB);
			// Both nodes must start while neither has been allowed to finish.
			final Invocation invocationB = calls.awaitInvocation("B");
			assertEquals(List.of("seed"), invocationA.initialMessages());
			assertEquals(List.of("seed"), invocationB.initialMessages());

			invocationA.complete();
			invocationB.complete();
			assertEquals(List.of("seed", "A"), messages(result(requestA)));
			assertEquals(List.of("seed", "B"), messages(result(requestB)));
			assertEquals(List.of("seed", "A"), latestMessages(calls.saver, configA));
			assertEquals(List.of("seed", "B"), latestMessages(calls.saver, configB));
		}
	}

	private static CompiledGraph compile(BaseCheckpointSaver saver, AsyncNodeActionWithConfig action) throws Exception {
		final StateGraph workflow = new StateGraph(() -> Map.<String, KeyStrategy>of("messages", new AppendStrategy(),
				"request", new ReplaceStrategy())).addNode("append", action)
			.addEdge(START, "append")
			.addEdge("append", END);
		final CompileConfig.Builder config = CompileConfig.builder();
		if (saver != null) {
			config.saverConfig(SaverConfig.builder().register(saver).build());
		}
		else {
			config.saverConfig(SaverConfig.builder().build());
		}
		return workflow.compile(config.build());
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	private static RunnableConfig config(String appName, String userId, String threadId) {
		return RunnableConfig.builder()
			.threadId(threadId)
			.addMetadata(RunnableConfig.APP_NAME_METADATA_KEY, appName)
			.addMetadata(RunnableConfig.USER_ID_METADATA_KEY, userId)
			.build();
	}

	private static OverAllState invoke(CompiledGraph graph, String request, RunnableConfig config) {
		return graph.invoke(Map.of("request", request), config).orElseThrow();
	}

	private static OverAllState result(CompletableFuture<NodeOutput> request) throws Exception {
		return request.get(10, TimeUnit.SECONDS).state();
	}

	private static List<String> messages(OverAllState state) {
		return List.copyOf(state.<List<String>>value("messages").orElseGet(List::of));
	}

	private static Object latestMessages(BaseCheckpointSaver saver, RunnableConfig config) {
		return saver.get(config).orElseThrow().getState().get("messages");
	}

	private static boolean hasCause(Throwable failure, Throwable expected) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause == expected) {
				return true;
			}
		}
		return false;
	}

	private record Invocation(String request, List<String> initialMessages,
			CompletableFuture<Map<String, Object>> completion) {

		private void complete() {
			assertTrue(completion.complete(Map.of("messages", List.of(request))));
		}

	}

	private static final class ControlledCalls implements AutoCloseable {

		private final BaseCheckpointSaver saver;

		private final CompiledGraph graph;

		private final BlockingQueue<Invocation> pendingInvocations = new LinkedBlockingQueue<>();

		private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

		private final List<CompletableFuture<NodeOutput>> subscriptions = new CopyOnWriteArrayList<>();

		private ControlledCalls(BaseCheckpointSaver saver) throws Exception {
			this.saver = saver;
			this.graph = compile(saver, action());
		}

		private AsyncNodeActionWithConfig action() {
			return (state, config) -> {
				final String request = state.<String>value("request").orElseThrow();
				if ("seed".equals(request)) {
					return completedFuture(Map.of("messages", List.of("seed")));
				}
				final CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>() {
					@Override
					public boolean cancel(boolean mayInterruptIfRunning) {
						return false;
					}
				};
				final Invocation invocation = new Invocation(request, messages(state), completion);
				invocations.add(invocation);
				pendingInvocations.add(invocation);
				return completion;
			};
		}

		private CompletableFuture<NodeOutput> submit(String request, RunnableConfig config) {
			return submit(graph, request, config);
		}

		private CompletableFuture<NodeOutput> submit(CompiledGraph compiledGraph, String request, RunnableConfig config) {
			return subscribe(compiledGraph.stream(Map.of("request", request), config));
		}

		private CompletableFuture<NodeOutput> subscribe(Flux<NodeOutput> stream) {
			final CompletableFuture<NodeOutput> subscription = stream.last().toFuture();
			subscriptions.add(subscription);
			return subscription;
		}

		private Invocation awaitInvocation(String request) throws InterruptedException {
			final Invocation invocation = pendingInvocations.poll(10, TimeUnit.SECONDS);
			assertNotNull(invocation, "Request " + request + " must reach its node");
			assertEquals(request, invocation.request());
			return invocation;
		}

		private List<String> enteredRequests() {
			return invocations.stream().map(Invocation::request).toList();
		}

		@Override
		public void close() {
			subscriptions.forEach(subscription -> subscription.cancel(true));
			invocations.forEach(invocation -> invocation.completion().complete(Map.of()));
		}

	}

}
