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
package io.github.agentic.ai.graph.agent.a2a;

import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.async.AsyncGenerator;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A2aNodeActionWithConfigTests {

	private static final Method TO_FLUX = initToFluxMethod();

	private final A2aNodeActionWithConfig action = new A2aNodeActionWithConfig(createAgentCardWrapper(), "", false,
			"messages", "instruction", true);

	@Test
	void toFluxEmitsAsyncData() throws Exception {
		AsyncGenerator<NodeOutput> generator = new AsyncGenerator<>() {
			private final AtomicInteger index = new AtomicInteger();

			@Override
			public Data<NodeOutput> next() {
				int step = index.getAndIncrement();
				if (step == 0) {
					// Use completedFuture to avoid timing issues with async execution
					return Data.of(CompletableFuture
						.completedFuture(NodeOutput.of("node-1", "", new OverAllState(), Usage.unknown())));
				}
				if (step == 1) {
					return Data.done(Map.of("result", "ok"));
				}
				return Data.done();
			}
		};

		Flux<GraphResponse<NodeOutput>> flux = invokeToFlux(generator);

		List<GraphResponse<NodeOutput>> responses = flux.collectList().block(Duration.ofSeconds(5));

		assertNotNull(responses);
		assertEquals(2, responses.size());

		GraphResponse<NodeOutput> first = responses.get(0);
		assertFalse(first.isDone());
		NodeOutput output = first.getOutput().getNow(null);
		assertNotNull(output);
		assertEquals("node-1", output.node());

		GraphResponse<NodeOutput> second = responses.get(1);
		assertTrue(second.isDone());
		Map<?, ?> resultValue = (Map<?, ?>) second.resultValue().orElseThrow();
		assertEquals("ok", resultValue.get("result"));
	}

	@Test
	void toFluxDrainsCompletedFuturesWithoutRecursiveStackGrowth() throws Exception {
		int count = 20_000;
		AsyncGenerator<NodeOutput> generator = new AsyncGenerator<>() {
			private final AtomicInteger index = new AtomicInteger();

			@Override
			public Data<NodeOutput> next() {
				int step = index.getAndIncrement();
				if (step < count) {
					return Data.of(NodeOutput.of("node-" + step, "", new OverAllState(), Usage.unknown()));
				}
				return Data.done(Map.of("result", "ok"));
			}
		};

		Flux<GraphResponse<NodeOutput>> flux = invokeToFlux(generator);

		List<GraphResponse<NodeOutput>> responses = flux.collectList().block(Duration.ofSeconds(5));

		assertNotNull(responses);
		assertEquals(count + 1, responses.size());
		assertEquals("node-0", responses.get(0).getOutput().getNow(null).node());
		assertEquals("node-" + (count - 1), responses.get(count - 1).getOutput().getNow(null).node());
		assertTrue(responses.get(count).isDone());
	}

	@Test
	void toFluxPropagatesErrors() throws Exception {
		AsyncGenerator<NodeOutput> generator = new AsyncGenerator<>() {
			private final AtomicInteger index = new AtomicInteger();

			@Override
			public Data<NodeOutput> next() {
				int step = index.getAndIncrement();
				if (step == 0) {
					// Use a completed exceptionally future to avoid timing issues
					CompletableFuture<NodeOutput> failedFuture = new CompletableFuture<>();
					failedFuture.completeExceptionally(new IllegalStateException("boom"));
					return Data.of(failedFuture);
				}
				return Data.done();
			}
		};

		Flux<GraphResponse<NodeOutput>> flux = invokeToFlux(generator);

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> flux.collectList().block(Duration.ofSeconds(5)));
		assertEquals("boom", exception.getMessage());
	}

	@Test
	void applyPreservesNonStreamingOutputContractAndDoesNotWritePayloadToStdout() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String response = """
					{"jsonrpc":"2.0","id":"response-1","result":{"kind":"artifact-update","artifact":{"parts":[{"text":"remote ok"}]}}}
					""";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
		PrintStream originalOut = System.out;
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
			A2aNodeActionWithConfig nonStreamingAction = new A2aNodeActionWithConfig(createAgentCardWrapper(baseUrl),
					"remote-agent", false, "reply", "secret instruction", false);

			Map<String, Object> result = nonStreamingAction.apply(new OverAllState(),
					RunnableConfig.builder().threadId("thread-1").build());
			A2aNodeActionWithConfig messagesAction = new A2aNodeActionWithConfig(createAgentCardWrapper(baseUrl),
					"remote-agent", false, "messages", "secret instruction", false);
			Map<String, Object> messagesResult = messagesAction.apply(new OverAllState(),
					RunnableConfig.builder().threadId("thread-1").build());

			assertEquals("remote ok", result.get("reply"));
			ChatMessage message = assertInstanceOf(ChatMessage.class, messagesResult.get("messages"));
			assertEquals("remote ok", message.text());
			assertEquals("", stdout.toString(StandardCharsets.UTF_8));
		}
		finally {
			System.setOut(originalOut);
			server.stop(0);
		}
	}

	@SuppressWarnings("unchecked")
	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void rendersInstructionBeforeSendingAndRejectsMissingVariablesBeforeTransport(boolean streaming) throws Exception {
		ObjectMapper json = new ObjectMapper();
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<JsonNode> captured = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try {
				requests.incrementAndGet();
				captured.set(json.readTree(exchange.getRequestBody()));
				byte[] bytes = """
						{"jsonrpc":"2.0","id":"response-1","result":{"kind":"artifact-update","artifact":{"parts":[{"text":"remote ok"}]}}}
						"""
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
			finally {
				exchange.close();
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			A2aNodeActionWithConfig node = new A2aNodeActionWithConfig(createAgentCardWrapper(baseUrl), "remote-agent",
					false, "reply", "Inspect {topic}", streaming);
			RunnableConfig config = RunnableConfig.builder().threadId("template-test").build();
			assertThrows(IllegalArgumentException.class, () -> node.apply(new OverAllState(), config));
			assertEquals(0, requests.get());
			Map<String, Object> result = node.apply(new OverAllState(Map.of("topic", "literal {braces}")), config);
			if (streaming) {
				Flux<GraphResponse<NodeOutput>> flux = (Flux<GraphResponse<NodeOutput>>) result.get("reply");
				List<GraphResponse<NodeOutput>> outputs = flux.collectList().block(Duration.ofSeconds(5));
				assertNotNull(outputs);
				GraphResponse<NodeOutput> terminal = outputs.get(outputs.size() - 1);
				assertTrue(terminal.isDone());
				assertEquals("remote ok", ((Map<?, ?>) terminal.resultValue().orElseThrow()).get("reply"));
			}
			else {
				assertEquals("remote ok", result.get("reply"));
			}
			assertEquals(1, requests.get());
			assertEquals(streaming ? "message/stream" : "message/send", captured.get().path("method").asText());
			assertEquals("Inspect literal {braces}", captured.get().at("/params/message/parts/0/text").asText());
		}
		finally {
			server.stop(0);
		}
	}

	@SuppressWarnings("unchecked")
	private Flux<GraphResponse<NodeOutput>> invokeToFlux(AsyncGenerator<NodeOutput> generator) throws Exception {
		return (Flux<GraphResponse<NodeOutput>>) TO_FLUX.invoke(this.action, generator);
	}

	private static Method initToFluxMethod() {
		try {
			Method method = A2aNodeActionWithConfig.class.getDeclaredMethod("toFlux", AsyncGenerator.class);
			method.setAccessible(true);
			return method;
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static AgentCardWrapper createAgentCardWrapper() {
		return createAgentCardWrapper(null);
	}

	private static AgentCardWrapper createAgentCardWrapper(String url) {
		AgentCard agentCard = mock(AgentCard.class);
		when(agentCard.name()).thenReturn("test-agent");
		when(agentCard.url()).thenReturn(url);
		return new AgentCardWrapper(agentCard);
	}

	// ==================== Tests for Issue #3608 fixes ====================

	private static final Method EXTRACT_RESPONSE_TEXT = initExtractResponseTextMethod();

	private static final Method AUTO_DETECT_AND_PARSE_RESPONSE = initAutoDetectAndParseResponseMethod();

	/**
	 * Test that extractResponseText returns empty string for "submitted" state. This is a
	 * fix for Issue #3608 - "Agent State: submitted" should not be returned.
	 */
	@Test
	void extractResponseText_withSubmittedState_returnsEmptyString() throws Exception {
		Map<String, Object> result = new HashMap<>();
		result.put("kind", "status-update");
		Map<String, Object> status = new HashMap<>();
		status.put("state", "submitted");
		result.put("status", status);

		String response = invokeExtractResponseText(result);
		assertEquals("", response, "submitted state should return empty string, not 'Agent State: submitted'");
	}

	/**
	 * Test that extractResponseText returns empty string for "canceled" state.
	 */
	@Test
	void extractResponseText_withCanceledState_returnsEmptyString() throws Exception {
		Map<String, Object> result = new HashMap<>();
		result.put("kind", "status-update");
		Map<String, Object> status = new HashMap<>();
		status.put("state", "canceled");
		result.put("status", status);

		String response = invokeExtractResponseText(result);
		assertEquals("", response, "canceled state should return empty string");
	}

	/**
	 * Test that extractResponseText returns empty string for known ignorable states.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "completed", "processing", "failed", "submitted", "canceled" })
	void extractResponseText_withIgnorableStates_returnsEmptyString(String state) throws Exception {
		Map<String, Object> result = new HashMap<>();
		result.put("kind", "status-update");
		Map<String, Object> status = new HashMap<>();
		status.put("state", state);
		result.put("status", status);

		String response = invokeExtractResponseText(result);
		assertEquals("", response, state + " state should return empty string");
	}

	/**
	 * Test that extractResponseText returns "Agent State: xxx" for unknown states.
	 */
	@Test
	void extractResponseText_withUnknownState_returnsAgentStateMessage() throws Exception {
		Map<String, Object> result = new HashMap<>();
		result.put("kind", "status-update");
		Map<String, Object> status = new HashMap<>();
		status.put("state", "unknown_state");
		result.put("status", status);

		String response = invokeExtractResponseText(result);
		assertEquals("Agent State: unknown_state", response, "Unknown state should return 'Agent State: xxx'");
	}

	/**
	 * Test that extractResponseText correctly extracts text from "working" state.
	 */
	@Test
	void extractResponseText_withWorkingState_extractsMessageText() throws Exception {
		Map<String, Object> result = new HashMap<>();
		result.put("kind", "status-update");
		Map<String, Object> status = new HashMap<>();
		status.put("state", "working");
		Map<String, Object> message = new HashMap<>();
		message.put("parts", List.of(Map.of("text", "Processing your request...")));
		status.put("message", message);
		result.put("status", status);

		String response = invokeExtractResponseText(result);
		assertEquals("Processing your request...", response);
	}

	/**
	 * Test that extractResponseText correctly extracts text from artifact-update.
	 */
	@Test
	void extractResponseText_withArtifactUpdate_extractsArtifactText() throws Exception {
		Map<String, Object> result = new HashMap<>();
		result.put("kind", "artifact-update");
		Map<String, Object> artifact = new HashMap<>();
		artifact.put("parts", List.of(Map.of("text", "This is the artifact response")));
		result.put("artifact", artifact);

		String response = invokeExtractResponseText(result);
		assertEquals("This is the artifact response", response);
	}

	@Test
	void autoDetectAndParseResponseTreatsTypeMetadataAsOrdinaryJsonField() throws Exception {
		String responseText = """
				{"jsonrpc":"2.0","id":"response-1","result":{"@type":"java.lang.Thread","kind":"artifact-update","artifact":{"parts":[{"text":"remote ok"}]}}}
				""";

		Map<String, Object> response = invokeAutoDetectAndParseResponse(responseText);

		Map<String, Object> result = castMap(response.get("result"));
		assertEquals("java.lang.Thread", result.get("@type"));
		assertEquals("remote ok", invokeExtractResponseText(result));
	}

	@Test
	void parseStreamingResponseTreatsTypeMetadataAsOrdinaryJsonField() throws Exception {
		String responseText = """
				event: status
				data: {"jsonrpc":"2.0","id":"response-1","result":{"@type":"java.lang.Thread","kind":"artifact-update","artifact":{"parts":[{"text":"remote ok"}]}}}

				data: [DONE]
				""";

		Map<String, Object> response = invokeAutoDetectAndParseResponse(responseText);

		Map<String, Object> result = castMap(response.get("result"));
		assertEquals("java.lang.Thread", result.get("@type"));
		assertEquals("remote ok", invokeExtractResponseText(result));
	}

	private String invokeExtractResponseText(Map<String, Object> result) throws Exception {
		return (String) EXTRACT_RESPONSE_TEXT.invoke(this.action, result);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> invokeAutoDetectAndParseResponse(String responseText) throws Exception {
		return (Map<String, Object>) AUTO_DETECT_AND_PARSE_RESPONSE.invoke(this.action, responseText);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value) {
		return (Map<String, Object>) value;
	}

	private static Method initExtractResponseTextMethod() {
		try {
			Method method = A2aNodeActionWithConfig.class.getDeclaredMethod("extractResponseText", Map.class);
			method.setAccessible(true);
			return method;
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Method initAutoDetectAndParseResponseMethod() {
		try {
			Method method = A2aNodeActionWithConfig.class.getDeclaredMethod("autoDetectAndParseResponse", String.class);
			method.setAccessible(true);
			return method;
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// ==================== Tests for gh-4760: A2A streaming output metadata
	// ====================

	private static final Method BUILD_STREAMING_OUTPUT = initBuildStreamingOutputMethod();

	private static final Method BUILD_FINAL_RESULT = initBuildFinalResultMethod();

	/**
	 * Studio receives neutral assistant messages and the model-delta output type.
	 */
	@Test
	void buildStreamingOutput_populatesNeutralMessageAndOutputType() throws Exception {
		OverAllState state = new OverAllState();

		StreamingOutput<?> output = (StreamingOutput<?>) BUILD_STREAMING_OUTPUT.invoke(this.action, "hello world",
				state);

		assertEquals("hello world", output.text());
		assertEquals(List.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("hello world")), output.messages());
		// outputType restores metadata for downstream consumers of getOutputType()
		assertEquals(OutputType.AGENT_MODEL_STREAMING, output.getOutputType());
	}

	/**
	 * Regression test for ARGI #47. A2A responses targeting the shared messages key must
	 * be appended as Message instances, otherwise the next routing decision encounters a
	 * String in List&lt;Message&gt; and fails with ClassCastException.
	 */
	@Test
	void buildFinalResult_wrapsMessagesOutputInAssistantMessage() throws Exception {
		Map<String, Object> result = invokeBuildFinalResult("messages", "remote reply");

		ChatMessage message = assertInstanceOf(ChatMessage.class, result.get("messages"));
		assertEquals("remote reply", message.text());
	}

	@Test
	void buildFinalResult_keepsCustomOutputAsString() throws Exception {
		Map<String, Object> result = invokeBuildFinalResult("reply", "remote reply");

		assertEquals("remote reply", result.get("reply"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> invokeBuildFinalResult(String outputKey, String text) throws Exception {
		return (Map<String, Object>) BUILD_FINAL_RESULT.invoke(this.action, outputKey, text);
	}

	private static Method initBuildStreamingOutputMethod() {
		try {
			Method method = A2aNodeActionWithConfig.class.getDeclaredMethod("buildStreamingOutput", String.class,
					OverAllState.class);
			method.setAccessible(true);
			return method;
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Method initBuildFinalResultMethod() {
		try {
			Method method = A2aNodeActionWithConfig.class.getDeclaredMethod("buildFinalResult", String.class,
					String.class);
			method.setAccessible(true);
			return method;
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
