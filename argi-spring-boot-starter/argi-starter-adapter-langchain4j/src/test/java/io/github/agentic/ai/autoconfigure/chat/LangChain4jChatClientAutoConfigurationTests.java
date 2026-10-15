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

import dev.langchain4j.model.chat.ChatModel;
import io.github.agentic.ai.adapter.langchain4j.LangChain4jChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LangChain4jChatClientAutoConfigurationTests {

	@Test
	void wiresOptionalStreamingModel() {
		runner.withBean(ChatModel.class, () -> mock(ChatModel.class))
			.withBean(dev.langchain4j.model.chat.StreamingChatModel.class,
					() -> mock(dev.langchain4j.model.chat.StreamingChatModel.class))
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
				assertThat(context.getBean(ChatClient.class).capabilities().streaming()).isTrue();
			});
	}

	@Test
	void rejectsAmbiguousStreamingModels() {
		runner.withBean(ChatModel.class, () -> mock(ChatModel.class))
			.withBean("firstStream", dev.langchain4j.model.chat.StreamingChatModel.class,
					() -> mock(dev.langchain4j.model.chat.StreamingChatModel.class))
			.withBean("secondStream", dev.langchain4j.model.chat.StreamingChatModel.class,
					() -> mock(dev.langchain4j.model.chat.StreamingChatModel.class))
			.run(context -> assertThat(context).hasFailed());
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(LangChain4jChatClientAutoConfiguration.class));

	@Test
	void adaptsSingleNativeModel() {
		runner.withBean(ChatModel.class, () -> mock(ChatModel.class)).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class)).isInstanceOf(LangChain4jChatClientAdapter.class);
		});
	}

	@Test
	void explicitSelectionMatchesOnlyThisAdapter() {
		runner.withPropertyValues("argi.chat.adapter=langchain4j")
			.withBean(ChatModel.class, () -> mock(ChatModel.class))
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class));
		runner.withPropertyValues("argi.chat.adapter=other")
			.withBean(ChatModel.class, () -> mock(ChatModel.class))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasMessage(
						"Invalid argi.chat.adapter 'other'; expected spring-ai, langchain4j, or google-adk"));
	}

	@Test
	void doesNotConstructProvidersOrLoadAbsentNativeClasses() {
		runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
		runner.withClassLoader(new FilteredClassLoader(ChatModel.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
	}

	@Test
	void preservesCustomNeutralClient() {
		ChatClient custom = mock(ChatClient.class);
		runner.withBean(ChatClient.class, () -> custom)
			.withBean(ChatModel.class, () -> mock(ChatModel.class))
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
				assertThat(context.getBean(ChatClient.class)).isSameAs(custom);
			});
	}

	@Test
	void doesNotChooseAnAmbiguousNativeModel() {
		runner.withBean("firstModel", ChatModel.class, () -> mock(ChatModel.class))
			.withBean("secondModel", ChatModel.class, () -> mock(ChatModel.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
	}

	@Test
	void usesPrimaryNativeModel() {
		runner.withBean("firstModel", ChatModel.class, () -> mock(ChatModel.class))
			.withBean("primaryModel", ChatModel.class, () -> mock(ChatModel.class),
					definition -> definition.setPrimary(true))
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class));
	}

}
