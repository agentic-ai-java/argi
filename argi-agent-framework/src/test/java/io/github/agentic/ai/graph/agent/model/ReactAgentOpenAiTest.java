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
package io.github.agentic.ai.graph.agent.model;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.streaming.StreamingOutput;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;
import io.github.agentic.ai.graph.agent.support.OpenAiCompatibleTestModels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledForRealModel
class ReactAgentOpenAiTest {

	private ChatModel chatModel;

	// Inner class for outputType example
	public static class PoemOutput {

		private String title;

		private String content;

		private String style;

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public String getContent() {
			return content;
		}

		public void setContent(String content) {
			this.content = content;
		}

		public String getStyle() {
			return style;
		}

		public void setStyle(String style) {
			this.style = style;
		}

		@Override
		public String toString() {
			return "PoemOutput{" + "title='" + title + '\'' + ", content='" + content + '\'' + ", style='" + style
					+ '\'' + '}';
		}

	}

	@BeforeEach
	void setUp() {
		this.chatModel = OpenAiCompatibleTestModels.chatModel();
	}

	@Test
	public void testReactAgent() throws Exception {
		ReactAgent agent = ReactAgent.builder()
			.name("single_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("帮我写一篇100字左右散文。");
			Optional<OverAllState> result2 = agent.invoke(io.github.agentic.ai.api.chat.ChatMessage.user("帮我写一首现代诗歌。"));
			Optional<OverAllState> result3 = agent.invoke("帮我写一首现代诗歌2。");

			assertTrue(result.isPresent(), "First result should be present");
			OverAllState state1 = result.get();
			assertTrue(state1.value("messages").isPresent(), "Messages should be present in first result");
			assertEquals(2, ((List) state1.value("messages").get()).size(),
					"There should be 2 messages in the first result");
			Object messages1 = state1.value("messages").get();
			assertNotNull(messages1, "Messages should not be null in first result");

			assertTrue(result2.isPresent(), "Second result should be present");
			OverAllState state2 = result2.get();
			assertTrue(state2.value("messages").isPresent(), "Messages should be present in second result");
			assertEquals(4, ((List<?>) state2.value("messages").get()).size(),
					"There should be 2 messages in the first result");
			Object messages2 = state2.value("messages").get();
			assertNotNull(messages2, "Messages should not be null in second result");

			assertNotEquals(messages1, messages2, "Results should be different for different inputs");

			System.out.println(result.get());

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

	@Test
	public void testReactAgentMessage() throws Exception {
		ReactAgent agent = ReactAgent.builder()
			.name("single_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.build();
		var message = agent.call("帮我写一篇100字左右散文。");
		System.out.println(message.text());
	}

	@Test
	public void testReactAgentWithOutputSchema() throws Exception {

		// Customized outputSchema
		String customSchema = """
				{
					"$schema": "https://json-schema.org/draft/2020-12/schema",
					"type": "object",
					"properties": {
						"title": {
							"type": "string"
						},
						"content": {
							"type": "string"
						},
						"style": {
							"type": "string"
						}
					},
					"additionalProperties": false
				}
				""";

		ReactAgent agent = ReactAgent.builder()
			.name("schema_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.outputSchema(customSchema)
			.build();

		var message = agent.call("帮我写一首关于春天的诗歌。");
		assertNotNull(message, "Message should not be null");
		assertNotNull(message.text(), "Message text should not be null");
		System.out.println("=== Output with custom schema ===");
		System.out.println(message.text());

		assertTrue(message.text().contains("title") || message.text().contains("标题"),
				"Output should contain title field");
	}

	@Test
	public void testReactAgentWithOutputType() throws Exception {

		// outputType will be automatically convert to schema
		ReactAgent agent = ReactAgent.builder()
			.name("type_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.outputType(PoemOutput.class)
			.build();

		var message = agent.call("帮我写一首关于秋天的现代诗。");
		assertNotNull(message, "Message should not be null");
		assertNotNull(message.text(), "Message text should not be null");
		System.out.println("=== Output with outputType (auto-generated schema) ===");
		System.out.println(message.text());

		assertTrue(message.text().contains("title") || message.text().contains("content")
				|| message.text().contains("style"), "Output should contain structured fields");
	}

	@Test
	public void testReactAgentWithOutputSchemaAndInvoke() throws Exception {

		String jsonSchema = """
				{
					"$schema": "https://json-schema.org/draft/2020-12/schema",
					"type": "object",
					"properties": {
						"summary": {
							"type": "string"
						},
						"keywords": {
							"type": "array",
							"items": {
								"type": "string"
							}
						},
						"sentiment": {
							"type": "string"
						}
					},
					"additionalProperties": false
				}
				""";

		ReactAgent agent = ReactAgent.builder()
			.name("analysis_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.outputSchema(jsonSchema)
			.build();

		Optional<OverAllState> result = agent.invoke("分析这句话：春天来了，万物复苏，生机勃勃。");

		assertTrue(result.isPresent(), "Result should be present");
		System.out.println("=== Full state output ===");
		System.out.println(result.get());
	}

	@Test
	public void testReactAgentStreamWithCustomChatOptions() throws Exception {
		// Configure custom OpenAiChatOptions with extra body parameters
		OpenAiChatOptions customChatOptions = OpenAiChatOptions.builder()
			.model(System.getenv("ARGI_MODEL"))
			.temperature(0.7)
			.extraBody(Map.of("enable_thinking", true))
			.build();

		// Create ReactAgent with custom chat options
		ReactAgent agent = ReactAgent.builder()
			.name("streaming_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(
					org.springframework.ai.chat.client.ChatClient.builder(chatModel)
						.defaultOptions(customChatOptions.mutate())
						.build()))
			.saver(new MemorySaver())
			.enableLogging(true)
			.build();

		System.out.println("=== Testing stream() with custom chat options ===");
		System.out.println("Model: qwen-plus");
		System.out.println("Temperature: 0.7");
		System.out.println("Extra parameters: top_k=50, repetition_penalty=1.1, frequency_penalty=0.5");
		System.out.println();

		// Use stream() method to get streaming output
		Flux<NodeOutput> stream = agent.stream("帮我写一首关于秋天的短诗，不超过50字。");

		AtomicInteger chunkCount = new AtomicInteger(0);
		StringBuilder completeContent = new StringBuilder();

		stream.doOnNext(output -> {
			assertNotNull(output, "NodeOutput should not be null");
			System.out.println("节点: " + output.node() + ", Agent: " + output.agent());

			if (output instanceof StreamingOutput<?> streamingOutput) {
				// Handle streaming output
				if (!streamingOutput.text().isEmpty()) {
					String content = streamingOutput.text();
					if (!content.isEmpty()) {
						System.out.print(content);
						completeContent.append(content);
						chunkCount.incrementAndGet();
					}
				}
			}
			else {
				// Handle final node output
				System.out.println("\n[节点完成] State: " + output.state());
			}
		}).doOnComplete(() -> {
			System.out.println("\n\n=== Stream completed ===");
			System.out.println("Total chunks: " + chunkCount.get());
			System.out.println("Complete content length: " + completeContent.length());
			assertTrue(chunkCount.get() > 0, "Should have received at least one streaming chunk");
			assertTrue(completeContent.length() > 0, "Should have received some content");
		}).doOnError(error -> {
			System.err.println("Error during streaming: " + error.getMessage());
			error.printStackTrace();
			fail("Stream execution failed: " + error.getMessage());
		}).blockLast(); // Block to wait for completion in test
	}

	private static CompileConfig getCompileConfig() {
		SaverConfig saverConfig = SaverConfig.builder().register(new MemorySaver()).build();
		CompileConfig compileConfig = CompileConfig.builder().saverConfig(saverConfig).build();
		return compileConfig;
	}

}
