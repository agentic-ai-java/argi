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
import io.github.agentic.ai.agent.studio.dto.messages.ToolRequestConfirmMessageDTO.ToolFeedback;
import io.github.agentic.ai.agent.studio.dto.messages.ToolRequestConfirmMessageDTO.ToolFeedback.FeedbackResult;
import io.github.agentic.ai.agent.studio.dto.messages.UserMessageDTO;
import io.github.agentic.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises Studio approvals through the real ReAct graph, saver and tool runtime. */
class StudioHumanApprovalIntegrationTest {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "updated")
	void resumeApprovesOnlyReviewedCallsAndKeepsHistory(String marker) throws Exception {
		List<ChatRequest> modelTurns = new ArrayList<>();
		var calls = List.of(new ToolCall("one", "read", "{}"), new ToolCall("two", "write", "{}"));
		var client = mock(ChatClient.class);
		when(client.capabilities()).thenReturn(new ChatClientCapabilities(true, true, false, false, true, true));
		when(client.stream(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			modelTurns.add(request);
			ChatResponse response = modelTurns.size() == 1
					? new ChatResponse("request", "mock", List.of(ChatMessage.assistant("", calls)), calls,
							FinishReason.TOOL_CALLS, Usage.unknown(), Map.of())
					: new ChatResponse("answer", "mock", List.of(ChatMessage.assistant("done")), List.of(),
							FinishReason.STOP, Usage.unknown(), Map.of());
			return JdkFlowAdapter.publisherToFlowPublisher(Flux.just(ChatEvent.response(response)));
		});
		var read = tool("read");
		var write = tool("write");
		var agent = ReactAgent.builder()
			.name("assistant")
			.chatClient(client)
			.toolRegistry(new ToolRegistry(List.of(read, write)))
			.saver(new MemorySaver())
			.hooks(HumanInTheLoopHook.builder()
				.approvalOn("read", "Read data")
				.approvalOn("write", "Write data")
				.build())
			.build();
		var loader = mock(AgentLoader.class);
		when(loader.loadAgent("assistant")).thenReturn(agent);
		var controller = new ExecutionController(loader, new StudioExecutionAccess("secret"));
		var run = new AgentRunRequest();
		run.appName = "assistant";
		run.threadId = "thread";
		run.userId = "user";
		run.newMessage = new UserMessageDTO("question");
		var pending = events(controller.agentRunSse(run, "secret"));
		assertTrue(pending.stream()
			.anyMatch(event -> "tool-confirm".equals(event.path("message").path("messageType").asText())));
		assertEquals(1, modelTurns.size());
		verify(read, never()).execute(any(), any());
		verify(write, never()).execute(any(), any());

		var resume = new AgentResumeRequest();
		resume.appName = run.appName;
		resume.threadId = run.threadId;
		resume.userId = run.userId;
		resume.toolFeedbacks = List.of(feedback("one", "read", FeedbackResult.APPROVED),
				feedback("two", "write", FeedbackResult.REJECTED));
		resume.stateDelta = marker == null ? null : marker.isEmpty() ? Map.of() : Map.of("marker", marker);
		var resumed = events(controller.agentResumeSse(resume, "secret"));
		assertTrue(resumed.stream().noneMatch(event -> event.path("error").asBoolean()));
		assertEquals("done", resumed.stream().map(event -> event.path("chunk").asText()).reduce("", String::concat));
		assertEquals(2, modelTurns.size());
		verify(read).execute(any(), any());
		verify(write, never()).execute(any(), any());
		var history = modelTurns.get(1).messages();
		var userMessages = history.stream().filter(message -> message.role() == ChatRole.USER).toList();
		assertEquals(List.of("question"), userMessages.stream().map(ChatMessage::text).toList());
		var results = history.stream().filter(message -> message.role() == ChatRole.TOOL).toList();
		assertEquals(2, results.size());
		assertEquals("found",
				results.stream()
					.filter(message -> "one".equals(message.toolCallId()))
					.findFirst()
					.orElseThrow()
					.text());
		assertTrue(results.stream()
			.filter(message -> "two".equals(message.toolCallId()))
			.findFirst()
			.orElseThrow()
			.text()
			.contains("rejected by human"));
		if (marker != null && !marker.isEmpty()) {
			var state = agent.getAndCompileGraph()
				.getState(RunnableConfig.builder()
					.threadId("thread")
					.addMetadata(RunnableConfig.APP_NAME_METADATA_KEY, "assistant")
					.addMetadata("user_id", "user")
					.build());
			assertEquals(marker, state.state().value("marker").orElseThrow());
		}
	}

	private static Tool tool(String name) {
		var tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition(name, "Test tool", "{\"type\":\"object\"}"));
		when(tool.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.text("found")));
		return tool;
	}

	private static ToolFeedback feedback(String id, String name, FeedbackResult result) {
		var feedback = new ToolFeedback();
		feedback.setId(id);
		feedback.setName(name);
		feedback.setResult(result);
		feedback.setDescription("Reviewed");
		return feedback;
	}

	private static List<JsonNode> events(Flux<ServerSentEvent<String>> stream) throws Exception {
		var received = stream.collectList().block(Duration.ofSeconds(5));
		assertNotNull(received);
		List<JsonNode> results = new ArrayList<>();
		var mapper = new ObjectMapper();
		for (var event : received) {
			results.add(mapper.readTree(event.data()));
		}
		return results;
	}

}
