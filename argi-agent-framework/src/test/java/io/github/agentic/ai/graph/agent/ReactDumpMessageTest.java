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
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.graph.skills.ArgiSkillChatClient;
import io.github.agentic.ai.graph.agent.flow.agent.SequentialAgent;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.agent.hook.skills.ReadSkillTool;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.skills.registry.SkillRegistry;
import io.github.agentic.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;
import org.springframework.ai.chat.model.ChatModel;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.tool.ToolRegistry;

import java.util.List;
import java.util.Optional;

/**
 * 验证 SequentialAgent 多轮对话时 messages 的累积行为。
 * <p>
 * 流程：用户输入 → 市场分析Agent → 商品搜索Agent → 输出 通过 MessagesModelHook 在每次调用模型前后打印 messages 快照，
 * 观察第二轮对话时 messages 是否出现重复。
 *
 * @author haojun.phj（ Jackie ）
 * @since 2026/3/9
 */
@EnabledForRealModel
public class ReactDumpMessageTest {

	private ChatModel chatModel;

	@BeforeEach
	public void setUp() {

		this.chatModel = OpenAiCompatibleTestModels.chatModel();
	}

	@Test
	void testSequentialAgentMultiRoundMessages() throws Exception {
		MessageMonitorHook monitorA = new MessageMonitorHook("A-市场分析");
		MessageMonitorHook monitorB = new MessageMonitorHook("B-商品搜索");

		MemorySaver saver = new MemorySaver();

		// 方案一验证：子 Agent 不配 saver，只让 SequentialAgent 管理状态
		ReactAgent agentA = ReactAgent.builder()
			.name("market-analysis")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.systemPrompt("无论用户说什么，只回复「市场OK」三个字，不要多说。")
			.hooks(List.of(monitorA))
			.build();

		ReactAgent agentB = ReactAgent.builder()
			.name("product-search")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.systemPrompt("无论用户说什么，只回复「商品OK」三个字，不要多说。")
			.hooks(List.of(monitorB))
			.build();

		SequentialAgent pipeline = SequentialAgent.builder()
			.name("pipeline")
			.subAgents(List.of(agentA, agentB))
			.saver(saver)
			.build();

		RunnableConfig config = RunnableConfig.builder().threadId("test-1").build();

		// ==================== 第一轮 ====================
		log("第一轮", "AAA");
		Optional<OverAllState> result1 = pipeline.invoke("AAA", config);
		logResult("第一轮", result1);

		// ==================== 第二轮 ====================
		log("第二轮", "BBB");
		Optional<OverAllState> result2 = pipeline.invoke("BBB", config);
		logResult("第二轮", result2);
	}

	// ======================== 日志工具 ========================

	private static void log(String round, String input) {
		System.out.println("\n============================");
		System.out.println(round + " | 用户输入: \"" + input + "\"");
		System.out.println("============================");
	}

	private static void logResult(String round, Optional<OverAllState> result) {
		result.ifPresent(state -> {
			@SuppressWarnings("unchecked")
			List<ChatMessage> messages = (List<ChatMessage>) state.value("messages").orElse(List.of());
			System.out.println("\n--- " + round + " 结束 | messages 共 " + messages.size() + " 条 ---");
			dumpMessages(messages);
			System.out.println("-------------------------------------------");
		});
	}

	private static void dumpMessages(List<ChatMessage> messages) {
		for (int i = 0; i < messages.size(); i++) {
			ChatMessage msg = messages.get(i);
			System.out.println("  " + i + ". [" + label(msg) + "] " + brief(msg));
		}
	}

	private static String label(ChatMessage msg) {
		return msg.role().name();
	}

	private static String brief(ChatMessage msg) {
		String text = msg.text();
		return text.length() > 60 ? text.substring(0, 60) + "..." : text;
	}

	// ======================== ChatClient + read_skill 流式调用 ========================

	@Test
	void testChatClientStreamWithReadSkill() throws Exception {
		String skillsDir = getClass().getClassLoader().getResource("skills").getFile();
		SkillRegistry skillRegistry = FileSystemSkillRegistry.builder().projectSkillsDirectory(skillsDir).build();
		ArgiSkillChatClient client = new ArgiSkillChatClient(new SpringAiChatClientAdapter(chatModel), skillRegistry);
		// Prompt summaries and tool reads use the same registry; ARGI owns the tool loop.
		var readSkillTool = ReadSkillTool.createReadSkillTool(skillRegistry, null);
		ReactAgent agent = ReactAgent.builder()
			.name("skill-writer")
			.chatClient(client)
			.systemPrompt("Use read_skill to select a writing template, then write a concise ecommerce report.")
			.toolRegistry(new ToolRegistry(List.of(readSkillTool)))
			.build();
		List<io.github.agentic.ai.api.chat.ChatMessage> result = agent
			.streamMessages("Analyze summer sunscreen products and write a marketing sentence.")
			.collectList()
			.block(java.time.Duration.ofSeconds(120));
		org.junit.jupiter.api.Assertions.assertNotNull(result);
		org.junit.jupiter.api.Assertions.assertFalse(result.isEmpty());
	}

	// ======================== Hook ========================

	@HookPositions({ HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL })
	static class MessageMonitorHook extends MessagesModelHook {

		private final String tag;

		MessageMonitorHook(String tag) {
			this.tag = tag;
		}

		@Override
		public AgentCommand beforeModel(List<ChatMessage> messages, RunnableConfig config) {
			System.out.println("\n  >> " + tag + " 调用模型前 | " + messages.size() + " 条消息");
			dumpMessages(messages);
			return new AgentCommand(messages);
		}

		@Override
		public AgentCommand afterModel(List<ChatMessage> messages, RunnableConfig config) {
			System.out.println("\n  << " + tag + " 模型返回后 | " + messages.size() + " 条消息");
			dumpMessages(messages);
			return new AgentCommand(messages);
		}

		@Override
		public String getName() {
			return tag;
		}

	}

}
