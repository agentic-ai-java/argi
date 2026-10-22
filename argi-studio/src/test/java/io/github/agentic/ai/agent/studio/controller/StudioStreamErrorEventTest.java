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
package io.github.agentic.ai.agent.studio.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.agent.studio.dto.AgentRunRequest;
import io.github.agentic.ai.agent.studio.dto.GraphRunRequest;
import io.github.agentic.ai.agent.studio.dto.messages.UserMessageDTO;
import io.github.agentic.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.ai.agent.studio.loader.GraphLoader;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.Agent;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies that agent and graph SSE error events are valid JSON for any message. */
class StudioStreamErrorEventTest {

	private static final String TRICKY_MESSAGE = "quote \" backslash \\ path C:\\temp\\new tab\tcr\rlf\n unicode 中文 ✓ \u0001";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void agentStreamErrorRoundTripsSpecialCharacters() throws Exception {
		var agent = mock(Agent.class);
		var loader = mock(AgentLoader.class);
		when(loader.loadAgent("assistant")).thenReturn(agent);
		when(agent.stream(any(ChatMessage.class), any()))
			.thenReturn(oneOutputThen(new IllegalStateException(TRICKY_MESSAGE)));
		var request = new AgentRunRequest();
		request.appName = "assistant";
		request.threadId = "thread";
		request.userId = "user";
		request.newMessage = new UserMessageDTO("question");

		assertOneNormalEventThenError(new ExecutionController(loader, new StudioExecutionAccess("secret"))
			.agentRunSse(request, "secret"), "IllegalStateException", TRICKY_MESSAGE);
	}

	@Test
	void graphStreamErrorRoundTripsSpecialCharacters() throws Exception {
		var graph = mock(CompiledGraph.class);
		var loader = mock(GraphLoader.class);
		when(loader.loadGraph("workflow")).thenReturn(graph);
		when(graph.stream(any(Map.class), any())).thenReturn(oneOutputThen(new IllegalStateException(TRICKY_MESSAGE)));
		var request = new GraphRunRequest();
		request.graphName = "workflow";
		request.threadId = "thread";
		request.newMessage = new UserMessageDTO("question");

		assertOneNormalEventThenError(new GraphExecutionController(loader, new StudioExecutionAccess("secret"))
			.graphRunSse(request, "secret"), "IllegalStateException", TRICKY_MESSAGE);
	}

	@Test
	void nullMessageUsesDefaultText() throws Exception {
		var event = StreamErrorEvents.of(mapper, new RuntimeException());

		JsonNode json = mapper.readTree(event.data());
		assertEquals("RuntimeException", json.get("errorType").asText());
		assertEquals("Unknown error occurred", json.get("errorMessage").asText());
	}

	@Test
	void serializationFailureFallsBackToValidErrorEvent() throws Exception {
		var failingMapper = new ObjectMapper() {
			@Override
			public String writeValueAsString(Object value) throws JsonProcessingException {
				throw new JsonProcessingException("boom") {
				};
			}
		};

		var event = StreamErrorEvents.of(failingMapper, new IllegalStateException("ignored"));

		assertEquals("error", event.event());
		JsonNode json = mapper.readTree(event.data());
		assertTrue(json.get("error").asBoolean());
		assertEquals("Internal error occurred", json.get("errorMessage").asText());
	}

	private static Flux<NodeOutput> oneOutputThen(Throwable failure) {
		var state = new OverAllState(Map.of());
		return Flux.<NodeOutput>just(new StreamingOutput<>(List.of(ChatMessage.assistant("partial")), "llm",
				"assistant", state, Usage.unknown(), OutputType.AGENT_MODEL_STREAMING))
			.concatWith(Flux.error(failure));
	}

	private void assertOneNormalEventThenError(Flux<ServerSentEvent<String>> events, String expectedType,
			String expectedMessage) throws Exception {
		var received = events.collectList().block(Duration.ofSeconds(5));
		assertNotNull(received);
		assertEquals(2, received.size());
		assertNull(received.get(0).event());
		assertTrue(received.get(0).data().contains("partial"));

		var error = received.get(1);
		assertEquals("error", error.event());
		JsonNode json = mapper.readTree(error.data());
		assertTrue(json.get("error").asBoolean());
		assertEquals(expectedType, json.get("errorType").asText());
		assertEquals(expectedMessage, json.get("errorMessage").asText());
	}

}
