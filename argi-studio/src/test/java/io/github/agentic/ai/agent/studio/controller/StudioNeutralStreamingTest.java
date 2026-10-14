/*
 * Copyright 2026 the original author or authors.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.agent.studio.dto.AgentResumeRequest;
import io.github.agentic.ai.agent.studio.dto.AgentRunRequest;
import io.github.agentic.ai.agent.studio.dto.GraphRunRequest;
import io.github.agentic.ai.agent.studio.dto.messages.UserMessageDTO;
import io.github.agentic.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.ai.agent.studio.loader.GraphLoader;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.Agent;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.when;

/** Verifies the actual controller-to-SSE conversion, including aggregate tool results. */
class StudioNeutralStreamingTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void syncOnlyClientProducesOneAnswerThroughTheRealAgentAndController() throws Exception {
		var client = mock(ChatClient.class, CALLS_REAL_METHODS);
		when(client.capabilities()).thenReturn(new ChatClientCapabilities(false, false, false, false, true, true));
		when(client.call(any())).thenReturn(CompletableFuture
			.completedFuture(new ChatResponse("answer", "mock", List.of(ChatMessage.assistant("answer")), List.of(),
					FinishReason.STOP, new Usage(1L, 1L, 2L), Map.of())));
		var agent = ReactAgent.builder().name("assistant").chatClient(client).build();
		var loader = mock(AgentLoader.class);
		when(loader.loadAgent("assistant")).thenReturn(agent);
		var request = new AgentRunRequest();
		request.appName = "assistant";
		request.threadId = "thread";
		request.userId = "user";
		request.newMessage = new UserMessageDTO("question");
		var received = new ExecutionController(loader, new StudioExecutionAccess("secret"))
			.agentRunSse(request, "secret")
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(received);
		List<String> chunks = new ArrayList<>();
		for (var event : received) {
			JsonNode data = mapper.readTree(event.data());
			assertEquals(false, data.path("error").asBoolean());
			if (!data.path("chunk").asText().isEmpty()) {
				chunks.add(data.path("chunk").asText());
			}
		}
		assertEquals(List.of("answer"), chunks);
	}

	@Test
	void agentRunPreservesEachToolResultAndDoesNotReplayTheFinalAnswer() throws Exception {
		var agent = mock(Agent.class);
		var loader = mock(AgentLoader.class);
		when(loader.loadAgent("assistant")).thenReturn(agent);
		when(agent.stream(any(ChatMessage.class), any())).thenReturn(outputs());
		var request = new AgentRunRequest();
		request.appName = "assistant";
		request.threadId = "thread";
		request.userId = "user";
		request.newMessage = new UserMessageDTO("question");

		assertEvents(
				new ExecutionController(loader, new StudioExecutionAccess("secret")).agentRunSse(request, "secret"),
				false);
	}

	@Test
	void agentResumeUsesTheSameLosslessSseConversion() throws Exception {
		var agent = mock(Agent.class);
		var loader = mock(AgentLoader.class);
		when(loader.loadAgent("assistant")).thenReturn(agent);
		when(agent.stream(anyString(), any())).thenReturn(outputs());
		var request = new AgentResumeRequest();
		request.appName = "assistant";
		request.threadId = "thread";
		request.userId = "user";

		assertEvents(
				new ExecutionController(loader, new StudioExecutionAccess("secret")).agentResumeSse(request, "secret"),
				false);
	}

	@Test
	void graphRunPreservesEachToolResultAndItsStateSnapshot() throws Exception {
		var graph = mock(CompiledGraph.class);
		var loader = mock(GraphLoader.class);
		when(loader.loadGraph("workflow")).thenReturn(graph);
		when(graph.stream(any(Map.class), any())).thenReturn(outputs());
		var request = new GraphRunRequest();
		request.graphName = "workflow";
		request.threadId = "thread";
		request.newMessage = new UserMessageDTO("question");

		assertEvents(new GraphExecutionController(loader, new StudioExecutionAccess("secret")).graphRunSse(request,
				"secret"), true);
	}

	private static Flux<NodeOutput> outputs() {
		var calls = List.of(new ToolCall("one", "lookup", "{}"), new ToolCall("two", "lookup", "{}"));
		var state = new OverAllState(Map.of("marker", "checkpoint"));
		var usage = new Usage(3L, 4L, 7L);
		return Flux.just(
				new StreamingOutput<>(List.of(ChatMessage.assistant("planning", calls)), "llm", "assistant", state,
						usage, OutputType.AGENT_MODEL_STREAMING),
				new StreamingOutput<>(
						List.of(ChatMessage.tool("one", "lookup", "first"),
								ChatMessage.tool("two", "lookup", "second")),
						"tools", "assistant", state, usage, OutputType.AGENT_TOOL_FINISHED),
				new StreamingOutput<>(List.of(ChatMessage.assistant("answer")), "llm", "assistant", state, usage,
						OutputType.AGENT_MODEL_STREAMING),
				new StreamingOutput<>(List.of(ChatMessage.assistant("answer")), "llm", "assistant", state, usage,
						OutputType.AGENT_MODEL_FINISHED));
	}

	private void assertEvents(Flux<ServerSentEvent<String>> events, boolean withState) throws Exception {
		var received = events.collectList().block(Duration.ofSeconds(5));
		assertNotNull(received);
		assertEquals(4, received.size());
		List<JsonNode> data = new ArrayList<>();
		for (var event : received) {
			data.add(mapper.readTree(event.data()));
		}
		assertEquals("tool-request", data.get(0).path("message").path("messageType").asText());
		assertEquals(2, data.get(0).path("message").path("toolCalls").size());
		assertEquals("", data.get(0).path("chunk").asText());
		for (int index = 1; index <= 2; index++) {
			var event = data.get(index);
			assertEquals("tools", event.path("node").asText());
			assertEquals("assistant", event.path("agent").asText());
			assertEquals(index == 2 ? 7 : 0, event.path("tokenUsage").path("totalTokens").asInt());
			assertEquals("tool", event.path("message").path("messageType").asText());
			var results = event.path("message").path("responses");
			assertEquals(1, results.size());
			assertEquals(index == 1 ? "one" : "two", results.get(0).path("id").asText());
			assertEquals("lookup", results.get(0).path("name").asText());
			assertEquals(index == 1 ? "first" : "second", results.get(0).path("responseData").asText());
			assertEquals("", event.path("chunk").asText());
			if (withState) {
				assertEquals("checkpoint", event.path("state").path("marker").asText());
			}
		}
		assertEquals(7, data.get(1).path("tokenUsage").path("totalTokens").asInt()
				+ data.get(2).path("tokenUsage").path("totalTokens").asInt());
		assertEquals("answer", data.get(3).path("chunk").asText());
		assertEquals("assistant", data.get(3).path("message").path("messageType").asText());
	}

}
