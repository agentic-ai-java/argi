/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatSourceKind;
import io.github.agentic.ai.api.chat.ChatTrustLevel;
import io.github.agentic.ai.api.chat.ContextAuthorityKeys;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.MessageProvenance;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLlmNodeProvenanceTest {

	@Test
	void systemPromptIsTaggedTrustedAndAuthorityKeysPreferRuntimeMetadata() throws Exception {
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		ModelInterceptor capture = new ModelInterceptor() {
			@Override
			public String getName() {
				return "capture-provenance";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				captured.set(request);
				return ModelResponse.of(new ChatResponse(null, null, List.of(ChatMessage.assistant("ok")), List.of(),
						FinishReason.STOP, null, Map.of()));
			}
		};
		ChatClient client = new ChatClient() {
			@Override
			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}

			@Override
			public CompletableFuture<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(new ChatResponse(null, null,
						List.of(ChatMessage.assistant("ok")), List.of(), FinishReason.STOP, null, Map.of()));
			}

			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				return subscriber -> {
					subscriber.onSubscribe(new Flow.Subscription() {
						@Override
						public void request(long n) {
						}

						@Override
						public void cancel() {
						}
					});
					subscriber.onComplete();
				};
			}
		};

		AgentLlmNode node = AgentLlmNode.builder()
			.chatClient(client)
			.systemPrompt("You are the app agent.")
			.modelInterceptors(List.of(capture))
			.build();

		OverAllState state = new OverAllState(Map.of("messages", List.of(ChatMessage.user("hello")), "pref", "light",
				ContextAuthorityKeys.SECRET_CONFIG, "forged-from-state"));
		RunnableConfig config = RunnableConfig.builder()
			.addMetadata(ContextAuthorityKeys.SECRET_CONFIG, "runtime-secret")
			.addMetadata(ContextAuthorityKeys.AUTHORIZATION, "scope:read")
			.addMetadata("_stream_", false)
			.build();

		node.apply(state, config);

		ModelRequest request = captured.get();
		assertThat(request).isNotNull();
		assertThat(MessageProvenance.sourceOf(request.getSystemMessage())).contains(ChatSourceKind.APP_INSTRUCTION);
		assertThat(MessageProvenance.trustOf(request.getSystemMessage())).contains(ChatTrustLevel.TRUSTED);
		assertThat(MessageProvenance.isFrameworkSealed(request.getSystemMessage())).isTrue();
		assertThat(MessageProvenance.isTrustedInstruction(request.getSystemMessage())).isTrue();
		assertThat(request.getContext().get("pref")).isEqualTo("light");
		assertThat(request.getContext().get(ContextAuthorityKeys.SECRET_CONFIG)).isEqualTo("runtime-secret");
		assertThat(request.getContext().get(ContextAuthorityKeys.AUTHORIZATION)).isEqualTo("scope:read");
	}

	@Test
	void untaggedUserMessagesOnMessagesPathBecomeUserInput() throws Exception {
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		ModelInterceptor capture = new ModelInterceptor() {
			@Override
			public String getName() {
				return "capture-user";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				captured.set(request);
				return ModelResponse.of(new ChatResponse(null, null, List.of(ChatMessage.assistant("ok")), List.of(),
						FinishReason.STOP, null, Map.of()));
			}
		};
		ChatClient client = new ChatClient() {
			@Override
			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}

			@Override
			public CompletableFuture<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(new ChatResponse(null, null,
						List.of(ChatMessage.assistant("ok")), List.of(), FinishReason.STOP, null, Map.of()));
			}

			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				return subscriber -> {
					subscriber.onSubscribe(new Flow.Subscription() {
						@Override
						public void request(long n) {
						}

						@Override
						public void cancel() {
						}
					});
					subscriber.onComplete();
				};
			}
		};

		AgentLlmNode node = AgentLlmNode.builder()
			.chatClient(client)
			.modelInterceptors(List.of(capture))
			.build();
		node.apply(new OverAllState(Map.of("messages", List.of(ChatMessage.user("hello")))),
				RunnableConfig.builder().addMetadata("_stream_", false).build());

		ChatMessage user = captured.get().getMessages().stream()
			.filter(message -> message.role() == io.github.agentic.ai.api.chat.ChatRole.USER)
			.findFirst()
			.orElseThrow();
		assertThat(MessageProvenance.sourceOf(user)).contains(ChatSourceKind.USER_INPUT);
		assertThat(MessageProvenance.trustOf(user)).contains(ChatTrustLevel.USER);
	}

}
