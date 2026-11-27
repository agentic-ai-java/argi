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
package io.github.agentic.ai.agent.fallback;

import io.github.agentic.ai.agent.orchestration.interceptor.modelfallback.ModelFallbackInterceptor;
import io.github.agentic.ai.api.chat.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Declarative fallback configuration. Provider clients are resolved at assembly time. */
public record ModelFallbackConfig(List<String> candidates, ModelFallbackPolicy policy) {

	public ModelFallbackConfig {
		candidates = List.copyOf(candidates);
		Objects.requireNonNull(policy, "policy");
	}

	public ModelFallbackInterceptor bind(Map<String, ChatClient> clients) {
		ModelFallbackInterceptor.Builder builder = ModelFallbackInterceptor.builder().policy(policy);
		for (String name : candidates) {
			ChatClient client = clients.get(name);
			if (client == null) {
				throw new IllegalArgumentException("Unknown fallback client: " + name);
			}
			builder.addFallbackClient(client);
		}
		return builder.build();
	}

}
