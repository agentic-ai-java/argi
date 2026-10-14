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
package io.github.agentic.ai.graph.agent.memory;

import io.github.agentic.ai.graph.agent.support.OpenAiCompatibleTestModels;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.agent.hook.messages.UpdatePolicy;
import io.github.agentic.ai.graph.agent.tools.WeatherTool;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import io.github.agentic.ai.api.chat.ChatMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for ReactAgent short-term memory functionality using MemorySaver and threadId
 *
 * Short-term memory allows the agent to remember previous interactions within a single
 * thread/session. This test demonstrates: 1. Basic memory retention across multiple calls
 * with same threadId 2. Memory isolation between different threads 3. ChatMessage
 * trimming to manage context window 4. ChatMessage deletion for memory cleanup
 */
@EnabledForRealModel
class ShortTermMemoryTest {

	private ChatModel chatModel;

	private ToolCallback weatherTool;

	@BeforeEach
	void setUp() {
		this.chatModel = OpenAiCompatibleTestModels.chatModel();

		// Create weather tool
		this.weatherTool = WeatherTool.createWeatherTool("getWeather", new WeatherTool());
	}

	/**
	 * Test 1: Basic short-term memory - agent remembers user's name across multiple
	 * interactions
	 */
	@Test
	void testBasicShortTermMemory() throws Exception {
		// Create agent with MemorySaver
		ReactAgent agent = ReactAgent.builder()
			.name("memory_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.build();

		// Create config with threadId to maintain conversation context
		RunnableConfig config = RunnableConfig.builder().threadId("conversation_1").build();

		// First interaction: introduce name
		Optional<OverAllState> result1 = agent.invoke("你好！我叫张三。", config);
		assertTrue(result1.isPresent(), "First response should be present");

		ChatMessage response1 = (ChatMessage) result1.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();
		System.out.println("Response 1: " + response1.text());
		assertTrue(response1.text().contains("张三") || response1.text().contains("你好"),
				"Agent should acknowledge the greeting");

		// Second interaction: ask for name
		Optional<OverAllState> result2 = agent.invoke("我叫什么名字？", config);
		assertTrue(result2.isPresent(), "Second response should be present");

		ChatMessage response2 = (ChatMessage) result2.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();
		System.out.println("Response 2: " + response2.text());
		assertTrue(response2.text().contains("张三"), "Agent should remember the name from previous interaction");
	}

	/**
	 * Test 2: Memory isolation - different threads maintain separate contexts
	 */
	@Test
	void testMemoryIsolationBetweenThreads() throws Exception {
		ReactAgent agent = ReactAgent.builder()
			.name("memory_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.build();

		// Thread 1: User named Alice
		RunnableConfig config1 = RunnableConfig.builder().threadId("thread_alice").build();

		agent.invoke("你好，我叫 Alice。", config1);

		// Thread 2: User named Bob
		RunnableConfig config2 = RunnableConfig.builder().threadId("thread_bob").build();

		agent.invoke("你好，我叫 Bob。", config2);

		// Ask for name in thread 1 - should be Alice
		Optional<OverAllState> result1 = agent.invoke("我叫什么名字？", config1);
		ChatMessage response1 = (ChatMessage) result1.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();
		System.out.println("Thread 1 response: " + response1.text());
		assertTrue(response1.text().contains("Alice"), "Thread 1 should remember Alice");

		// Ask for name in thread 2 - should be Bob
		Optional<OverAllState> result2 = agent.invoke("我叫什么名字？", config2);
		ChatMessage response2 = (ChatMessage) result2.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();
		System.out.println("Thread 2 response: " + response2.text());
		assertTrue(response2.text().contains("Bob"), "Thread 2 should remember Bob");
	}

	/**
	 * Test 3: ChatMessage trimming - keep context window manageable using
	 * MessagesModelHook. Before each model call, keeps the first message (e.g. system)
	 * and the last 4 messages when over threshold.
	 */
	@Test
	void testMessageTrimming() throws Exception {
		MessageTrimmingMessagesHook messageTrimmingHook = new MessageTrimmingMessagesHook();

		ReactAgent agent = ReactAgent.builder()
			.name("trimming_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.hooks(messageTrimmingHook)
			.saver(new MemorySaver())
			.build();

		RunnableConfig config = RunnableConfig.builder().threadId("trimming_thread").build();

		// Have multiple interactions
		agent.invoke("你好，我叫 Bob", config);
		agent.invoke("我喜欢猫", config);
		agent.invoke("我也喜欢狗", config);
		agent.invoke("给我讲个笑话", config);

		// After multiple interactions, the message count should be trimmed
		Optional<OverAllState> result = agent.invoke("我叫什么名字？", config);
		ChatMessage response = (ChatMessage) result.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();

		System.out.println("Response after trimming: " + response.text());
		// Note: Due to trimming, the agent might not remember the name
		// This demonstrates the trade-off between memory and context window size
	}

	/**
	 * Test 4: ChatMessage deletion - clear old messages using MessagesModelHook. After
	 * each model response, the hook keeps only the last 4 messages (REPLACE policy).
	 */
	@Test
	void testMessageDeletion() throws Exception {
		MessageDeletionMessagesHook messageDeletionHook = new MessageDeletionMessagesHook();

		ReactAgent agent = ReactAgent.builder()
			.name("deletion_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.hooks(messageDeletionHook)
			.saver(new MemorySaver())
			.build();

		RunnableConfig config = RunnableConfig.builder().threadId("deletion_thread").build();

		// Have multiple interactions
		agent.invoke("记住：密码是 12345", config);
		agent.invoke("再记住：我的邮箱是 test@example.com", config);
		agent.invoke("今天天气怎么样？", config);

		// After deletion hook, old messages should be removed
		Optional<OverAllState> result = agent.invoke("密码是什么？", config);
		ChatMessage response = (ChatMessage) result.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();

		System.out.println("Response after deletion: " + response.text());
		// Due to message deletion, the agent should not remember the password
		assertFalse(response.text().contains("12345"), "Old messages should be deleted");
	}

	/**
	 * MessagesModelHook that deletes old messages after each model response: keeps only
	 * the last 4 messages when there are more than 4.
	 */
	@HookPositions(HookPosition.AFTER_MODEL)
	private static class MessageDeletionMessagesHook extends MessagesModelHook {

		private static final int KEEP_LAST = 4;

		@Override
		public String getName() {
			return "message_deletion";
		}

		@Override
		public AgentCommand afterModel(List<ChatMessage> previousMessages, RunnableConfig config) {
			System.out.println("ChatMessage count before deletion: " + previousMessages.size());

			if (previousMessages.size() > KEEP_LAST) {
				List<ChatMessage> recentMessages = new ArrayList<>(
						previousMessages.subList(previousMessages.size() - KEEP_LAST, previousMessages.size()));
				System.out.println("ChatMessage count after deletion: " + recentMessages.size());
				return new AgentCommand(recentMessages, UpdatePolicy.REPLACE);
			}

			return new AgentCommand(previousMessages);
		}

	}

	/**
	 * MessagesModelHook that trims messages before each model call: keeps the first
	 * message (e.g. system) and the last 4 messages when total count exceeds
	 * MAX_MESSAGES.
	 */
	@HookPositions(HookPosition.BEFORE_MODEL)
	private static class MessageTrimmingMessagesHook extends MessagesModelHook {

		private static final int MAX_MESSAGES = 5;

		private static final int KEEP_LAST = 4;

		@Override
		public String getName() {
			return "message_trimming";
		}

		@Override
		public AgentCommand beforeModel(List<ChatMessage> previousMessages, RunnableConfig config) {
			System.out.println("Current message count: " + previousMessages.size());

			if (previousMessages.size() <= MAX_MESSAGES) {
				return new AgentCommand(previousMessages);
			}

			ChatMessage firstMsg = previousMessages.get(0);
			List<ChatMessage> recentMessages = previousMessages.subList(previousMessages.size() - KEEP_LAST,
					previousMessages.size());

			List<ChatMessage> newMessages = new ArrayList<>();
			newMessages.add(firstMsg);
			newMessages.addAll(recentMessages);

			System.out.println("Trimmed message count: " + newMessages.size());
			return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
		}

	}

	/**
	 * Test 5: Memory with tools - agent remembers context while using tools
	 */
	@Test
	void testMemoryWithTools() throws Exception {
		ReactAgent agent = ReactAgent.builder()
			.name("tool_memory_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherTool))
			.saver(new MemorySaver())
			.build();

		RunnableConfig config = RunnableConfig.builder().threadId("tool_thread").build();

		// First interaction: user provides their location
		agent.invoke("我住在北京。", config);

		// Second interaction: ask about weather (agent should remember location)
		Optional<OverAllState> result = agent.invoke("我这里的天气怎么样？", config);
		assertTrue(result.isPresent(), "Response should be present");

		ChatMessage response = (ChatMessage) result.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();

		System.out.println("Weather response: " + response.text());
		// Agent should use the weather tool and remember the user is in Beijing
	}

	/**
	 * Test 6: Resume conversation from checkpoint
	 */
	@Test
	void testResumeConversation() throws Exception {
		MemorySaver saver = new MemorySaver();
		String threadId = "resume_thread";

		// Create first agent instance
		ReactAgent agent1 = ReactAgent.builder()
			.name("resume_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(saver)
			.build();

		RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

		// Have a conversation
		agent1.invoke("你好，我叫李明，今年25岁。", config);

		// Create a new agent instance with the same saver and threadId
		ReactAgent agent2 = ReactAgent.builder()
			.name("resume_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(saver)
			.build();

		// Continue the conversation with new agent instance
		Optional<OverAllState> result = agent2.invoke("我叫什么名字？多大了？", config);
		ChatMessage response = (ChatMessage) result.get()
			.value("messages")
			.map(m -> ((List<ChatMessage>) m).get(((List<ChatMessage>) m).size() - 1))
			.orElseThrow();

		System.out.println("Resumed conversation response: " + response.text());
		assertTrue(response.text().contains("李明") || response.text().contains("25"),
				"Agent should remember information from previous conversation");
	}

}
