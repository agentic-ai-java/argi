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
package io.github.agentic.ai.graph.agent.hooks.modelcalllimit;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException;
import io.github.agentic.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCallLimitHookOfflineTest {

	@Test
	void explicitThreadLimitIsCumulativeAcrossFreshConfigsForSameThread() throws Exception {
		CountingChatModel chatModel = new CountingChatModel();
		ReactAgent agent = createAgent(threadLimit(ModelCallLimitHook.ExitBehavior.ERROR), new MemorySaver(),
				chatModel);

		assertTrue(agent.invoke("first", config("same-thread")).isPresent());

		assertThrows(ModelCallLimitExceededException.class, () -> agent.invoke("second", config("same-thread")));
		assertEquals(1, chatModel.calls());
	}

	@Test
	void explicitThreadLimitDoesNotLeakAcrossDifferentThreads() throws Exception {
		CountingChatModel chatModel = new CountingChatModel();
		ReactAgent agent = createAgent(threadLimit(ModelCallLimitHook.ExitBehavior.ERROR), new MemorySaver(),
				chatModel);

		assertTrue(agent.invoke("first", config("thread-a")).isPresent());
		assertTrue(agent.invoke("first", config("thread-b")).isPresent());

		assertThrows(ModelCallLimitExceededException.class, () -> agent.invoke("second", config("thread-a")));
		assertEquals(2, chatModel.calls());
	}

	@Test
	void runLimitResetsForEachFreshInvokeOnSameThread() throws Exception {
		CountingChatModel chatModel = new CountingChatModel();
		ModelCallLimitHook hook = ModelCallLimitHook.builder()
			.runLimit(1)
			.exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
			.build();
		ReactAgent agent = createAgent(hook, new MemorySaver(), chatModel);

		assertTrue(agent.invoke("first", config("run-thread")).isPresent());
		assertTrue(agent.invoke("second", config("run-thread")).isPresent());

		assertEquals(2, chatModel.calls());
	}

	@Test
	void runOnlyLimitDoesNotPersistThreadCounterInState() throws Exception {
		CountingChatModel chatModel = new CountingChatModel();
		ModelCallLimitHook hook = ModelCallLimitHook.builder()
			.runLimit(1)
			.exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
			.build();
		ReactAgent agent = createAgent(hook, new MemorySaver(), chatModel);

		Optional<OverAllState> result = agent.invoke("first", config("run-only-thread"));

		assertTrue(result.isPresent());
		assertFalse(result.get()
			.data()
			.keySet()
			.stream()
			.anyMatch(key -> key.contains("model_call_limit") && key.contains("thread_count")));
		assertEquals(1, chatModel.calls());
	}

	@Test
	void endBehaviorStopsBeforeModelCallWhenPersistedThreadLimitIsReached() throws Exception {
		CountingChatModel chatModel = new CountingChatModel();
		ReactAgent agent = createAgent(threadLimit(ModelCallLimitHook.ExitBehavior.END), new MemorySaver(), chatModel);

		assertTrue(agent.invoke("first", config("end-thread")).isPresent());
		Optional<OverAllState> ended = agent.invoke("second", config("end-thread"));

		assertTrue(ended.isPresent());
		assertEquals(1, chatModel.calls());
		List<ChatMessage> messages = messages(ended.get());
		assertTrue(messages.get(messages.size() - 1).text().contains("thread limit (1/1)"));
	}

	@Test
	void persistedThreadLimitSurvivesFreshAgentAndConfigWithSameSaver() throws Exception {
		MemorySaver saver = new MemorySaver();
		CountingChatModel firstModel = new CountingChatModel();
		ReactAgent firstAgent = createAgent(threadLimit(ModelCallLimitHook.ExitBehavior.ERROR), saver, firstModel);

		assertTrue(firstAgent.invoke("first", config("resumed-thread")).isPresent());

		CountingChatModel secondModel = new CountingChatModel();
		ReactAgent secondAgent = createAgent(threadLimit(ModelCallLimitHook.ExitBehavior.ERROR), saver, secondModel);

		assertThrows(ModelCallLimitExceededException.class,
				() -> secondAgent.invoke("second", config("resumed-thread")));
		assertEquals(1, firstModel.calls());
		assertEquals(0, secondModel.calls());
	}

	@Test
	void unspecifiedThreadIdKeepsExistingPerInvokeThreadLimitBehavior() throws Exception {
		CountingChatModel chatModel = new CountingChatModel();
		ReactAgent agent = createAgent(threadLimit(ModelCallLimitHook.ExitBehavior.ERROR), new MemorySaver(),
				chatModel);

		assertTrue(agent.invoke("first").isPresent());
		assertTrue(agent.invoke("second").isPresent());

		assertEquals(2, chatModel.calls());
	}

	private static ModelCallLimitHook threadLimit(ModelCallLimitHook.ExitBehavior exitBehavior) {
		return ModelCallLimitHook.builder().threadLimit(1).exitBehavior(exitBehavior).build();
	}

	private static ReactAgent createAgent(ModelCallLimitHook hook, MemorySaver saver, ChatModel chatModel)
			throws Exception {
		return ReactAgent.builder()
			.name("model-call-limit-offline-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.hooks(List.of(hook))
			.saver(saver)
			.build();
	}

	private static RunnableConfig config(String threadId) {
		return RunnableConfig.builder().threadId(threadId).build();
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messages(OverAllState state) {
		return (List<ChatMessage>) state.value("messages").orElseThrow();
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

		private int calls() {
			return calls.get();
		}

	}

}
