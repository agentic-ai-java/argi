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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.flow.agent.LlmRoutingAgent;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;

import org.springframework.ai.chat.messages.AssistantMessage;
import io.github.agentic.ai.api.chat.ChatMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression tests for ARGI #47. */
class A2aLlmRoutingAgentShareStateTests {

	@Test
	void sharedA2aRepliesRemainMessagesAcrossRoutingInvocations() throws Exception {
		AtomicInteger remoteRequests = new AtomicInteger();
		HttpServer server = createA2aServer(remoteRequests);
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			CapturingRoutingChatModel routingModel = new CapturingRoutingChatModel("remote_agent");
			A2aRemoteAgent remoteAgent = A2aRemoteAgent.builder()
				.name("remote_agent")
				.description("Remote test agent")
				.agentCard(createAgentCard(baseUrl))
				.outputKey("messages")
				.shareState(true)
				.build();
			LlmRoutingAgent routingAgent = LlmRoutingAgent.builder()
				.name("routing_agent")
				.description("Routes requests to the remote agent")
				.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(routingModel))
				.subAgents(List.of(remoteAgent))
				.saver(new MemorySaver())
				.build();
			RunnableConfig config = RunnableConfig.builder().threadId("issue-47-thread").build();

			Optional<OverAllState> firstResult = routingAgent.invoke("first request", config);
			Optional<OverAllState> secondResult = routingAgent.invoke("second request", config);

			assertTrue(firstResult.isPresent());
			assertTrue(secondResult.isPresent());
			assertEquals(2, remoteRequests.get());
			assertEquals(2, routingModel.prompts().size());
			assertTrue(routingModel.prompts()
				.get(1)
				.getInstructions()
				.stream()
				.anyMatch(
						message -> message instanceof AssistantMessage && "remote reply 1".equals(message.getText())));

			@SuppressWarnings("unchecked")
			List<Object> messages = (List<Object>) secondResult.orElseThrow().value("messages").orElseThrow();
			assertTrue(messages.stream().allMatch(ChatMessage.class::isInstance));
			ChatMessage lastMessage = assertInstanceOf(ChatMessage.class, messages.get(messages.size() - 1));
			assertEquals("remote reply 2", lastMessage.text());
		}
		finally {
			server.stop(0);
		}
	}

	private static HttpServer createA2aServer(AtomicInteger requests) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			int requestNumber = requests.incrementAndGet();
			String response = """
					{"jsonrpc":"2.0","id":"response-%d","result":{"kind":"artifact-update","artifact":{"parts":[{"text":"remote reply %d"}]}}}
					"""
				.formatted(requestNumber, requestNumber);
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		return server;
	}

	private static AgentCard createAgentCard(String baseUrl) {
		AgentCard agentCard = mock(AgentCard.class);
		AgentCapabilities capabilities = mock(AgentCapabilities.class);
		when(capabilities.streaming()).thenReturn(false);
		when(agentCard.name()).thenReturn("remote_agent");
		when(agentCard.url()).thenReturn(baseUrl);
		when(agentCard.capabilities()).thenReturn(capabilities);
		return agentCard;
	}

	private static final class CapturingRoutingChatModel implements ChatModel {

		private final String response;

		private final java.util.ArrayList<Prompt> prompts = new java.util.ArrayList<>();

		private CapturingRoutingChatModel(String agentName) {
			this.response = "{\"agents\":[{\"agent\":\"" + agentName + "\",\"query\":\"delegate\"}]}";
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompts.add(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage(this.response))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

		private List<Prompt> prompts() {
			return List.copyOf(this.prompts);
		}

	}

}
