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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.authorization.AuthorizationDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedModelContractsTest {

	@Test
	void optionalBuilderValuesAndCopyKeepTheirOriginalMeaning() {
		ModelRequest request = ModelRequest.builder()
			.messages(null)
			.tools(null)
			.toolDescriptions(null)
			.context(null)
			.attributes(Map.of("attribute", "value"))
			.build();
		assertTrue(request.getMessages().isEmpty());
		assertFalse(request.hasToolSelection());
		assertTrue(request.getTools().isEmpty());
		assertTrue(request.getToolDescriptions().isEmpty());
		assertTrue(request.getContext().isEmpty());
		assertEquals(Map.of("attribute", "value"), request.getAttributes());
		assertTrue(request.getTrustedExecutionContext().isEmpty());
		assertSame(request.getDynamicToolRegistry(), ModelRequest.builder(request).build().getDynamicToolRegistry());
		assertEquals(GenerationOptions.defaults(), request.getOptions());
		ModelRequest selected = ModelRequest.builder().tools(List.of()).build();
		assertTrue(ModelRequest.builder(selected).build().hasToolSelection());
		assertTrue(selected.getTools().isEmpty());
		ToolRegistry registry = new ToolRegistry(List.of());
		assertSame(registry, ModelRequest.builder().dynamicToolRegistry(registry).build().getDynamicToolRegistry());
	}

	@Test
	void sharedInterceptorAndAuthorizationRetainTheirContracts() {
		ModelInterceptor interceptor = new ModelInterceptor() {
			public String getName() {
				return "portable";
			}

			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(request);
			}
		};
		assertTrue(interceptor.getTools().isEmpty());
		assertEquals("portable", interceptor.getName());
		ModelResponse response = interceptor.interceptModel(ModelRequest.builder().build(),
				request -> ModelResponse.of(ChatMessage.assistant("answer")));
		assertEquals("answer", response.getMessage().text());
		assertFalse(AuthorizationDecision.allow().isDenied());
		assertTrue(AuthorizationDecision.deny("reason").isDenied());
	}

}
