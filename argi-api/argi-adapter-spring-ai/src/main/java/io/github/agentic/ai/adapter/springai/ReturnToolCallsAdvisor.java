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
package io.github.agentic.ai.adapter.springai;

import io.github.agentic.ai.api.chat.GenerationOptions;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * Reserves Spring AI's single ToolAdvisor slot without running a tool loop. A native
 * client with an explicit tool-execution advisor is rejected by Spring AI validation.
 */
final class ReturnToolCallsAdvisor implements CallAdvisor, StreamAdvisor, ToolAdvisor {

	private final List<ToolCallback> callbacks;

	private final GenerationOptions overrides;

	ReturnToolCallsAdvisor(List<ToolCallback> callbacks, GenerationOptions overrides) {
		this.callbacks = List.copyOf(callbacks);
		this.overrides = overrides;
	}

	@Override
	public String getName() {
		return "ARGI Return Tool Calls";
	}

	@Override
	public int getOrder() {
		return Integer.MAX_VALUE - 1;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		return chain.nextCall(withRequestTools(request));
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return chain.nextStream(withRequestTools(request));
	}

	private ChatClientRequest withRequestTools(ChatClientRequest request) {
		var nativeOptions = request.prompt().getOptions();
		// Merge only after ChatClient resolves defaults, retaining provider-specific
		// options.
		ToolCallingChatOptions.Builder<?> builder = nativeOptions instanceof ToolCallingChatOptions options
				? options.mutate() : ToolCallingChatOptions.builder();
		if (nativeOptions != null && !(nativeOptions instanceof ToolCallingChatOptions)) {
			builder.combineWith(nativeOptions.mutate());
		}
		if (overrides.temperature() != null) {
			builder.temperature(overrides.temperature());
		}
		if (overrides.topP() != null) {
			builder.topP(overrides.topP());
		}
		if (overrides.maxTokens() != null) {
			builder.maxTokens(overrides.maxTokens());
		}
		if (!overrides.stopSequences().isEmpty()) {
			builder.stopSequences(overrides.stopSequences());
		}
		// Native defaults must not introduce tools outside this ARGI model turn.
		return request.mutate()
			.prompt(request.prompt().mutate().chatOptions(builder.toolCallbacks(callbacks).build()).build())
			.build();
	}

}
