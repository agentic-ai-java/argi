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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.tool.ToolCancelledException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every error exit of {@link AgentToolNode} records a structured
 * {@link ToolCallResponse#FAILURE_KIND_METADATA_KEY failure kind} and, where an exception
 * is available, its {@link ToolCallResponse#EXCEPTION_TYPE_METADATA_KEY class name}, so
 * that interceptors can distinguish a timeout from a validation error without parsing
 * messages.
 */
@DisplayName("AgentToolNode structured failure metadata")
class AgentToolNodeFailureMetadataTest {

	private static final String CALL_ID = "call_1";

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("async tool timeout is reported as failureKind=timeout with TimeoutException")
	void asyncTimeoutCarriesTimeoutKind() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Tool slow = asyncTool("slowTool", Duration.ofMillis(50), () -> CompletableFuture.supplyAsync(() -> {
				sleepQuietly(2000);
				return "late";
			}, executor));

			ToolCallResponse response = runSingleTool(slow, "slowTool", false);

			assertThat(response.isError()).isTrue();
			assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_TIMEOUT);
			assertThat(response.getExceptionType()).contains(TimeoutException.class.getName());
			assertThat(response.getMetadata()).containsEntry("error", true);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("cancelled async tool is reported as failureKind=cancelled")
	void asyncCancellationCarriesCancelledKind() throws Exception {
		Tool cancelled = asyncTool("cancelTool", Duration.ofSeconds(5), () -> {
			CompletableFuture<String> future = new CompletableFuture<>();
			future.cancel(true);
			return future;
		});

		ToolCallResponse response = runSingleTool(cancelled, "cancelTool", false);

		assertThat(response.isError()).isTrue();
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_CANCELLED);
		assertThat(response.getExceptionType()).contains(CancellationException.class.getName());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("async tool failing with ToolCancelledException is reported as cancelled")
	void asyncToolCancelledExceptionCarriesCancelledKind() throws Exception {
		Tool cancelling = asyncTool("selfCancelTool", Duration.ofSeconds(5),
				() -> CompletableFuture.failedFuture(new ToolCancelledException("stop")));

		ToolCallResponse response = runSingleTool(cancelling, "selfCancelTool", false);

		assertThat(response.isError()).isTrue();
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_CANCELLED);
		assertThat(response.getExceptionType()).contains(ToolCancelledException.class.getName());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("sync tool throwing a plain exception is reported as execution with its class name")
	void syncExceptionCarriesExecutionKindAndType() throws Exception {
		Tool failing = syncTool("failingTool", () -> {
			throw new IllegalStateException("backend rejected the request");
		});

		ToolCallResponse response = runSingleTool(failing, "failingTool", false);

		assertThat(response.isError()).isTrue();
		assertThat(response.getResult()).contains("backend rejected the request");
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_EXECUTION);
		assertThat(response.getExceptionType()).contains(IllegalStateException.class.getName());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("CompletionException is marked as an error and reports the wrapped exception type")
	void toolExecutionExceptionReportsErrorAndWrappedType() throws Exception {
		ToolDefinition definition = definition("wrappedTool");
		Tool wrapped = syncTool(definition, () -> {
			throw new CompletionException(new IllegalArgumentException("bad argument"));
		});

		ToolCallResponse response = runSingleTool(wrapped, "wrappedTool", false);

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.ERROR_STATUS);
		assertThat(response.isError()).isTrue();
		assertThat(response.getResult()).contains("bad argument");
		// Metadata identifies the exception thrown by the tool, not its wrapper.
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_EXECUTION);
		assertThat(response.getExceptionType()).contains(IllegalArgumentException.class.getName());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("async failed future is marked as an error with the same status contract as the sync path")
	void asyncFailedFutureReportsErrorAndWrappedType() throws Exception {
		Tool wrapped = asyncTool("asyncWrappedTool", Duration.ofSeconds(5),
				() -> CompletableFuture.failedFuture(
						new CompletionException(new IllegalArgumentException("bad async argument"))));

		ToolCallResponse response = runSingleTool(wrapped, "asyncWrappedTool", false);

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.ERROR_STATUS);
		assertThat(response.isError()).isTrue();
		assertThat(response.getResult()).contains("bad async argument");
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_EXECUTION);
		assertThat(response.getExceptionType()).contains(IllegalArgumentException.class.getName());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("unresolved tool name is reported as failureKind=unresolved without exception type")
	void unresolvedToolCarriesUnresolvedKind() throws Exception {
		Tool other = syncTool("otherTool", () -> "ok");

		ToolCallResponse response = runSingleTool(other, "missingTool", false);

		assertThat(response.isError()).isTrue();
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_UNRESOLVED);
		assertThat(response.getExceptionType()).isEmpty();
		assertThat(response.getMetadata()).containsEntry("unresolvedToolName", "missingTool");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("successful tool call carries no failure metadata")
	void successCarriesNoFailureMetadata() throws Exception {
		Tool ok = syncTool("okTool", () -> "fine");

		ToolCallResponse response = runSingleTool(ok, "okTool", false);

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.SUCCESS_STATUS);
		assertThat(response.getFailureKind()).isEmpty();
		assertThat(response.getExceptionType()).isEmpty();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("parallel mode: sync tool exception is reported as execution with its class name")
	void parallelSyncExceptionCarriesExecutionKind() throws Exception {
		Tool failing = syncTool("parallelFailingTool", () -> {
			throw new IllegalStateException("boom");
		});

		ToolCallResponse response = runSingleTool(failing, "parallelFailingTool", true);

		assertThat(response.isError()).isTrue();
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_EXECUTION);
		assertThat(response.getExceptionType()).contains(IllegalStateException.class.getName());
	}

	@Test
	@DisplayName("failureMetadata maps throwable types to kinds and unwraps CompletionException")
	void failureMetadataMapping() {
		assertThat(AgentToolNode.failureMetadata(new TimeoutException()))
			.containsEntry(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_TIMEOUT)
			.containsEntry(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, TimeoutException.class.getName());
		assertThat(AgentToolNode.failureMetadata(new InterruptedException()))
			.containsEntry(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_CANCELLED);
		assertThat(AgentToolNode.failureMetadata(new CancellationException()))
			.containsEntry(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_CANCELLED);
		assertThat(AgentToolNode.failureMetadata(new RuntimeException("x")))
			.containsEntry(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_EXECUTION)
			.containsEntry(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, RuntimeException.class.getName());

		CompletionException wrapped = new CompletionException(new IllegalArgumentException("inner"));
		assertThat(AgentToolNode.failureMetadata(wrapped))
			.containsEntry(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_EXECUTION)
			.containsEntry(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, IllegalArgumentException.class.getName());
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/**
	 * Runs a single tool call through the node and captures the {@link ToolCallResponse}
	 * as seen by a tool interceptor, which is the only place the response metadata is
	 * observable from.
	 */
	private ToolCallResponse runSingleTool(Tool callback, String requestedToolName, boolean parallel) throws Exception {
		AtomicReference<ToolCallResponse> captured = new AtomicReference<>();
		ToolInterceptor capture = new ToolInterceptor() {
			@Override
			public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
				ToolCallResponse response = handler.call(request);
				captured.set(response);
				return response;
			}

			@Override
			public String getName() {
				return "capture";
			}
		};

		AgentToolNode node = AgentToolNode.builder()
			.agentName("test-agent")
			.toolRegistry(new ToolRegistry(List.of(callback)))
			.parallelToolExecution(parallel)
			.toolExecutionTimeout(Duration.ofMillis(150))
			.throwOnToolError(false)
			.build();
		node.setToolInterceptors(List.of(capture));

		ChatMessage assistantMessage = ChatMessage.assistant("",
				List.of(new ToolCall(CALL_ID, requestedToolName, "{}")));
		OverAllState state = stateWithMessages(assistantMessage);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			RunnableConfig config = RunnableConfig.builder()
				.threadId("test-thread")
				.addParallelNodeExecutor("_AGENT_TOOL_", executor)
				.build();
			node.apply(state, config);
		}
		finally {
			executor.shutdownNow();
		}

		ToolCallResponse response = captured.get();
		assertThat(response).as("response captured by interceptor").isNotNull();
		assertThat(response.getToolCallId()).isEqualTo(CALL_ID);
		return response;
	}

	private static OverAllState stateWithMessages(ChatMessage... messages) {
		Map<String, Object> data = new HashMap<>();
		data.put("messages", new ArrayList<>(List.of(messages)));
		return new OverAllState(data);
	}

	private static ToolDefinition definition(String name) {
		return new ToolDefinition(name, name, "{}");
	}

	private static Tool syncTool(String name, ThrowingSupplier body) {
		return syncTool(definition(name), body);
	}

	private static Tool syncTool(ToolDefinition definition, ThrowingSupplier body) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return definition;
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text(body.get()));
			}
		};
	}

	private static Tool asyncTool(String name, Duration ignoredTimeout, FutureSupplier body) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return AgentToolNodeFailureMetadataTest.definition(name);
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				CompletableFuture<String> source = body.get();
				CompletableFuture<ToolResult> output = new CompletableFuture<>();
				source.whenComplete((value, error) -> {
					if (source.isCancelled()) {
						output.cancel(true);
					}
					else if (error != null) {
						output.completeExceptionally(error);
					}
					else {
						output.complete(ToolResult.text(value));
					}
				});
				output.whenComplete((value, error) -> {
					if (output.isCancelled()) {
						source.cancel(true);
					}
				});
				return output;
			}
		};
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	private interface ThrowingSupplier {

		String get();

	}

	@FunctionalInterface
	private interface FutureSupplier {

		CompletableFuture<String> get();

	}

}
