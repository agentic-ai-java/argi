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
package io.github.agentic.ai.graph.stream;

import io.github.agentic.ai.api.chat.ChatMessage;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.NodeAction;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatRequest;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.util.Map;

public class LLmNodeAction implements NodeAction {

	private ChatClient chatClient;

	private String nodeId;

	public LLmNodeAction(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	public LLmNodeAction(ChatClient chatClient, String nodeId) {
		this.chatClient = chatClient;
		this.nodeId = nodeId;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) {
		// Create prompt with user message
		ChatMessage message = ChatMessage.user((String) state.value(OverAllState.DEFAULT_INPUT_KEY).get());
		Flux<ChatEvent> stream = JdkFlowAdapter
			.flowPublisherToFlux(chatClient.stream(ChatRequest.of(java.util.List.of(message))));
		return Map.of("messages", stream);
	}

}
