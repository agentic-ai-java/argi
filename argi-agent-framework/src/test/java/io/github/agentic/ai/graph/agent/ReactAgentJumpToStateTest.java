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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import io.github.agentic.ai.api.chat.ChatMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ReactAgentJumpToStateTest {

	@Test
	void consumedJumpToDoesNotLeakIntoNextModelLoop() throws Exception {
		CountingChatModel chatModel = new CountingChatModel();
		JumpBackOnceHook hook = new JumpBackOnceHook();
		ReactAgent agent = ReactAgent.builder()
			.name("jump-to-state-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.hooks(List.of(hook))
			.saver(new MemorySaver())
			.build();

		Optional<OverAllState> result = agent.invoke("start",
				RunnableConfig.builder().threadId("jump-to-state-thread").addMetadata("_stream_", false).build());

		assertTrue(result.isPresent());
		assertEquals(2, hook.afterModelCalls.get());
		assertEquals(2, chatModel.calls.get());
		assertFalse(result.get().value("jump_to").isPresent());
	}

	@HookPositions(HookPosition.AFTER_MODEL)
	private static final class JumpBackOnceHook extends MessagesModelHook {

		private final AtomicInteger afterModelCalls = new AtomicInteger();

		@Override
		public String getName() {
			return "jump_back_once_hook";
		}

		@Override
		public List<JumpTo> canJumpTo() {
			return List.of(JumpTo.model);
		}

		@Override
		public AgentCommand afterModel(List<ChatMessage> previousMessages, RunnableConfig config) {
			int calls = afterModelCalls.incrementAndGet();
			if (calls == 1) {
				return new AgentCommand(JumpTo.model, previousMessages);
			}
			if (calls == 2) {
				return new AgentCommand(previousMessages);
			}
			fail("jump_to leaked after it was consumed and routed back to the model");
			return new AgentCommand(previousMessages);
		}

	}

	private static final class CountingChatModel implements ChatModel {

		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(
					List.of(new Generation(new AssistantMessage("response-" + calls.incrementAndGet()))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

}
