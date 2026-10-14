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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.tool.AsyncToolCallback;
import io.github.agentic.ai.graph.agent.tool.ToolCancelledException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;

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
 * {@link ToolCallResponse#FAILURE_KIND_METADATA_KEY failure kind} and, where an
 * exception is available, its {@link ToolCallResponse#EXCEPTION_TYPE_METADATA_KEY class
 * name}, so that interceptors can distinguish a timeout from a validation error without
 * parsing messages.
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
			AsyncToolCallback slow = asyncTool("slowTool", Duration.ofMillis(50),
					() -> CompletableFuture.supplyAsync(() -> {
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
		AsyncToolCallback cancelled = asyncTool("cancelTool", Duration.ofSeconds(5), () -> {
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
		AsyncToolCallback cancelling = asyncTool("selfCancelTool", Duration.ofSeconds(5),
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
		ToolCallback failing = syncTool("failingTool", () -> {
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
	@DisplayName("ToolExecutionException is reported as an error and carries the wrapped exception type")
	void toolExecutionExceptionIsReportedAsErrorAndCarriesWrappedType() throws Exception {
		ToolDefinition definition = definition("wrappedTool");
		ToolCallback wrapped = syncTool(definition, () -> {
			throw new ToolExecutionException(definition, new IllegalArgumentException("bad argument"));
		});

		ToolCallResponse response = runSingleTool(wrapped, "wrappedTool", false);

		// The processed message is still delivered as the result content, but the
		// response is now explicitly an error instead of shipping without a status.
		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.ERROR_STATUS);
		assertThat(response.isError()).isTrue();
		assertThat(response.getResult()).contains("bad argument");
		// Structured metadata points at the exception thrown by the tool itself.
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_EXECUTION);
		assertThat(response.getExceptionType()).contains(IllegalArgumentException.class.getName());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("async ToolExecutionException is reported as an error with the same status contract")
	void asyncToolExecutionExceptionIsReportedAsError() throws Exception {
		ToolDefinition definition = definition("asyncWrappedTool");
		ToolCallback wrapped = asyncTool("asyncWrappedTool", Duration.ofSeconds(5),
				() -> CompletableFuture.failedFuture(
						new ToolExecutionException(definition, new IllegalArgumentException("bad async argument"))));

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
		ToolCallback other = syncTool("otherTool", () -> "ok");

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
		ToolCallback ok = syncTool("okTool", () -> "fine");

		ToolCallResponse response = runSingleTool(ok, "okTool", false);

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.SUCCESS_STATUS);
		assertThat(response.getFailureKind()).isEmpty();
		assertThat(response.getExceptionType()).isEmpty();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("parallel mode: sync tool exception is reported as execution with its class name")
	void parallelSyncExceptionCarriesExecutionKind() throws Exception {
		ToolCallback failing = syncTool("parallelFailingTool", () -> {
			throw new IllegalStateException("boom");
		});

		ToolCallResponse response = runSingleTool(failing, "parallelFailingTool", true);

		assertThat(response.isError()).isTrue();
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_EXECUTION);
		assertThat(response.getExceptionType()).contains(IllegalStateException.class.getName());
	}

	@Test
	@DisplayName("failureMetadata maps throwable types to kinds and unwraps ToolExecutionException")
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

		ToolExecutionException wrapped = new ToolExecutionException(definition("t"),
				new IllegalArgumentException("inner"));
		assertThat(AgentToolNode.failureMetadata(wrapped))
			.containsEntry(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_EXECUTION)
			.containsEntry(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, IllegalArgumentException.class.getName());
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/**
	 * Runs a single tool call through the node and captures the
	 * {@link ToolCallResponse} as seen by a tool interceptor, which is the only place the
	 * response metadata is observable from.
	 */
	private ToolCallResponse runSingleTool(ToolCallback callback, String requestedToolName, boolean parallel)
			throws Exception {
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
			.toolCallbacks(List.of(callback))
			.parallelToolExecution(parallel)
			.toolExecutionTimeout(Duration.ofSeconds(5))
			.toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(false).build())
			.build();
		node.setToolInterceptors(List.of(capture));

		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", requestedToolName, "{}")))
			.build();
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

	private static OverAllState stateWithMessages(Message... messages) {
		Map<String, Object> data = new HashMap<>();
		data.put("messages", new ArrayList<>(List.of(messages)));
		return new OverAllState(data);
	}

	private static ToolDefinition definition(String name) {
		return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
	}

	private static ToolCallback syncTool(String name, ThrowingSupplier body) {
		return syncTool(definition(name), body);
	}

	private static ToolCallback syncTool(ToolDefinition definition, ThrowingSupplier body) {
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return body.get();
			}

			@Override
			public String call(String toolInput, ToolContext toolContext) {
				return body.get();
			}
		};
	}

	private static AsyncToolCallback asyncTool(String name, Duration timeout, FutureSupplier body) {
		return new AsyncToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return definition(name);
			}

			@Override
			public CompletableFuture<String> callAsync(String arguments, ToolContext context) {
				return body.get();
			}

			@Override
			public Duration getTimeout() {
				return timeout;
			}

			@Override
			public String call(String toolInput) {
				return callAsync(toolInput, new ToolContext(Map.of())).join();
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
