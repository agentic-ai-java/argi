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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.agent.support.OpenAiCompatibleTestModels;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.interceptor.modelfallback.ModelFallbackInterceptor;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.model.ChatModel;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledForRealModel
class ModelfallbackTest {

	private ChatModelCallCounter chatModel;

	private ChatModelCallCounter fallbackModel;

	private static CompileConfig getCompileConfig() {
		SaverConfig saverConfig = SaverConfig.builder().register(new MemorySaver()).build();
		CompileConfig compileConfig = CompileConfig.builder().saverConfig(saverConfig).build();
		return compileConfig;
	}

	@BeforeEach
	void setUp() {
		ChatModel primaryChatModel = OpenAiCompatibleTestModels.chatModel();
		this.chatModel = new ChatModelCallCounter(primaryChatModel, "Primary");

		ChatModel fallbackChatModel = OpenAiCompatibleTestModels.chatModel();
		this.fallbackModel = new ChatModelCallCounter(fallbackChatModel, "Fallback");

	}

	@Test
	public void testReactAgent() throws Exception {

		ModelFallbackInterceptor modelFallbackInterceptor = ModelFallbackInterceptor.builder()
			.addFallbackClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(fallbackModel))
			.build();

		ReactAgent agent = ReactAgent.builder()
			.name("single_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.interceptors(modelFallbackInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("帮我写一篇100字左右散文。");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			// Get call counts
			int primaryModelCalls = chatModel.getCallCount();
			int fallbackModelCalls = fallbackModel.getCallCount();

			// Verify the models were called as expected
			// Bug fix: Primary model succeeded, so fallback should NOT be called
			assertEquals(1, primaryModelCalls, "Primary model should be called once");
			assertEquals(0, fallbackModelCalls, "Fallback should not be called when primary succeeds");

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

}
