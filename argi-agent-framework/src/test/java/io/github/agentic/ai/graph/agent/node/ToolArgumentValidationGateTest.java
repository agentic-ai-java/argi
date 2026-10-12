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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the opt-in tool argument validation gate (argi#134 / T31):
 * invalid arguments must be rejected before the tool callback runs, valid arguments
 * must pass through, and disabling the gate must preserve legacy behavior.
 */
class ToolArgumentValidationGateTest {

	private static final String GATED_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "name": { "type": "string" },
			    "level": { "type": "integer", "minimum": 1, "maximum": 5 }
			  },
			  "required": ["name"],
			  "additionalProperties": false
			}""";

	private AgentToolNode.Builder baseBuilder() {
		return AgentToolNode.builder()
			.agentName("gate-test-agent")
			.toolExecutionTimeout(Duration.ofSeconds(5))
			.toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder()
				.alwaysThrow(false)
				.build());
	}

	private ToolCallback countedTool(String name, String inputSchema, AtomicInteger counter) {
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder()
					.name(name)
					.description("Test tool " + name)
					.inputSchema(inputSchema)
					.build();
			}

			@Override
			public String call(String toolInput, ToolContext toolContext) {
				counter.incrementAndGet();
				return "executed:" + toolInput;
			}

			@Override
			public String call(String toolInput) {
				return call(toolInput, new ToolContext(Map.of()));
			}
		};
	}

	private String run(AgentToolNode node, String toolName, String arguments) throws Exception {
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", toolName, arguments);
		AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
		Map<String, Object> stateData = new HashMap<>();
		stateData.put("messages", new ArrayList<>(List.of((Message) message)));
		OverAllState state = new OverAllState(stateData);

		Map<String, Object> result = node.apply(state, RunnableConfig.builder().build());
		ToolResponseMessage responseMessage = (ToolResponseMessage) result.get("messages");
		assertEquals(1, responseMessage.getResponses().size());
		return responseMessage.getResponses().get(0).responseData();
	}

	@Test
	@DisplayName("missing required property is rejected before the callback runs")
	void missingRequiredIsRejected() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<ToolCallResponse> captured = new AtomicReference<>();
		ToolInterceptor capturing = capturingInterceptor(captured);

		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.toolCallbacks(List.of(countedTool("gated", GATED_SCHEMA, calls)))
			.build();
		node.setToolInterceptors(List.of(capturing));

		String response = run(node, "gated", "{\"level\": 3}");

		assertEquals(0, calls.get(), "callback must not run on validation failure");
		assertTrue(response.contains("REQUIRED_MISSING"), response);
		assertTrue(response.contains("$.name"), response);
		assertFalse(response.contains("3"), "response must not echo argument values");

		Map<String, Object> metadata = captured.get().getMetadata();
		assertEquals(true, metadata.get("error"));
		assertEquals("REQUIRED_MISSING", metadata.get("errorCode"));
		assertEquals("$.name", metadata.get("fieldPath"));
	}

	@Test
	@DisplayName("illegal JSON is rejected before the callback runs")
	void illegalJsonIsRejected() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.toolCallbacks(List.of(countedTool("gated", GATED_SCHEMA, calls)))
			.build();

		String response = run(node, "gated", "{\"name\": ");

		assertEquals(0, calls.get());
		assertTrue(response.contains("INVALID_JSON"), response);
	}

	@Test
	@DisplayName("type, enum, range and additionalProperties violations are rejected")
	void ruleViolationsAreRejected() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.toolCallbacks(List.of(countedTool("gated", GATED_SCHEMA, calls)))
			.build();

		assertTrue(run(node, "gated", "{\"name\": 42}").contains("INVALID_TYPE"));
		assertTrue(run(node, "gated", "{\"name\": \"x\", \"level\": 0}").contains("RANGE_VIOLATION"));
		assertTrue(run(node, "gated", "{\"name\": \"x\", \"extra\": \"v\"}").contains("ADDITIONAL_PROPERTY"));
		assertEquals(0, calls.get(), "callback must not run for any rejected call");
	}

	@Test
	@DisplayName("valid arguments execute the callback when the gate is enabled")
	void validArgumentsPass() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.toolCallbacks(List.of(countedTool("gated", GATED_SCHEMA, calls)))
			.build();

		String response = run(node, "gated", "{\"name\": \"agent\", \"level\": 3}");

		assertEquals(1, calls.get());
		assertTrue(response.contains("executed:"), response);
	}

	@Test
	@DisplayName("disabled gate (default) preserves legacy pass-through behavior")
	void disabledGatePreservesLegacyBehavior() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentToolNode node = baseBuilder()
			.toolCallbacks(List.of(countedTool("gated", GATED_SCHEMA, calls)))
			.build();

		String response = run(node, "gated", "{\"level\": 0}");

		assertEquals(1, calls.get(), "legacy behavior must reach the callback even for invalid arguments");
		assertTrue(response.contains("executed:"), response);
	}

	@Test
	@DisplayName("async wrapped dispatch is gated the same way as sync dispatch")
	void asyncPathIsGated() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.wrapSyncToolsAsAsync(true)
			.toolCallbacks(List.of(countedTool("gated", GATED_SCHEMA, calls)))
			.build();

		String response = run(node, "gated", "{\"level\": 3}");

		assertEquals(0, calls.get());
		assertTrue(response.contains("REQUIRED_MISSING"), response);
	}

	@Test
	@DisplayName("arguments rewritten by an interceptor are validated after the rewrite")
	void interceptorRewriteIsValidated() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<String> seenByTool = new AtomicReference<>();

		ToolInterceptor rewriting = new ToolInterceptor() {
			@Override
			public String getName() {
				return "rewritingInterceptor";
			}

			@Override
			public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
				ToolCallRequest modified = ToolCallRequest.builder(request)
					.arguments("{\"rewritten\": true}")
					.build();
				return handler.call(modified);
			}
		};

		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.toolCallbacks(List.of(countedTool("gated", GATED_SCHEMA, calls)))
			.build();
		node.setToolInterceptors(List.of(rewriting));

		// original arguments are valid; only validating the rewritten value can reject the call
		String response = run(node, "gated", "{\"name\": \"agent\"}");

		assertEquals(0, calls.get(),
				"gate must validate the post-rewrite arguments, not the original ones");
		assertTrue(response.contains("REQUIRED_MISSING"), response);
	}

	private ToolInterceptor capturingInterceptor(AtomicReference<ToolCallResponse> captured) {
		return new ToolInterceptor() {
			@Override
			public String getName() {
				return "capturingInterceptor";
			}

			@Override
			public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
				ToolCallResponse response = handler.call(request);
				captured.set(response);
				return response;
			}
		};
	}

	@Test
	@DisplayName("oversized arguments are rejected before the callback runs")
	void oversizedArgumentsAreRejected() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.toolCallbacks(List.of(countedTool("gated", "{}", calls)))
			.build();

		// 70_000 bytes, well above the validator's 64 KiB byte limit
		String response = run(node, "gated", "{\"name\": \"" + "a".repeat(70_000) + "\"}");

		assertEquals(0, calls.get(), "byte-limit rejection must happen before dispatch");
		assertTrue(response.contains("ARGUMENTS_TOO_LARGE"), response);
	}

	@Test
	@DisplayName("local $ref rules in a tool schema are enforced by the gate")
	void localRefSchemaIsEnforced() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		String refSchema = """
				{
				  "type": "object",
				  "properties": {
				    "name": { "$ref": "#/definitions/str" }
				  },
				  "definitions": { "str": { "type": "string" } }
				}""";
		AgentToolNode node = baseBuilder()
			.validateToolArguments(true)
			.toolCallbacks(List.of(countedTool("gated", refSchema, calls)))
			.build();

		assertTrue(run(node, "gated", "{\"name\": \"ok\"}").contains("executed:"));
		assertEquals(1, calls.get());

		String rejected = run(node, "gated", "{\"name\": 1}");
		assertEquals(1, calls.get(), "second call must be rejected before the callback");
		assertTrue(rejected.contains("INVALID_TYPE"), rejected);
	}

}
