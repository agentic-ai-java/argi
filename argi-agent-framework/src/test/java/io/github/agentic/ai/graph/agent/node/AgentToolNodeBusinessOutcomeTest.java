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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.BusinessOutcomeMapper;
import io.github.agentic.ai.graph.agent.interceptor.BusinessOutcomeMapperRegistry;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ToolOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the T38 business-outcome acceptance criteria end to end: a registered
 * {@link BusinessOutcomeMapper} is the only component that can reinterpret a successful
 * raw tool result as a business failure ({@link ToolOutcome#BUSINESS_FAILED}), results
 * of unregistered tools are never inspected, and the outcome survives the conversion to
 * the model-visible message.
 */
@DisplayName("AgentToolNode business outcome mapping")
class AgentToolNodeBusinessOutcomeTest {

	private static final String CALL_ID = "call_1";

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("registered mapper surfacing a business failure gets BUSINESS_FAILED end to end")
	void registeredBusinessFailureIsSurfaced() throws Exception {
		BusinessOutcomeMapper decline = (call, result) -> new ToolResult(result.content(), true,
				Map.of("errorCode", "PAYMENT_DECLINED"));
		Tool payment = tool("paymentTool", ToolResult.text("{\"status\":\"declined\"}"));
		BusinessOutcomeMapperRegistry registry = BusinessOutcomeMapperRegistry.builder()
			.declare("paymentTool", decline)
			.build();

		ToolCallResponse response = runSingleTool(payment, "paymentTool", registry);

		assertThat(response.getStatus()).isEqualTo(ToolOutcome.BUSINESS_FAILED);
		// A business failure is a failure: isError() must agree so the model-visible
		// message derived from it carries tool.error=true.
		assertThat(response.isError()).isTrue();
		assertThat(response.getResult()).contains("declined");
		assertThat(response.getMetadata()).containsEntry("errorCode", "PAYMENT_DECLINED");
		// The outcome must survive the conversion into the model-visible message.
		ChatMessage visible = response.toMessage();
		assertThat(visible.metadata()).containsEntry("errorCode", "PAYMENT_DECLINED");
		assertThat(visible.metadata()).containsEntry("tool.error", true);
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("unregistered tool whose payload mentions error stays a plain success")
	void unregisteredToolPayloadIsNeverGuessed() throws Exception {
		Tool suspicious = tool("rawTool", ToolResult.text("{\"error\": true, \"reason\": \"oops\"}"));

		ToolCallResponse response = runSingleTool(suspicious, "rawTool", BusinessOutcomeMapperRegistry.empty());

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.SUCCESS_STATUS);
		assertThat(response.isError()).isFalse();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("registered mapper returning the result unchanged keeps SUCCESS")
	void registeredMapperKeepingResultStaysSuccess() throws Exception {
		Tool ok = tool("paymentTool", ToolResult.text("{\"status\":\"ok\"}"));
		BusinessOutcomeMapperRegistry registry = BusinessOutcomeMapperRegistry.builder()
			.declare("paymentTool", (call, result) -> result)
			.build();

		ToolCallResponse response = runSingleTool(ok, "paymentTool", registry);

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.SUCCESS_STATUS);
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("mapper throwing is reported as a regular execution failure")
	void mapperFailureIsReportedAsExecutionError() throws Exception {
		Tool ok = tool("paymentTool", ToolResult.text("pending"));
		BusinessOutcomeMapperRegistry registry = BusinessOutcomeMapperRegistry.builder()
			.declare("paymentTool", (call, result) -> {
				throw new IllegalStateException("mapper blew up");
			})
			.build();

		ToolCallResponse response = runSingleTool(ok, "paymentTool", registry);

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.ERROR_STATUS);
		assertThat(response.isError()).isTrue();
		assertThat(response.getFailureKind()).contains(ToolCallResponse.FAILURE_KIND_EXECUTION);
		assertThat(response.getResult()).contains("mapper blew up");
	}

	// ---------------------------------------------------------------------
	// helpers (pattern borrowed from AgentToolNodeFailureMetadataTest)
	// ---------------------------------------------------------------------

	private ToolCallResponse runSingleTool(Tool callback, String requestedToolName,
			BusinessOutcomeMapperRegistry registry) throws Exception {
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
			.parallelToolExecution(false)
			.toolExecutionTimeout(Duration.ofSeconds(5))
			.throwOnToolError(false)
			.businessOutcomeMappers(registry)
			.build();
		node.setToolInterceptors(List.of(capture));

		ChatMessage assistantMessage = ChatMessage.assistant("",
				List.of(new ToolCall(CALL_ID, requestedToolName, "{}")));
		Map<String, Object> data = new HashMap<>();
		data.put("messages", new ArrayList<>(List.of(assistantMessage)));
		OverAllState state = new OverAllState(data);

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

	private static Tool tool(String name, ToolResult result) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(result);
			}
		};
	}

}
