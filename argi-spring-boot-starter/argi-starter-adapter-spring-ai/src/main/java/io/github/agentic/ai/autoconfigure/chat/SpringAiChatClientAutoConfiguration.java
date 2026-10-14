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

import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/** Adapts user-provided native clients; provider construction remains outside ARGI. */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass({ ChatClient.class, org.springframework.ai.chat.model.ChatModel.class })
@ConditionalOnMissingBean(ChatClient.class)
@Conditional(SpringAiChatClientAutoConfiguration.AdapterSelection.class)
public class SpringAiChatClientAutoConfiguration {

	static final class AdapterSelection implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String selected = context.getEnvironment().getProperty("argi.chat.adapter");
			if (selected == null) {
				return true;
			}
			Map<String, String> adapters = Map.of("spring-ai", "SpringAi", "langchain4j", "LangChain4j", "google-adk",
					"GoogleAdk", "agentscope", "AgentScope");
			String name = adapters.get(selected);
			if (name == null) {
				throw new IllegalStateException("Invalid argi.chat.adapter '" + selected
						+ "'; expected spring-ai, langchain4j, google-adk, or agentscope");
			}
			if (!ClassUtils.isPresent("io.github.agentic.ai.autoconfigure.chat." + name + "ChatClientAutoConfiguration",
					context.getClassLoader())) {
				throw new IllegalStateException("Selected ARGI adapter starter is not installed: " + selected);
			}
			return "spring-ai".equals(selected);
		}

	}

	@Bean
	@ConditionalOnSingleCandidate(org.springframework.ai.chat.client.ChatClient.class)
	ChatClient argiChatClient(org.springframework.ai.chat.client.ChatClient client, Environment environment,
			ResourceLoader loader) {
		validateSelection(environment, loader);
		return new SpringAiChatClientAdapter(client);
	}

	@Bean
	@ConditionalOnMissingBean(org.springframework.ai.chat.client.ChatClient.class)
	@ConditionalOnSingleCandidate(org.springframework.ai.chat.model.ChatModel.class)
	ChatClient argiChatClientFromModel(org.springframework.ai.chat.model.ChatModel model, Environment environment,
			ResourceLoader loader) {
		validateSelection(environment, loader);
		return new SpringAiChatClientAdapter(model);
	}

	// Multiple installed starters must never select a provider by configuration order.
	private static void validateSelection(Environment environment, ResourceLoader loader) {
		if (environment.getProperty("argi.chat.adapter") != null) {
			return;
		}
		int installed = 0;
		for (String name : new String[] { "SpringAi", "LangChain4j", "GoogleAdk", "AgentScope" }) {
			if (ClassUtils.isPresent("io.github.agentic.ai.autoconfigure.chat." + name + "ChatClientAutoConfiguration",
					loader.getClassLoader())) {
				installed++;
			}
		}
		if (installed > 1) {
			throw new IllegalStateException("Multiple ARGI adapter starters are installed; set argi.chat.adapter "
					+ "to spring-ai, langchain4j, google-adk, or agentscope, or provide an ARGI ChatClient bean");
		}
	}

}
