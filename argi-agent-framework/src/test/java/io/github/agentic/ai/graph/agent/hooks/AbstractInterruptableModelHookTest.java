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
package io.github.agentic.ai.graph.agent.hooks;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.AbstractInterruptableModelHook;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.action.InterruptionMetadata;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractInterruptableModelHookTest {

	@Test
	void customInterruptableModelHookRunsAsFullGraphNode() throws Exception {
		TestInterruptableModelHook hook = new TestInterruptableModelHook();
		ReactAgent agent = ReactAgent.builder()
			.name("custom-interruptable-hook-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new MockChatModel()))
			.hooks(hook)
			.build();

		agent.invoke("test");

		assertTrue(hook.wasApplied(), "The custom interruptable hook should execute its node action");
	}

	@HookPositions(HookPosition.BEFORE_MODEL)
	private static final class TestInterruptableModelHook extends AbstractInterruptableModelHook {

		private final AtomicBoolean applied = new AtomicBoolean();

		@Override
		public String getName() {
			return "test_interruptable_model_hook";
		}

		@Override
		public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
			applied.set(true);
			return CompletableFuture.completedFuture(Map.of());
		}

		@Override
		public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
			return Optional.empty();
		}

		boolean wasApplied() {
			return applied.get();
		}

	}

	private static final class MockChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

}
