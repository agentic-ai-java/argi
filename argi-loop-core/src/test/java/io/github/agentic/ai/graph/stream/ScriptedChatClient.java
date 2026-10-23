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
package io.github.agentic.ai.graph.stream;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/** Cold, deterministic client for graph protocol regression tests. */
public final class ScriptedChatClient implements ChatClient {

	private final Supplier<Flux<ChatEvent>> script;

	public ScriptedChatClient(Supplier<Flux<ChatEvent>> script) {
		this.script = script;
	}

	@Override
	public CompletionStage<ChatResponse> call(ChatRequest request) {
		return Flux.defer(script)
			.filter(event -> event.type() == ChatEvent.Type.RESPONSE)
			.map(ChatEvent::response)
			.single()
			.toFuture();
	}

	@Override
	public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
		return JdkFlowAdapter.publisherToFlowPublisher(Flux.defer(script));
	}

	@Override
	public ChatClientCapabilities capabilities() {
		return new ChatClientCapabilities(true, true, false, false, true, true);
	}

}
