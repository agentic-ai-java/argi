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
package io.github.agentic.ai.graph.agent.hook.messages;

import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.InstructionAgentHook;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.serializer.AgentInstructionMessage;

import org.springframework.ai.chat.messages.AssistantMessage;
import io.github.agentic.ai.api.chat.ChatMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the "exactly one instruction" invariant of {@link InstructionAgentHook}: when
 * the incoming messages already carry the same instruction text (e.g. injected manually
 * by a caller such as AgentTool, see issue #77), the hook must not append another copy.
 */
class InstructionAgentHookIdempotencyTest {

	private static final String INSTRUCTION = "You are a product support assistant.";

	private static class NoopChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("mock"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.empty();
		}

	}

	private InstructionAgentHook hookFor(String instruction) {
		ReactAgent agent = ReactAgent.builder()
			.name("instr-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new NoopChatModel()))
			.saver(new MemorySaver())
			.instruction(instruction)
			.build();
		InstructionAgentHook hook = InstructionAgentHook.create();
		hook.setAgent(agent);
		return hook;
	}

	private long countInstruction(List<ChatMessage> messages, String text) {
		return messages.stream()
			.filter(AgentInstructionMessage::isTemplate)
			.map(message -> message.text())
			.filter(text::equals)
			.count();
	}

	@Test
	void duplicateSameTextInstructionIsNormalizedToSingleCopy() {
		InstructionAgentHook hook = hookFor(INSTRUCTION);
		List<ChatMessage> previous = new ArrayList<>(
				List.of(AgentInstructionMessage.builder().text(INSTRUCTION).build(), ChatMessage.user("hello"),
						AgentInstructionMessage.builder().text(INSTRUCTION).build()));

		AgentCommand command = hook.beforeAgent(previous, null);

		assertEquals(1, countInstruction(command.getMessages(), INSTRUCTION));
	}

	@Test
	void missingInstructionIsInjectedOnce() {
		InstructionAgentHook hook = hookFor(INSTRUCTION);
		List<ChatMessage> previous = new ArrayList<>(List.of(ChatMessage.user("hello")));

		AgentCommand command = hook.beforeAgent(previous, null);

		assertEquals(1, countInstruction(command.getMessages(), INSTRUCTION));
	}

	@Test
	void agentWithoutInstructionLeavesMessagesUnchanged() {
		InstructionAgentHook hook = hookFor("");
		List<ChatMessage> previous = new ArrayList<>(List.of(ChatMessage.user("hello")));

		AgentCommand command = hook.beforeAgent(previous, null);

		assertEquals(previous, command.getMessages());
	}

	@Test
	void differentTextInstructionIsKeptAndCurrentInstructionAppendedOnce() {
		InstructionAgentHook hook = hookFor(INSTRUCTION);
		List<ChatMessage> previous = new ArrayList<>(List
			.of(AgentInstructionMessage.builder().text("other agent instruction").build(), ChatMessage.user("hello")));

		AgentCommand command = hook.beforeAgent(previous, null);

		assertEquals(1, countInstruction(command.getMessages(), "other agent instruction"));
		assertEquals(1, countInstruction(command.getMessages(), INSTRUCTION));
	}

	@Test
	void renderedOwnCopyFromPreviousTurnIsReplacedByFreshInstruction() {
		InstructionAgentHook hook = hookFor(INSTRUCTION);
		// Simulate a previous turn: the hook injected the raw template, the model node
		// rendered it
		// in place (text mutated, rendered flag set, ownership metadata preserved).
		List<ChatMessage> previous = new ArrayList<>(List.of(AgentInstructionMessage.builder()
			.text("You are a product support assistant for Bob.")
			.metadata(Map.of(InstructionAgentHook.AGENT_NAME_METADATA_KEY, "instr-agent"))
			.rendered(true)
			.build(), ChatMessage.user("hello")));

		AgentCommand command = hook.beforeAgent(previous, null);

		long total = command.getMessages().stream().filter(AgentInstructionMessage::isTemplate).count();
		assertEquals(1, total, "the stale rendered copy must be replaced by a single fresh instruction");
	}

	@Test
	void sameTextInstructionOwnedByAnotherAgentIsNeverRemoved() {
		// A child agent sharing the parent's exact instruction text must not dedupe away
		// the parent-owned copy from a shared message history: ownership metadata wins
		// over text equality (review finding on the instruction dedup PR).
		ReactAgent agent = ReactAgent.builder()
			.name("child")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new NoopChatModel()))
			.saver(new MemorySaver())
			.instruction(INSTRUCTION)
			.build();
		InstructionAgentHook hook = InstructionAgentHook.create();
		hook.setAgent(agent);

		List<ChatMessage> previous = new ArrayList<>(List.of(AgentInstructionMessage.builder()
			.text(INSTRUCTION)
			.metadata(Map.of(InstructionAgentHook.AGENT_NAME_METADATA_KEY, "parent"))
			.build(), ChatMessage.user("hello")));

		AgentCommand command = hook.beforeAgent(previous, null);

		long parentOwned = command.getMessages()
			.stream()
			.filter(AgentInstructionMessage::isTemplate)
			.filter(message -> "parent".equals(message.metadata().get(InstructionAgentHook.AGENT_NAME_METADATA_KEY)))
			.count();
		assertEquals(1, parentOwned, "the parent-owned instruction must survive the child's dedup");
		assertEquals(2, countInstruction(command.getMessages(), INSTRUCTION),
				"the retained parent copy plus exactly one fresh child copy");
	}

}
