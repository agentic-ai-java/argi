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
package io.github.agentic.ai.graph.agent.hooks.pii;

import io.github.agentic.ai.graph.agent.support.OpenAiCompatibleTestModels;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.pii.PIIDetectionHook;
import io.github.agentic.ai.graph.agent.hook.pii.PIIDetectors;
import io.github.agentic.ai.graph.agent.hook.pii.PIIType;
import io.github.agentic.ai.graph.agent.hook.pii.RedactionStrategy;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledForRealModel
public class PIIDectionHookTest {

	private ChatModel chatModel;

	@BeforeEach
	void setUp() {
		this.chatModel = OpenAiCompatibleTestModels.chatModel();
	}

	@Test
	public void testPIIDetectionWithRedactStrategy() throws Exception {
		PIIDetectionHook hook = PIIDetectionHook.builder()
			.piiType(PIIType.EMAIL)
			.strategy(RedactionStrategy.REDACT)
			.applyToInput(true)
			.applyToOutput(true)
			.build();

		ReactAgent agent = createAgent(hook, "test-pii-redact-agent", chatModel);

		System.out.println("=== 测试PII检测（REDACT策略）===");

		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.user("我的邮箱地址是 test@example.com，请记住它。"));

		Optional<OverAllState> result = agent.invoke(messages);

		assertTrue(result.isPresent(), "结果应该存在");
		Object messagesObj = result.get().value("messages").get();
		assertNotNull(messagesObj, "消息应该存在于结果中");

		if (messagesObj instanceof List) {
			List<ChatMessage> resultMessages = (List<ChatMessage>) messagesObj;
			System.out.println("返回消息数量: " + resultMessages.size());

			for (ChatMessage message : resultMessages) {
				if (message.role() == ChatRole.USER) {
					String content = message.text();
					if (content.contains("[REDACTED_EMAIL]")) {
						System.out.println("✓ 成功检测并替换用户消息中的邮箱地址");
					}
				}
				else if (message.role() == ChatRole.ASSISTANT) {
					String content = message.text();
					System.out.println("AI回复: " + content);
				}
			}
		}
	}

	@Test
	public void testPIIDetectionWithMaskStrategy() throws Exception {
		PIIDetectionHook hook = PIIDetectionHook.builder()
			.piiType(PIIType.CREDIT_CARD)
			.strategy(RedactionStrategy.MASK)
			.applyToInput(true)
			.applyToOutput(true)
			.build();

		ReactAgent agent = createAgent(hook, "test-pii-mask-agent", chatModel);

		System.out.println("\n=== 测试PII检测（MASK策略）===");

		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.user("我的信用卡号是 1234 5678 9012 3456，请帮我检查一下。"));

		Optional<OverAllState> result = agent.invoke(messages);

		assertTrue(result.isPresent(), "结果应该存在");
		Object messagesObj = result.get().value("messages").get();
		assertNotNull(messagesObj, "消息应该存在于结果中");

		if (messagesObj instanceof List) {
			List<ChatMessage> resultMessages = (List<ChatMessage>) messagesObj;
			System.out.println("返回消息数量: " + resultMessages.size());
			for (ChatMessage message : resultMessages) {
				if (message.role() == ChatRole.USER) {
					String content = message.text();
					if (content.contains("****") && content.contains("3456")) {
						System.out.println("成功检测并部分掩码用户消息中的信用卡号");
					}
					System.out.println("处理后的用户消息: " + content);
				}
			}
		}
	}

	@Test
	public void testPIIDetectionWithBlockStrategy() throws Exception {
		PIIDetectionHook hook = PIIDetectionHook.builder()
			.piiType(PIIType.IP)
			.strategy(RedactionStrategy.BLOCK)
			.applyToInput(true)
			.build();

		ReactAgent agent = createAgent(hook, "test-pii-block-agent", chatModel);

		System.out.println("\n=== 测试PII检测（BLOCK策略）===");

		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.user("我的服务器IP地址是 192.168.1.100，请不要泄露。"));

		try {
			Optional<OverAllState> result = agent.invoke(messages);
			System.out.println("未抛出异常，可能IP未被正确检测");
		}
		catch (Exception e) {
			if (e.getCause() instanceof io.github.agentic.ai.graph.agent.hook.pii.PIIDetectionException) {
				System.out.println("✓ 成功检测到IP地址并阻止处理: " + e.getCause().getMessage());
			}
			else {
				System.out.println("抛出其他异常: " + e.getMessage());
			}
		}
	}

	@Test
	public void testWithoutPIIDetection() throws Exception {
		ReactAgent agent = ReactAgent.builder()
			.name("test-no-pii-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.build();

		System.out.println("\n=== 测试不带PII检测的对话 ===");

		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.user("你好，有什么可以帮助你的吗？"));

		Optional<OverAllState> result = agent.invoke(messages);

		assertTrue(result.isPresent(), "结果应该存在");
		Object messagesObj = result.get().value("messages").get();
		assertNotNull(messagesObj, "消息应该存在于结果中");

		if (messagesObj instanceof List) {
			List<ChatMessage> resultMessages = (List<ChatMessage>) messagesObj;
			System.out.println("返回消息数量: " + resultMessages.size());
			System.out.println("✓ 正常对话流程，未触发PII检测");
		}
	}

	@Test
	public void testCustomPIIDetector() throws Exception {
		PIIDetectionHook hook = PIIDetectionHook.builder()
			.piiType(PIIType.CUSTOM)
			.strategy(RedactionStrategy.REDACT)
			.detector(PIIDetectors.regexDetector("PHONE", "\\b1[3-9]\\d{9}\\b"))
			.applyToInput(true)
			.build();

		ReactAgent agent = createAgent(hook, "test-custom-pii-agent", chatModel);

		System.out.println("\n=== 测试自定义PII检测器 ===");

		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.user("我的手机号码是 13812345678，请保存。"));

		Optional<OverAllState> result = agent.invoke(messages);

		assertTrue(result.isPresent(), "结果应该存在");
		Object messagesObj = result.get().value("messages").get();
		assertNotNull(messagesObj, "消息应该存在于结果中");

		if (messagesObj instanceof List) {
			List<ChatMessage> resultMessages = (List<ChatMessage>) messagesObj;
			System.out.println("返回消息数量: " + resultMessages.size());

			for (ChatMessage message : resultMessages) {
				if (message.role() == ChatRole.USER) {
					String content = message.text();
					if (content.contains("[REDACTED_PHONE]")) {
						System.out.println("成功检测并替换用户消息中的手机号码");
					}
					System.out.println("处理后的用户消息: " + content);
				}
			}
		}
	}

	public ReactAgent createAgent(PIIDetectionHook hook, String name, ChatModel model) throws Exception {
		return ReactAgent.builder()
			.name(name)
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.hooks(List.of(hook))
			.saver(new MemorySaver())
			.build();
	}

}
