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
package io.github.agentic.ai.graph.agent.flow.agent.loop;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.flow.agent.LoopAgent;
import io.github.agentic.ai.graph.agent.internal.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArrayLoopStrategyTest {

	@Test
	void loopAgentInvokesItsSubAgentOncePerBatch() throws Exception {
		List<List<?>> received = new ArrayList<>();
		ChatClient client = mock(ChatClient.class);
		when(client.capabilities()).thenReturn(ChatClientCapabilities.basic());
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			ChatMessage lastUser = request.messages()
				.stream()
				.filter(message -> message.role() == ChatRole.USER)
				.reduce((first, second) -> second)
				.orElseThrow();
			received.add(new JsonCodec().fromJson(lastUser.text(), List.class));
			return CompletableFuture.completedFuture(new ChatResponse(null, null,
					List.of(ChatMessage.assistant("done")), List.of(), FinishReason.STOP, Usage.unknown(), Map.of()));
		});
		ReactAgent worker = ReactAgent.builder().name("batch_worker").chatClient(client).build();
		LoopAgent loop = LoopAgent.builder()
			.name("batch_loop")
			.subAgent(worker)
			.loopStrategy(LoopMode.array(5))
			.build();
		List<Integer> input = IntStream.range(0, 20).boxed().toList();

		assertTrue(loop.invoke(new JsonCodec().toJson(input)).isPresent());

		assertEquals(List.of(input.subList(0, 5), input.subList(5, 10), input.subList(10, 15), input.subList(15, 20)),
				received);
	}

	@Test
	void dispatchesCompleteBatchesInOrder() {
		ArrayLoopStrategy strategy = LoopMode.array(5);
		List<Integer> input = IntStream.range(0, 20).boxed().toList();
		Map<String, Object> state = initializedState(strategy, new JsonCodec().toJson(input));

		for (int index = 0; index < input.size(); index += 5) {
			Map<String, Object> update = strategy.loopDispatch(new OverAllState(state));
			assertBatch(update, strategy, index + 5, input.subList(index, index + 5));
			state.putAll(update);
		}
		assertCompleted(strategy, state);
	}

	@Test
	void dispatchesShortFinalBatchWithCustomConverter() {
		List<Integer> input = IntStream.range(0, 12).boxed().toList();
		ArrayLoopStrategy strategy = LoopMode.array(5, messages -> {
			assertEquals("custom source", messages.get(0).text());
			return input;
		});
		Map<String, Object> state = initializedState(strategy, "custom source");

		for (int index = 0; index < input.size(); index += 5) {
			int nextIndex = Math.min(index + 5, input.size());
			Map<String, Object> update = strategy.loopDispatch(new OverAllState(state));
			assertBatch(update, strategy, nextIndex, input.subList(index, nextIndex));
			state.putAll(update);
		}
		assertCompleted(strategy, state);
	}

	@ParameterizedTest
	@ValueSource(ints = { 10, Integer.MAX_VALUE })
	void batchLargerThanInputConsumesAllRemainingElements(int batchSize) {
		ArrayLoopStrategy strategy = LoopMode.array(batchSize);
		Map<String, Object> state = initializedState(strategy, "[0,1,2]");
		state.put(strategy.loopCountKey(), 1);

		Map<String, Object> update = strategy.loopDispatch(new OverAllState(state));

		assertBatch(update, strategy, 3, List.of(1, 2));
		state.putAll(update);
		assertCompleted(strategy, state);
	}

	@Test
	void emptyArrayCompletesWithoutDispatchingAMessage() {
		ArrayLoopStrategy strategy = LoopMode.array(5);

		assertCompleted(strategy, initializedState(strategy, "[]"));
	}

	@Test
	void existingDefaultEntrypointsKeepSingleElementMessages() {
		for (ArrayLoopStrategy strategy : List.of(LoopMode.array(), new ArrayLoopStrategy())) {
			Map<String, Object> state = initializedState(strategy, "[\"one\",\"two\"]");
			for (int index = 0; index < 2; index++) {
				Map<String, Object> update = strategy.loopDispatch(new OverAllState(state));
				assertDispatch(update, strategy, index + 1, List.of("one", "two").get(index));
				state.putAll(update);
			}
			assertCompleted(strategy, state);
		}
	}

	@Test
	void existingConverterEntrypointsKeepElementToStringFormat() {
		Map<String, String> element = Map.of("name", "one");
		for (ArrayLoopStrategy strategy : List.of(LoopMode.array(messages -> List.of(element)),
				new ArrayLoopStrategy(messages -> List.of(element)))) {
			Map<String, Object> state = initializedState(strategy, "ignored");

			assertDispatch(strategy.loopDispatch(new OverAllState(state)), strategy, 1, element.toString());
		}
	}

	@Test
	void explicitSingleElementBatchesKeepJsonArrayFormat() {
		for (ArrayLoopStrategy strategy : List.of(LoopMode.array(1),
				LoopMode.array(1, messages -> List.of("one", "two")))) {
			Map<String, Object> state = initializedState(strategy, "[\"one\",\"two\"]");

			assertBatch(strategy.loopDispatch(new OverAllState(state)), strategy, 1, List.of("one"));
		}
	}

	@Test
	void batchPayloadPreservesStructuredAndEscapedValues() {
		List<?> input = List.of("a\"b\\c\n", Map.of("name", "quoted\"value", "count", 2), List.of("nested", true), 3);
		ArrayLoopStrategy strategy = LoopMode.array(5, messages -> input);
		Map<String, Object> state = initializedState(strategy, "ignored");

		assertBatch(strategy.loopDispatch(new OverAllState(state)), strategy, input.size(), input);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, Integer.MIN_VALUE })
	void rejectsNonPositiveBatchSizes(int batchSize) {
		assertThrows(IllegalArgumentException.class, () -> LoopMode.array(batchSize));
		assertThrows(IllegalArgumentException.class, () -> LoopMode.array(batchSize, messages -> List.of()));
	}

	private static Map<String, Object> initializedState(ArrayLoopStrategy strategy, String text) {
		OverAllState input = new OverAllState(Map.of(LoopStrategy.MESSAGE_KEY, List.of(ChatMessage.user(text))));
		return new HashMap<>(strategy.loopInit(input));
	}

	private static void assertBatch(Map<String, Object> update, ArrayLoopStrategy strategy, int expectedIndex,
			List<?> expectedElements) {
		ChatMessage message = (ChatMessage) update.get(LoopStrategy.MESSAGE_KEY);
		assertTrue((Boolean) update.get(strategy.loopFlagKey()));
		assertEquals(expectedIndex, update.get(strategy.loopCountKey()));
		assertEquals(ChatRole.USER, message.role());
		assertEquals(expectedElements, new JsonCodec().fromJson(message.text(), List.class));
	}

	private static void assertDispatch(Map<String, Object> update, ArrayLoopStrategy strategy, int expectedIndex,
			String expectedText) {
		assertTrue((Boolean) update.get(strategy.loopFlagKey()));
		assertEquals(expectedIndex, update.get(strategy.loopCountKey()));
		ChatMessage message = (ChatMessage) update.get(LoopStrategy.MESSAGE_KEY);
		assertEquals(ChatRole.USER, message.role());
		assertEquals(expectedText, message.text());
	}

	private static void assertCompleted(ArrayLoopStrategy strategy, Map<String, Object> state) {
		Map<String, Object> update = strategy.loopDispatch(new OverAllState(state));
		assertFalse((Boolean) update.get(strategy.loopFlagKey()));
		assertFalse(update.containsKey(LoopStrategy.MESSAGE_KEY));
	}

}
