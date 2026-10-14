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
package io.github.agentic.ai.graph.node;

import com.google.adk.models.ApigeeLlm;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.github.agentic.ai.adapter.agentscope.AgentScopeChatClientAdapter;
import io.github.agentic.ai.adapter.googleadk.GoogleAdkChatClientAdapter;
import io.github.agentic.ai.adapter.langchain4j.LangChain4jChatClientAdapter;
import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.node.AdapterGraphIntegrationTest.Framework;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises native HTTP implementations; credentials are never supplied through JVM
 * properties.
 */
@EnabledForRealModel
class RealModelAdapterGraphIntegrationTest {

	private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

	@TempDir
	Path checkpointFolder;

	@ParameterizedTest(name = "{0}, streaming={1}")
	@MethodSource("io.github.agentic.ai.graph.node.AdapterGraphIntegrationTest#frameworksAndModes")
	void realModelExecutesToolAndRestoresNeutralCheckpoint(Framework framework, boolean streaming) throws Exception {
		// Only the tool sees this token before execution, so an echoed answer proves the
		// round trip.
		String token = "argi_" + UUID.randomUUID().toString().replace("-", "");
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition("lookup", "Return the verification token",
				"{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
		when(tool.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.text(token)));
		Path folder = checkpointFolder.resolve(framework + "_" + streaming);
		RunnableConfig config = RunnableConfig.builder().threadId("real-model-loop").build();
		CompiledGraph graph = AdapterGraphIntegrationTest.graph(client(framework, streaming),
				new ToolRegistry(List.of(tool)), streaming, folder);
		Map<String, Object> input = Map.of("messages",
				List.of(ChatMessage
					.user("Call lookup exactly once with empty arguments. After receiving its result, reply only with "
							+ "the exact token it returns. Do not invent a token or call any more tools.")));

		List<NodeOutput> outputs = graph.stream(input, config).collectList().block(Duration.ofMinutes(3));

		assertNotNull(outputs);
		verify(tool).execute(any(), any());
		Map<String, Object> persisted = FileSystemSaver.builder()
			.targetFolder(folder)
			.build()
			.get(config)
			.orElseThrow()
			.getState();
		List<ChatMessage> history = ((List<?>) persisted.get("messages")).stream()
			.map(ChatMessage.class::cast)
			.toList();
		assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT),
				history.stream().map(ChatMessage::role).toList());
		ChatMessage toolMessage = history.get(2);
		assertEquals(token, toolMessage.text());
		assertEquals("lookup", toolMessage.toolName());
		assertEquals(history.get(1).toolCalls().get(0).id(), toolMessage.toolCallId());
		ChatResponse response = (ChatResponse) persisted.get(LlmNode.LLM_RESPONSE_KEY);
		assertTrue(response.text().contains(token));
		assertTrue(response.toolCalls().isEmpty());
		assertFalse(persisted.containsKey(NodeOutput.TOKEN_USAGE_KEY));
		if (streaming) {
			List<ChatEvent> events = outputs.stream()
				.filter(StreamingOutput.class::isInstance)
				.map(output -> ((StreamingOutput<?>) output).getOriginData())
				.filter(ChatEvent.class::isInstance)
				.map(ChatEvent.class::cast)
				.toList();
			assertEquals(2, events.stream().filter(event -> event.type() == ChatEvent.Type.RESPONSE).count());
			assertTrue(events.stream()
				.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
				.map(ChatEvent::text)
				.reduce("", String::concat)
				.contains(token));
		}
	}

	private static ChatClient client(Framework framework, boolean streaming) {
		String key = System.getenv("ARGI_API_KEY");
		String model = System.getenv("ARGI_MODEL");
		String baseUrl = System.getenv().getOrDefault("ARGI_BASE_URL", "https://api.openai.com/v1");
		return switch (framework) {
			case SPRING_AI -> new SpringAiChatClientAdapter(OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder()
					.apiKey(key)
					.baseUrl(baseUrl)
					.model(model)
					.timeout(MODEL_TIMEOUT)
					.maxRetries(0)
					.streamUsage(true)
					.build())
				.build());
			case LANGCHAIN4J -> new LangChain4jChatClientAdapter(
					dev.langchain4j.model.openai.OpenAiChatModel.builder()
						.apiKey(key)
						.baseUrl(baseUrl)
						.modelName(model)
						.timeout(MODEL_TIMEOUT)
						.maxRetries(0)
						.build(),
					dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
						.apiKey(key)
						.baseUrl(baseUrl)
						.modelName(model)
						.timeout(MODEL_TIMEOUT)
						.build());
			case GOOGLE_ADK -> new GoogleAdkChatClientAdapter(ApigeeLlm.builder()
				.modelName("apigee/openai/" + model)
				.proxyUrl(baseUrl)
				.customHeaders(Map.of("Authorization", "Bearer " + key))
				.build());
			case AGENTSCOPE ->
				new AgentScopeChatClientAdapter(io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
					.apiKey(key)
					.baseUrl(baseUrl)
					.modelName(model)
					.stream(streaming)
					.generateOptions(GenerateOptions.builder()
						.executionConfig(ExecutionConfig.builder().timeout(MODEL_TIMEOUT).maxAttempts(1).build())
						.build())
					.build());
		};
	}

}
