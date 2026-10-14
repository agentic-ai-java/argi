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

import io.github.agentic.ai.graph.agent.support.OpenAiCompatibleTestModels;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.agent.flow.agent.LlmRoutingAgent;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledForRealModel
class StreamAgentTest {

	private ChatModel chatModel;

	@BeforeEach
	void setUp() {
		this.chatModel = OpenAiCompatibleTestModels.chatModel();
	}

	@Test
	public void testStreamLlmRoutingAgent() throws Exception {
		ReactAgent proseWriterAgent = ReactAgent.builder()
			.name("prose_writer_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("可以写散文文章。")
			.instruction("你是一个知名的作家，擅长写散文。请根据用户的提问进行回答。")
			.outputKey("prose_article")
			.build();

		ReactAgent poemWriterAgent = ReactAgent.builder()
			.name("poem_writer_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("可以写现代诗。")
			.instruction("你是一个知名的诗人，擅长写现代诗。请根据用户的提问进行回答。")
			.outputKey("poem_article")
			.build();

		LlmRoutingAgent blogAgent = LlmRoutingAgent.builder()
			.name("blog_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("可以根据用户给定的主题写文章或作诗。")
			.subAgents(List.of(proseWriterAgent, poemWriterAgent))
			.build();

		try {
			List<NodeOutput> outputs = new ArrayList<>();

			Flux<NodeOutput> result = blogAgent.stream("帮我写一个100字左右的散文");
			result.doOnNext(nodeOutput -> {
				System.out.println(nodeOutput);
				outputs.add(nodeOutput);
			}).then().block();

			assertFalse(outputs.isEmpty());
			var last = outputs.get(outputs.size() - 1);
			var finalState = last.state();
			assertTrue(finalState.value("prose_article").isPresent());
			assertFalse(finalState.value("poem_article").isPresent());
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testStreamMessageAgent() throws Exception {
		ReactAgent proseWriterAgent = ReactAgent.builder()
			.name("prose_writer_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("可以写散文文章。")
			.instruction("你是一个知名的作家，擅长写散文。请根据用户的提问进行回答。")
			.build();

		List<ChatMessage> outputs = new ArrayList<>();

		Flux<ChatMessage> result = proseWriterAgent.streamMessages("帮我写一个100字左右的散文");
		result.doOnNext(message -> {
			System.out.println(message);
			outputs.add(message);
		}).then().block();

		assertFalse(outputs.isEmpty());
	}

	@Test
	public void testStreamMessageLlmRoutingAgent() throws Exception {
		ReactAgent proseWriterAgent = ReactAgent.builder()
			.name("prose_writer_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("可以写散文文章。")
			.instruction("你是一个知名的作家，只写散文，不是散文将直接拒绝写作。请根据用户的提问进行回答。")
			.outputKey("prose_article")
			.build();

		ReactAgent poemWriterAgent = ReactAgent.builder()
			.name("poem_writer_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("可以写现代诗。")
			.instruction("你是一个知名的诗人，只写现代诗，不是现代诗将直接拒绝写作。请根据用户的提问进行回答。")
			.outputKey("poem_article")
			.build();

		LlmRoutingAgent writerAgent = LlmRoutingAgent.builder()
			.name("writer_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("可以根据用户给定的主题写文章或作诗。")
			.subAgents(List.of(proseWriterAgent, poemWriterAgent))
			.build();

		List<ChatMessage> outputs = new ArrayList<>();

		Flux<ChatMessage> result = writerAgent.streamMessages("帮我写一个100字左右的散文");
		result.doOnNext(message -> {
			System.out.println(message);
			outputs.add(message);
		}).then().block();

		assertFalse(outputs.isEmpty());
	}

	@Test
	public void testStreamMessageWithAgentToolFinishedType() throws Exception {
		ReactAgent oddAgent = ReactAgent.builder()
			.name("return_agent0")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("奇数 Agent")
			.instruction("如果是奇数，返回数字111。除此之外不返回任何信息。")
			.build();

		ReactAgent evenAgent = ReactAgent.builder()
			.name("return_agent1")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.description("偶数 Agent")
			.instruction("如果是偶数，返回数字222。除此之外不返回任何信息。")
			.build();

		ReactAgent numberAgent = ReactAgent.builder()
			.name("blog_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.instruction("根据用户输入的数字交给对应的Agent处理。" + "调用完成后，如果结果是 111，则输出333；如果结果是222，则输出444。" + "不要再进行任何额外推理或工具调用。")
			.toolRegistry(new io.github.agentic.ai.api.tool.ToolRegistry(
					List.of(AgentTool.create(oddAgent), AgentTool.create(evenAgent))))
			.build();

		List<ChatMessage> outputs = new ArrayList<>();
		numberAgent.streamMessages("2")
			.filter(message -> message.role() == ChatRole.TOOL)
			.doOnNext(outputs::add)
			.then()
			.block();

		assertFalse(outputs.isEmpty());
	}

}
