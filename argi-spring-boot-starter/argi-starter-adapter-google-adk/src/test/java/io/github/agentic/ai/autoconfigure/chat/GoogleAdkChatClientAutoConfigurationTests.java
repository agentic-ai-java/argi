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

import com.google.adk.models.BaseLlm;
import com.google.adk.models.Claude;
import com.google.adk.models.Gemini;
import io.github.agentic.ai.adapter.googleadk.GoogleAdkChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GoogleAdkChatClientAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GoogleAdkChatClientAutoConfiguration.class));

	@Test
	void adaptsSingleNativeModel() {
		runner.withBean(BaseLlm.class, () -> mock(BaseLlm.class)).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class)).isInstanceOf(GoogleAdkChatClientAdapter.class);
		});
	}

	@Test
	void retainsImplementationSpecificCapabilities() {
		runner.withBean(BaseLlm.class, () -> mock(Claude.class)).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class).capabilities()).isEqualTo(ChatClientCapabilities.basic());
		});
		runner.withBean(BaseLlm.class, () -> mock(Gemini.class)).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
			assertThat(context.getBean(ChatClient.class).capabilities().streaming()).isTrue();
			assertThat(context.getBean(ChatClient.class).capabilities().toolCalling()).isTrue();
		});
	}

	@Test
	void customModelUsesExplicitCapabilitiesThroughUserProvidedAdapter() {
		var capabilities = new ChatClientCapabilities(true, true, false, false, true, true);
		var adapter = new GoogleAdkChatClientAdapter(mock(BaseLlm.class), capabilities);
		runner.withBean(ChatClient.class, () -> adapter)
			.withBean(BaseLlm.class, () -> mock(BaseLlm.class))
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
				assertThat(context.getBean(ChatClient.class)).isSameAs(adapter);
				assertThat(context.getBean(ChatClient.class).capabilities()).isEqualTo(capabilities);
			});
	}

	@Test
	void explicitSelectionMatchesOnlyThisAdapter() {
		runner.withPropertyValues("argi.chat.adapter=google-adk")
			.withBean(BaseLlm.class, () -> mock(BaseLlm.class))
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class));
		runner.withPropertyValues("argi.chat.adapter=other")
			.withBean(BaseLlm.class, () -> mock(BaseLlm.class))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasMessage(
						"Invalid argi.chat.adapter 'other'; expected spring-ai, langchain4j, google-adk, or agentscope"));
	}

	@Test
	void doesNotConstructProvidersOrLoadAbsentNativeClasses() {
		runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
		runner.withClassLoader(new FilteredClassLoader(BaseLlm.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
	}

	@Test
	void preservesCustomNeutralClient() {
		ChatClient custom = mock(ChatClient.class);
		runner.withBean(ChatClient.class, () -> custom)
			.withBean(BaseLlm.class, () -> mock(BaseLlm.class))
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class);
				assertThat(context.getBean(ChatClient.class)).isSameAs(custom);
			});
	}

	@Test
	void doesNotChooseAnAmbiguousNativeModel() {
		runner.withBean("firstModel", BaseLlm.class, () -> mock(BaseLlm.class))
			.withBean("secondModel", BaseLlm.class, () -> mock(BaseLlm.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatClient.class));
	}

	@Test
	void usesPrimaryNativeModel() {
		runner.withBean("firstModel", BaseLlm.class, () -> mock(BaseLlm.class))
			.withBean("primaryModel", BaseLlm.class, () -> mock(BaseLlm.class),
					definition -> definition.setPrimary(true))
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatClient.class));
	}

}
