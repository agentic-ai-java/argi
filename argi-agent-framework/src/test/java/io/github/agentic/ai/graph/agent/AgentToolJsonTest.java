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
package io.github.agentic.ai.graph.agent;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.tools.ToolContextConstants;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.util.JsonHelper;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolJsonTest {

	@Test
	void inputSchemaShouldBeWrappedWithSpringAiJsonHelperCompatibleJson() {
		ReactAgent agent = ReactAgent.builder()
			.name("structured_child_agent")
			.description("Structured child agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new CapturingChatModel()))
			.inputSchema("""
					{
						"type": "object",
						"properties": {
							"topic": { "type": "string" },
							"items": {
								"type": "array",
								"items": { "type": "integer" }
							}
						},
						"required": ["topic", "items"]
					}
					""")
			.build();

		Tool callback = AgentTool.create(agent);
		Map<String, Object> wrappedSchema = new JsonHelper().fromJsonToMap(callback.definition().inputSchema());
		Map<String, Object> properties = asMap(wrappedSchema.get("properties"));
		Map<String, Object> input = asMap(properties.get("input"));

		assertEquals("object", wrappedSchema.get("type"));
		assertEquals(List.of("input"), wrappedSchema.get("required"));
		assertEquals("object", input.get("type"));
		assertTrue(asMap(input.get("properties")).containsKey("topic"));
		assertTrue(asMap(input.get("properties")).containsKey("items"));
	}

	@Test
	void objectAndArrayInputShouldBeForwardedAsJsonText() {
		CapturingChatModel chatModel = new CapturingChatModel();
		ReactAgent agent = ReactAgent.builder()
			.name("json_child_agent")
			.description("JSON child agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.build();

		AgentTool.AgentToolExecutor executor = new AgentTool.AgentToolExecutor(agent);
		executor.executeAgent("""
				{
					"input": {
						"topic": "spring",
						"items": [1, 2]
					}
				}
				""", new ToolContext(Map.of()));

		UserMessage userMessage = chatModel.lastPrompt.get().getUserMessage();
		assertEquals("{\"topic\":\"spring\",\"items\":[1,2]}", userMessage.getText());
	}

	@Test
	void callbackShouldAcceptStructuredInputWithoutMethodArgumentConversion() {
		CapturingChatModel chatModel = new CapturingChatModel();
		ReactAgent agent = ReactAgent.builder()
			.name("json_child_agent")
			.description("JSON child agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.inputSchema("""
					{
						"type": "object",
						"properties": {
							"topic": { "type": "string" },
							"items": {
								"type": "array",
								"items": { "type": "integer" }
							}
						},
						"required": ["topic", "items"]
					}
					""")
			.build();

		Tool callback = AgentTool.create(agent);
		ToolResult result = callback.execute(new ToolCall("structured-call", callback.definition().name(), """
				{
					"input": {
						"topic": "spring",
						"items": [1, 2]
					}
				}
				"""), ToolContext.empty()).toCompletableFuture().join();

		UserMessage userMessage = chatModel.lastPrompt.get().getUserMessage();
		assertEquals("{\"topic\":\"spring\",\"items\":[1,2]}", userMessage.getText());
		assertEquals("done",
				result.toMessage(new ToolCall("structured-call", callback.definition().name(), "{}")).text());
		assertFalse(result.error());
	}

	@Test
	void registryShouldForwardParentContextWithoutSharingRuntimeState() throws Exception {
		ReactAgent agent = mockAgent("context_child");
		AtomicReference<RunnableConfig> captured = new AtomicReference<>();
		when(agent.invoke(anyMap(), any(RunnableConfig.class))).thenAnswer(invocation -> {
			captured.set(invocation.getArgument(1));
			return assistantState(ChatMessage.assistant("done"));
		});
		RunnableConfig parent = RunnableConfig.builder()
			.threadId("parent")
			.nextNode("parent-node")
			.checkPointId("parent-checkpoint")
			.addMetadata("business", "value")
			.build();
		parent.context().put("parent-only", "value");
		Tool tool = AgentTool.create(agent);
		new ToolRegistry(List.of(tool))
			.execute(new ToolCall("id", tool.definition().name(), "{\"input\":\"hello\"}"),
					new ToolContext(Map.of(ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY, parent)))
			.toCompletableFuture()
			.join();

		assertEquals("parent_context_child", captured.get().threadId().orElseThrow());
		assertEquals("value", captured.get().metadata("business").orElseThrow());
		assertTrue(captured.get().nextNode().isEmpty());
		assertTrue(captured.get().checkPointId().isEmpty());
		assertTrue(captured.get().context().isEmpty());
		assertEquals("value", parent.context().get("parent-only"));
		assertEquals("parent-node", parent.nextNode().orElseThrow());
	}

	@Test
	void executionFailuresShouldCompleteTheStageExceptionallyWithContext() throws Exception {
		ReactAgent agent = mockAgent("failing_child_agent");
		when(agent.invoke(anyMap())).thenThrow(new IllegalStateException("executor failed"));
		Tool tool = AgentTool.create(agent);
		CompletionException exception = assertThrows(CompletionException.class,
				() -> tool
					.execute(new ToolCall("id", tool.definition().name(), "{\"input\":\"hello\"}"), ToolContext.empty())
					.toCompletableFuture()
					.join());

		assertTrue(exception.getCause().getMessage().contains("failing_child_agent"));
		assertTrue(exception.getCause().getMessage().contains("input=hello"));
		assertEquals("executor failed", exception.getCause().getCause().getMessage());
	}

	@Test
	void resultShouldRetainStructuredContentAndMetadataIncludingEmptyResponses() throws Exception {
		for (List<ContentPart> content : List.of(List.<ContentPart>of(), List.of(ContentPart.json("{\"ok\":true}"),
				ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2 })))) {
			ReactAgent agent = mockAgent("content_child");
			ChatMessage message = new ChatMessage(ChatRole.ASSISTANT, content, Map.of("source", "child"));
			when(agent.invoke(anyMap())).thenReturn(assistantState(message));
			Tool tool = AgentTool.create(agent);
			ToolResult result = tool
				.execute(new ToolCall("id", tool.definition().name(), "{\"input\":\"hello\"}"), ToolContext.empty())
				.toCompletableFuture()
				.join();
			assertEquals(content, result.content());
			assertEquals(message.metadata(), result.metadata());
			assertFalse(result.error());
		}
	}

	@Test
	void cancellationShouldInterruptBlockingChildInvocation() throws Exception {
		ReactAgent agent = mockAgent("blocking_child");
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		when(agent.invoke(anyMap())).thenAnswer(invocation -> {
			started.countDown();
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException exception) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Child invocation cancelled", exception);
			}
			return Optional.empty();
		});
		Tool tool = AgentTool.create(agent);
		var future = new ToolRegistry(List.of(tool))
			.execute(new ToolCall("id", tool.definition().name(), "{}"), ToolContext.empty())
			.toCompletableFuture();
		try {
			assertTrue(started.await(5, TimeUnit.SECONDS), "Tool execution must be asynchronous");
			assertTrue(future.cancel(true));
			assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Cancellation must interrupt the child worker");
			assertTrue(future.isCancelled());
		}
		finally {
			future.cancel(true);
		}
	}

	@Test
	void publicSignaturesShouldBeNeutralAndInvalidInputsRejected() {
		for (Class<?> type : List.of(AgentTool.class, AgentTool.AgentToolExecutor.class)) {
			for (var method : type.getDeclaredMethods()) {
				if (Modifier.isPublic(method.getModifiers())) {
					assertFalse(method.toGenericString().contains("org.springframework.ai"));
				}
			}
		}
		assertThrows(NullPointerException.class, () -> AgentTool.create(null));
		assertThrows(NullPointerException.class, () -> new AgentTool.AgentToolExecutor(null));
		Tool tool = AgentTool.create(mockAgent("validation_child"));
		assertThrows(NullPointerException.class, () -> tool.execute(null, ToolContext.empty()));
		assertThrows(NullPointerException.class,
				() -> tool.execute(new ToolCall("id", "validation_child", "{}"), null));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return assertInstanceOf(Map.class, value);
	}

	private static ReactAgent mockAgent(String name) {
		ReactAgent agent = mock(ReactAgent.class);
		when(agent.name()).thenReturn(name);
		when(agent.description()).thenReturn("Child agent");
		return agent;
	}

	private static Optional<OverAllState> assistantState(ChatMessage message) {
		return Optional.of(new OverAllState(Map.of("messages", List.of(message))));
	}

	private static final class CapturingChatModel implements ChatModel {

		private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

		@Override
		public ChatResponse call(Prompt prompt) {
			this.lastPrompt.set(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

}
