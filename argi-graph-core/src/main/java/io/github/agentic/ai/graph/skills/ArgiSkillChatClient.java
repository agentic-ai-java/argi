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
package io.github.agentic.ai.graph.skills;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.graph.skills.registry.SkillRegistry;
import io.github.agentic.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * Adds skill summaries to a neutral client's system prompt. Tool execution remains the
 * agent runtime's responsibility; share this registry with the read-skill tool. Decorator
 * nesting determines ordering, independently of native advisor APIs.
 */
public final class ArgiSkillChatClient implements ChatClient {

	private final ChatClient delegate;

	private final SkillRegistry skillRegistry;

	public ArgiSkillChatClient(ChatClient delegate, SkillRegistry skillRegistry) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry must not be null");
	}

	public static Builder builder(ChatClient delegate) {
		return new Builder(Objects.requireNonNull(delegate, "delegate must not be null"));
	}

	@Override
	public CompletionStage<ChatResponse> call(ChatRequest request) {
		return delegate.call(augment(Objects.requireNonNull(request, "request must not be null")));
	}

	@Override
	public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		// Resolve the registry on every subscription, without starting a model turn
		// early.
		return JdkFlowAdapter.publisherToFlowPublisher(
				Flux.defer(() -> JdkFlowAdapter.flowPublisherToFlux(delegate.stream(augment(request)))));
	}

	@Override
	public ChatClientCapabilities capabilities() {
		return delegate.capabilities();
	}

	public SkillRegistry getSkillRegistry() {
		return skillRegistry;
	}

	private ChatRequest augment(ChatRequest request) {
		try {
			skillRegistry.reload();
		}
		catch (UnsupportedOperationException ignored) {
			// Static registries can provide summaries without supporting reload.
		}
		List<SkillMetadata> skills = skillRegistry.listAll();
		if (skills.isEmpty()) {
			return request;
		}
		String prompt = SkillPromptConstants.buildSkillsPrompt(skills, skillRegistry,
				skillRegistry.getSystemPromptTemplate());
		List<ChatMessage> messages = new ArrayList<>(request.messages());
		for (int i = 0; i < messages.size(); i++) {
			ChatMessage message = messages.get(i);
			if (message.role() == ChatRole.SYSTEM) {
				List<ContentPart> content = new ArrayList<>(message.content());
				content.add(ContentPart.text((content.isEmpty() ? "" : "\n\n") + prompt));
				messages.set(i, new ChatMessage(message.role(), content, message.toolCalls(), message.toolCallId(),
						message.toolName(), message.metadata()));
				return new ChatRequest(messages, request.tools(), request.options(), request.attributes());
			}
		}
		messages.add(0, ChatMessage.system(prompt));
		return new ChatRequest(messages, request.tools(), request.options(), request.attributes());
	}

	/** Builds a decorator with a shared registry or a filesystem registry. */
	public static final class Builder {

		private final ChatClient delegate;

		private SkillRegistry skillRegistry;

		private String userSkillsDirectory;

		private String projectSkillsDirectory;

		private boolean lazyLoad;

		private Builder(ChatClient delegate) {
			this.delegate = delegate;
		}

		public Builder skillRegistry(SkillRegistry registry) {
			this.skillRegistry = Objects.requireNonNull(registry, "skillRegistry must not be null");
			return this;
		}

		public Builder userSkillsDirectory(String directory) {
			this.userSkillsDirectory = Objects.requireNonNull(directory, "directory must not be null");
			return this;
		}

		public Builder projectSkillsDirectory(String directory) {
			this.projectSkillsDirectory = Objects.requireNonNull(directory, "directory must not be null");
			return this;
		}

		/**
		 * Defers filesystem loading until the first invocation or stream subscription.
		 */
		public Builder lazyLoad(boolean lazyLoad) {
			this.lazyLoad = lazyLoad;
			return this;
		}

		public ArgiSkillChatClient build() {
			SkillRegistry registry = skillRegistry;
			if (registry == null) {
				var builder = FileSystemSkillRegistry.builder().autoLoad(!lazyLoad);
				if (userSkillsDirectory != null) {
					builder.userSkillsDirectory(userSkillsDirectory);
				}
				if (projectSkillsDirectory != null) {
					builder.projectSkillsDirectory(projectSkillsDirectory);
				}
				registry = builder.build();
			}
			return new ArgiSkillChatClient(delegate, registry);
		}

	}

}
