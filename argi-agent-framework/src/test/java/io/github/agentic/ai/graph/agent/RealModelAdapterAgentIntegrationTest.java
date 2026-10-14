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
package io.github.agentic.ai.graph.agent;

import com.google.adk.models.ApigeeLlm;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.github.agentic.ai.adapter.agentscope.AgentScopeChatClientAdapter;
import io.github.agentic.ai.adapter.googleadk.GoogleAdkChatClientAdapter;
import io.github.agentic.ai.adapter.langchain4j.LangChain4jChatClientAdapter;
import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.AdapterAgentIntegrationTest.Framework;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs full ReAct loops through native HTTP clients, only with explicit environment
 * credentials.
 */
@EnabledForRealModel
class RealModelAdapterAgentIntegrationTest {

	private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

	@TempDir
	Path checkpointFolder;

	@ParameterizedTest(name = "{0}, streaming={1}")
	@MethodSource("io.github.agentic.ai.graph.agent.AdapterAgentIntegrationTest#frameworksAndModes")
	void realModelExecutesToolAndPersistsPairedHistory(Framework framework, boolean streaming) throws Exception {
		// The model cannot know this token until it receives the executed tool result.
		String token = "argi_" + UUID.randomUUID().toString().replace("-", "");
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition("lookup", "Return the verification token",
				"{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
		when(tool.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.text(token)));
		Path folder = checkpointFolder.resolve(framework + "_" + streaming);
		RunnableConfig config = RunnableConfig.builder().threadId("real-agent-loop").build();
		ReactAgent agent = ReactAgent.builder()
			.name("verification_agent")
			.chatClient(client(framework, streaming))
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.saver(saver(folder))
			.build();
		String question = "Call lookup exactly once with empty arguments. After receiving its result, reply only with "
				+ "the exact token it returns. Do not invent a token or call any more tools.";

		String answer;
		if (streaming) {
			List<ChatMessage> messages = agent.streamMessages(question, config)
				.collectList()
				.block(Duration.ofMinutes(3));
			assertNotNull(messages);
			assertEquals(1, messages.stream().filter(message -> message.role() == ChatRole.TOOL).count());
			answer = messages.stream()
				.filter(message -> message.role() == ChatRole.ASSISTANT && message.toolCalls().isEmpty())
				.map(ChatMessage::text)
				.filter(text -> text != null && !text.isEmpty())
				.reduce("", String::concat);
		}
		else {
			answer = agent.call(question, config).text();
		}

		verify(tool).execute(any(), any());
		assertNotNull(answer);
		assertTrue(answer.contains(token));
		List<?> history = (List<?>) saver(folder).get(config).orElseThrow().getState().get("messages");
		assertEquals(4, history.size());
		ChatMessage invocation = (ChatMessage) history.get(1);
		assertEquals(ChatRole.ASSISTANT, invocation.role());
		assertEquals(1, invocation.toolCalls().size());
		assertEquals("lookup", invocation.toolCalls().get(0).name());
		ChatMessage result = (ChatMessage) history.get(2);
		assertEquals(ChatRole.TOOL, result.role());
		assertEquals(invocation.toolCalls().get(0).id(), result.toolCallId());
		assertEquals(token, result.text());
		ChatMessage finalAnswer = (ChatMessage) history.get(3);
		assertEquals(ChatRole.ASSISTANT, finalAnswer.role());
		assertTrue(finalAnswer.toolCalls().isEmpty());
		assertTrue(finalAnswer.text().contains(token));
	}

	private static FileSystemSaver saver(Path folder) {
		return FileSystemSaver.builder()
			.targetFolder(folder)
			.stateSerializer(new ArgiJacksonStateSerializer(OverAllState::new))
			.build();
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
