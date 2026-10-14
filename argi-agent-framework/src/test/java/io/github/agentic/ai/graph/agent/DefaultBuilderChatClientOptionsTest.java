/*
 * Copyright 2026-2027 the original author or authors.
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

import java.util.List;

import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultBuilderChatClientOptionsTest {

	@Test
	void chatClientDefaultOptionsRemainOwnedByTheAdapter() throws Exception {
		TestChatModel chatModel = new TestChatModel(
				OpenAiChatOptions.builder().model("configured-model").temperature(0.2).build());
		ChatClient chatClient = ChatClient.builder(chatModel)
			.defaultOptions(OpenAiChatOptions.builder().model("configured-model").temperature(0.7))
			.build();

		ReactAgent agent = ReactAgent.builder()
			.name("test-agent")
			.chatClient(new SpringAiChatClientAdapter(chatClient))
			.build();

		agent.call("test");
		ChatOptions effectiveOptions = chatModel.prompt.getOptions();
		assertEquals("configured-model", effectiveOptions.getModel());
		assertEquals(0.7, effectiveOptions.getTemperature());
	}

	@Test
	void requestOptionsOverrideClientDefaultsWithoutLosingTheConfiguredModel() throws Exception {
		TestChatModel model = new TestChatModel(ChatOptions.builder().build());
		ChatClient client = ChatClient.builder(model)
			.defaultOptions(OpenAiChatOptions.builder().model("configured-model").temperature(0.7))
			.build();
		ReactAgent.builder()
			.name("test-agent")
			.chatClient(new SpringAiChatClientAdapter(client))
			.generationOptions(io.github.agentic.ai.api.chat.GenerationOptions.builder().temperature(0.4).build())
			.build()
			.call("test");
		assertEquals("configured-model", model.prompt.getOptions().getModel());
		assertEquals(0.4, model.prompt.getOptions().getTemperature());
	}

	private static final class TestChatModel implements ChatModel {

		private final ChatOptions options;

		private Prompt prompt;

		private TestChatModel(ChatOptions options) {
			this.options = options;
		}

		@Override
		public ChatOptions getOptions() {
			return this.options;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompt = prompt;
			return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
		}

	}

}
