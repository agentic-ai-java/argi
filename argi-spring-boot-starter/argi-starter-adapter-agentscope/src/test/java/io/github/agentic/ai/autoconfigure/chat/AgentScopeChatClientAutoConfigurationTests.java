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

import io.agentscope.core.model.Model;
import io.github.agentic.ai.adapter.agentscope.AgentScopeChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeChatClientAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentScopeChatClientAutoConfiguration.class));

	@Test
	void adaptsSingleNativeModel() {
		runner.withBean(Model.class, () -> mock(Model.class)).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class)).isInstanceOf(AgentScopeChatClientAdapter.class);
		});
	}

	@Test
	void explicitSelectionMatchesOnlyThisAdapter() {
		runner.withPropertyValues("argi.chat.adapter=agentscope")
			.withBean(Model.class, () -> mock(Model.class))
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class));
		runner.withPropertyValues("argi.chat.adapter=other")
			.withBean(Model.class, () -> mock(Model.class))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasMessage(
						"Invalid argi.chat.adapter 'other'; expected spring-ai, langchain4j, google-adk, or agentscope"));
	}

	@Test
	void doesNotConstructProvidersOrLoadAbsentNativeClasses() {
		runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
		runner.withClassLoader(new FilteredClassLoader(Model.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
	}

	@Test
	void preservesCustomNeutralClient() {
		ChatClient custom = mock(ChatClient.class);
		runner.withBean(ChatClient.class, () -> custom).withBean(Model.class, () -> mock(Model.class)).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class)).isSameAs(custom);
		});
	}

	@Test
	void doesNotChooseAnAmbiguousNativeModel() {
		runner.withBean("firstModel", Model.class, () -> mock(Model.class))
			.withBean("secondModel", Model.class, () -> mock(Model.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
	}

	@Test
	void usesPrimaryNativeModel() {
		runner.withBean("firstModel", Model.class, () -> mock(Model.class))
			.withBean("primaryModel", Model.class, () -> mock(Model.class), definition -> definition.setPrimary(true))
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class));
	}

}
