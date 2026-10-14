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
package io.github.agentic.ai.graph.agent.support;

import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class OpenAiCompatibleTestModels {

	private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

	private OpenAiCompatibleTestModels() {
	}

	public static ChatClient chatClient() {
		return new SpringAiChatClientAdapter(chatModel());
	}

	public static ChatModel chatModel() {
		return chatModel(builder -> {
		});
	}

	public static ChatModel chatModel(Consumer<OpenAiChatOptions.Builder> optionsCustomizer) {
		OpenAiChatOptions.Builder optionsBuilder = options(System.getenv());
		optionsCustomizer.accept(optionsBuilder);
		return OpenAiChatModel.builder().options(optionsBuilder.build()).build();
	}

	static OpenAiChatOptions.Builder options(Map<String, String> environment) {
		String model = environment.get("ARGI_MODEL");
		String apiKey = environment.get("ARGI_API_KEY");
		// Also guard mixed suites that use this helper without a class-level condition.
		assumeTrue(model != null && !model.isBlank() && apiKey != null && !apiKey.isBlank(),
				"Real model tests require both ARGI_MODEL and ARGI_API_KEY");
		return OpenAiChatOptions.builder()
			.baseUrl(environment.getOrDefault("ARGI_BASE_URL", DEFAULT_BASE_URL))
			.apiKey(apiKey)
			.model(model);
	}

}
