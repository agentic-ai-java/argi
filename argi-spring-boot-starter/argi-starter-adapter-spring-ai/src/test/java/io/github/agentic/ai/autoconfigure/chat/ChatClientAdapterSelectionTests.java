/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.autoconfigure.chat;

import io.github.agentic.ai.api.chat.ChatClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatClientAdapterSelectionTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SpringAiChatClientAutoConfiguration.class,
				LangChain4jChatClientAutoConfiguration.class, GoogleAdkChatClientAutoConfiguration.class))
		.withBean("springModel", org.springframework.ai.chat.model.ChatModel.class,
				() -> mock(org.springframework.ai.chat.model.ChatModel.class))
		.withBean("langChainModel", dev.langchain4j.model.chat.ChatModel.class,
				() -> mock(dev.langchain4j.model.chat.ChatModel.class))
		.withBean(com.google.adk.models.BaseLlm.class, () -> mock(com.google.adk.models.BaseLlm.class));

	@Test
	void requiresSelectionWhenMultipleStartersAreInstalled() {
		runner.run(context -> assertThat(context).hasFailed()
			.getFailure()
			.hasRootCauseMessage("Multiple ARGI adapter starters are installed; set argi.chat.adapter "
					+ "to spring-ai, langchain4j, or google-adk, or provide an ARGI ChatClient bean"));
	}

	@Test
	void selectsEachAdapterExplicitly() {
		Map<String, String> adapters = Map.of("spring-ai", "SpringAiChatClientAdapter", "langchain4j",
				"LangChain4jChatClientAdapter", "google-adk", "GoogleAdkChatClientAdapter");
		adapters.forEach((name, type) -> runner.withPropertyValues("argi.chat.adapter=" + name).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class).getClass().getSimpleName()).isEqualTo(type);
		}));
	}

	@Test
	void customClientOverridesAllAdaptersWithoutASelector() {
		ChatClient custom = mock(ChatClient.class);
		runner.withBean(ChatClient.class, () -> custom).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class)).isSameAs(custom);
		});
	}

	@Test
	void rejectsUnknownOrBlankSelectorsEvenWithoutNativeModelBeans() {
		var noModels = new ApplicationContextRunner().withConfiguration(
				AutoConfigurations.of(SpringAiChatClientAutoConfiguration.class,
						LangChain4jChatClientAutoConfiguration.class, GoogleAdkChatClientAutoConfiguration.class));
		for (String selector : new String[] { "unknown", "", "SPRING-AI" }) {
			noModels.withPropertyValues("argi.chat.adapter=" + selector)
				.run(context -> assertThat(context).hasFailed()
					.getFailure()
					.hasMessage("Invalid argi.chat.adapter '" + selector
							+ "'; expected spring-ai, langchain4j, or google-adk"));
		}
	}

	@Test
	void rejectsASelectorForAStarterAbsentFromTheApplicationClasspath() {
		Map<String, Class<?>> missing = Map.of("spring-ai", SpringAiChatClientAutoConfiguration.class, "langchain4j",
				LangChain4jChatClientAutoConfiguration.class, "google-adk", GoogleAdkChatClientAutoConfiguration.class);
		missing.forEach((name, configuration) -> {
			Class<?> present = name.equals("spring-ai") ? LangChain4jChatClientAutoConfiguration.class
					: SpringAiChatClientAutoConfiguration.class;
			new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(present))
				.withClassLoader(new FilteredClassLoader(configuration))
				.withPropertyValues("argi.chat.adapter=" + name)
				.run(context -> assertThat(context).hasFailed()
					.getFailure()
					.hasMessage("Selected ARGI adapter starter is not installed: " + name));
		});
	}

}
