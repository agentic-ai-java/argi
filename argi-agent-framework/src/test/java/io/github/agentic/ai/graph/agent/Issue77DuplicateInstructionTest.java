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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for <a href="https://github.com/agentic-ai-java/argi/issues/77">issue
 * #77</a>: when a ReactAgent with an instruction runs through {@link AgentTool}, the
 * instruction used to be injected twice (once manually by the tool executor, once by the
 * default InstructionAgentHook), so the model context carried two copies. It must appear
 * exactly once.
 */
class Issue77DuplicateInstructionTest {

	private static final String INSTRUCTION = "You are a product support assistant.";

	private static class CapturingChatModel implements ChatModel {

		final List<Prompt> prompts = new CopyOnWriteArrayList<>();

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompts.add(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			this.prompts.add(prompt);
			return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
		}

	}

	@Test
	void instructionAppearsExactlyOnceWhenAgentRunsAsTool() {
		CapturingChatModel model = new CapturingChatModel();
		ReactAgent childAgent = ReactAgent.builder()
			.name("issue77_child")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.saver(new MemorySaver())
			.instruction(INSTRUCTION)
			.build();

		AgentTool.AgentToolExecutor executor = new AgentTool.AgentToolExecutor(childAgent);
		ChatMessage result = executor.executeAgent("{\"input\": \"hello\"}", ToolContext.empty());

		assertNotNull(result);
		assertTrue(!model.prompts.isEmpty(), "the child agent must call the model at least once");
		for (Prompt prompt : model.prompts) {
			long instructionCopies = prompt.getInstructions()
				.stream()
				.map(Message::getText)
				.filter(INSTRUCTION::equals)
				.count();
			assertEquals(1, instructionCopies, "each model call must carry the instruction exactly once");
		}
	}

	@Test
	void userMessageIsStillPassedThroughWhenInstructionIsInjectedOnce() {
		CapturingChatModel model = new CapturingChatModel();
		ReactAgent childAgent = ReactAgent.builder()
			.name("issue77_child_user")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(model))
			.saver(new MemorySaver())
			.instruction(INSTRUCTION)
			.build();

		AgentTool.AgentToolExecutor executor = new AgentTool.AgentToolExecutor(childAgent);
		executor.executeAgent("{\"input\": \"hello\"}", ToolContext.empty());

		long userMessageCopies = model.prompts.stream()
			.flatMap(prompt -> prompt.getInstructions().stream())
			.filter(message -> message.getClass() == UserMessage.class)
			.map(Message::getText)
			.filter("hello"::equals)
			.count();
		assertEquals(1, userMessageCopies, "the user input must not be duplicated either");
	}

}
