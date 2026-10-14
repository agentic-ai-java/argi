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
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Shared base class for creating a ChatModel through an OpenAI-compatible API. Subclasses
 * can use the initialized {@link #chatModel} field directly.
 *
 * <p>
 * Set the following environment variables before running these tests:
 * <ul>
 * <li>{@code ARGI_API_KEY} - API key (required)</li>
 * <li>{@code ARGI_MODEL} - model name (required)</li>
 * <li>{@code ARGI_BASE_URL} - API base URL (optional; defaults to the OpenAI
 * endpoint)</li>
 * </ul>
 */
@EnabledForRealModel
abstract class AbstractDashscopeChatModelTest {

	protected ChatModel chatModel;

	@BeforeEach
	void initChatModel() {
		chatModel = OpenAiCompatibleTestModels.chatModel();
	}

}
