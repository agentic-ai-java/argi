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
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.graph.skills.registry.SkillRegistry;
import io.github.agentic.ai.graph.utils.PromptTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArgiSkillChatClientTest {

	private static final ChatRequest QUESTION = ChatRequest.of(List.of(ChatMessage.user("question")));

	@Test
	void augmentsFirstSystemAndPreservesAllOtherRequestFields() {
		StubClient delegate = new StubClient();
		ArgiSkillChatClient client = new ArgiSkillChatClient(delegate, registry());
		ContentPart binary = ContentPart.binary(ContentPart.Type.OTHER, "application/octet-stream", new byte[] { 1 });
		ChatMessage system = new ChatMessage(ChatRole.SYSTEM, List.of(ContentPart.text("rules"), binary),
				Map.of("source", "policy"));
		var request = new ChatRequest(List.of(system, ChatMessage.system("extra"), ChatMessage.user("question")),
				List.of(new ToolDefinition("lookup", "Lookup", "{\"type\":\"object\"}")),
				GenerationOptions.builder().temperature(0.3).build(), Map.of("correlation", "id"));

		assertThat(client.call(request)).isSameAs(delegate.pending);
		ChatRequest actual = delegate.requests.get(0);
		assertThat(actual.messages().get(0).text()).startsWith("rules\n\nTest registry\n").contains("Documentation");
		assertThat(actual.messages().get(0).content()).contains(binary);
		assertThat(actual.messages().get(0).metadata()).isEqualTo(system.metadata());
		assertThat(actual.messages().subList(1, 3)).isEqualTo(request.messages().subList(1, 3));
		assertThat(actual.tools()).isEqualTo(request.tools());
		assertThat(actual.options()).isSameAs(request.options());
		assertThat(actual.attributes()).isEqualTo(request.attributes());
		assertThat(request.messages().get(0).text()).isEqualTo("rules");
		assertThat(client.capabilities()).isSameAs(delegate.capabilities());
	}

	@Test
	void addsSystemBeforeHistoryWhenMissing() {
		StubClient delegate = new StubClient();
		new ArgiSkillChatClient(delegate, registry()).call(QUESTION);
		assertThat(delegate.requests.get(0).messages()).hasSize(2);
		assertThat(delegate.requests.get(0).messages().get(0).role()).isEqualTo(ChatRole.SYSTEM);
		assertThat(delegate.requests.get(0).messages().get(1)).isSameAs(QUESTION.messages().get(0));
	}

	@Test
	void supportsEmptySystemContent() {
		StubClient delegate = new StubClient();
		new ArgiSkillChatClient(delegate, registry()).call(ChatRequest
			.of(List.of(new ChatMessage(ChatRole.SYSTEM, List.of(), Map.of()), ChatMessage.user("question"))));
		assertThat(delegate.requests.get(0).messages().get(0).text()).startsWith("Test registry");
	}

	@Test
	void emptyRegistryPassesOriginalRequestThrough() {
		StubClient delegate = new StubClient();
		SkillRegistry registry = mock(SkillRegistry.class);
		when(registry.listAll()).thenReturn(List.of());
		new ArgiSkillChatClient(delegate, registry).call(QUESTION);
		assertThat(delegate.requests).containsExactly(QUESTION);
		assertThat(delegate.requests.get(0)).isSameAs(QUESTION);
	}

	@Test
	void registryWithoutReloadStillAugments() {
		SkillRegistry registry = registry();
		doThrow(new UnsupportedOperationException("Static registry")).when(registry).reload();
		StubClient delegate = new StubClient();
		new ArgiSkillChatClient(delegate, registry).call(QUESTION);
		assertThat(delegate.requests.get(0).messages().get(0).text()).contains("Documentation");
	}

	@Test
	void streamIsColdAndReloadsAtEachSubscriptionWithoutMutatingHistory() {
		StubClient delegate = new StubClient();
		SkillRegistry registry = registry();
		ArgiSkillChatClient client = new ArgiSkillChatClient(delegate, registry);
		var publisher = client.stream(QUESTION);
		assertThat(delegate.requests).isEmpty();
		org.mockito.Mockito.verify(registry, org.mockito.Mockito.never()).reload();
		JdkFlowAdapter.flowPublisherToFlux(publisher).blockLast(Duration.ofSeconds(2));
		when(registry.listAll()).thenReturn(List.of());
		JdkFlowAdapter.flowPublisherToFlux(publisher).blockLast(Duration.ofSeconds(2));
		assertThat(delegate.requests).hasSize(2);
		assertThat(delegate.requests.get(0).messages()).hasSize(2);
		assertThat(delegate.requests.get(1)).isSameAs(QUESTION);
		org.mockito.Mockito.verify(registry, org.mockito.Mockito.times(2)).reload();
		assertThat(QUESTION.messages()).hasSize(1);
	}

	@Test
	void delegatesFutureCancellationWithoutReplacingTheFuture() {
		StubClient delegate = new StubClient();
		var future = new ArgiSkillChatClient(delegate, registry()).call(QUESTION).toCompletableFuture();
		future.cancel(true);
		assertThat(delegate.pending).isCancelled();
	}

	@Test
	void delegatesStreamCancellation() {
		StubClient delegate = new StubClient();
		AtomicBoolean cancelled = new AtomicBoolean();
		delegate.events = Flux.<ChatEvent>never().doOnCancel(() -> cancelled.set(true));
		var subscription = JdkFlowAdapter
			.flowPublisherToFlux(new ArgiSkillChatClient(delegate, registry()).stream(QUESTION))
			.subscribe();
		subscription.dispose();
		assertThat(cancelled).isTrue();
	}

	@Test
	void doesNotSwallowRegistryOrModelFailures() {
		StubClient delegate = new StubClient();
		SkillRegistry registry = registry();
		IllegalStateException failure = new IllegalStateException("Registry unavailable");
		doThrow(failure).when(registry).reload();
		ArgiSkillChatClient client = new ArgiSkillChatClient(delegate, registry);
		assertThatThrownBy(() -> client.call(QUESTION)).isSameAs(failure);
		assertThatThrownBy(() -> JdkFlowAdapter.flowPublisherToFlux(client.stream(QUESTION)).blockLast())
			.isSameAs(failure);
		assertThat(delegate.requests).isEmpty();

		StubClient failed = new StubClient();
		failed.events = Flux.error(failure);
		failed.pending.completeExceptionally(failure);
		ArgiSkillChatClient failedClient = new ArgiSkillChatClient(failed, registry());
		assertThatThrownBy(() -> failedClient.callBlocking(QUESTION)).isSameAs(failure);
		assertThatThrownBy(() -> JdkFlowAdapter.flowPublisherToFlux(failedClient.stream(QUESTION)).blockLast())
			.isSameAs(failure);
	}

	@Test
	void sharedRegistryIsNotLoadedDuringConstruction() {
		SkillRegistry registry = mock(SkillRegistry.class);
		ArgiSkillChatClient client = ArgiSkillChatClient.builder(new StubClient()).skillRegistry(registry).build();
		assertThat(client.getSkillRegistry()).isSameAs(registry);
		verifyNoInteractions(registry);
	}

	@Test
	void filesystemBuilderSupportsEagerAndLazyLoading(@TempDir Path root) throws Exception {
		Path user = Files.createDirectory(root.resolve("user"));
		Path project = Files.createDirectory(root.resolve("project"));
		Path skill = Files.createDirectory(project.resolve("docs"));
		Files.writeString(skill.resolve("SKILL.md"),
				"---\nname: docs\ndescription: Documentation\n---\nWrite documentation.");
		for (boolean lazy : List.of(false, true)) {
			StubClient delegate = new StubClient();
			ArgiSkillChatClient client = ArgiSkillChatClient.builder(delegate)
				.userSkillsDirectory(user.toString())
				.projectSkillsDirectory(project.toString())
				.lazyLoad(lazy)
				.build();
			assertThat(client.getSkillRegistry().size()).isEqualTo(lazy ? 0 : 1);
			client.call(QUESTION);
			assertThat(client.getSkillRegistry().size()).isEqualTo(1);
			assertThat(delegate.requests.get(0).messages().get(0).text()).contains("Documentation");
		}
	}

	@Test
	void rejectsNullInputs() {
		StubClient delegate = new StubClient();
		SkillRegistry registry = registry();
		assertThatThrownBy(() -> new ArgiSkillChatClient(null, registry)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ArgiSkillChatClient(delegate, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ArgiSkillChatClient.builder(null)).isInstanceOf(NullPointerException.class);
		ArgiSkillChatClient client = new ArgiSkillChatClient(delegate, registry);
		assertThatThrownBy(() -> client.call(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> client.stream(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ArgiSkillChatClient.builder(delegate).skillRegistry(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ArgiSkillChatClient.builder(delegate).userSkillsDirectory(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ArgiSkillChatClient.builder(delegate).projectSkillsDirectory(null))
			.isInstanceOf(NullPointerException.class);
	}

	private static SkillRegistry registry() {
		SkillRegistry registry = mock(SkillRegistry.class);
		when(registry.listAll()).thenReturn(List.of(SkillMetadata.builder()
			.name("docs")
			.description("Documentation")
			.source("project")
			.skillPath("/skills/docs")
			.build()));
		when(registry.getRegistryType()).thenReturn("Test registry");
		when(registry.getSkillLoadInstructions()).thenReturn("Use read_skill to read the full content.");
		when(registry.getSystemPromptTemplate())
			.thenReturn(PromptTemplate.of("{skills_registry}\n{skills_list}\n{skills_load_instructions}"));
		return registry;
	}

	private static final class StubClient implements ChatClient {

		private final List<ChatRequest> requests = new ArrayList<>();

		private final CompletableFuture<ChatResponse> pending = new CompletableFuture<>();

		private final ChatClientCapabilities capabilities = ChatClientCapabilities.basic();

		private Flux<ChatEvent> events = Flux.empty();

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			requests.add(request);
			return pending;
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			requests.add(request);
			return JdkFlowAdapter.publisherToFlowPublisher(events);
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return capabilities;
		}

	}

}
