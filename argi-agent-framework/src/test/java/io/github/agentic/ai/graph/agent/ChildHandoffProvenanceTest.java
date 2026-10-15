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
package io.github.agentic.ai.graph.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatSourceKind;
import io.github.agentic.ai.api.chat.ChatTrustLevel;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.MessageProvenance;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.flow.agent.SequentialAgent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Child agent handoff into a parent SequentialAgent must retain CHILD_OUTPUT provenance.
 */
class ChildHandoffProvenanceTest {

	@Test
	void sequentialChildHandoffTagsChildAssistantAsUntrustedChildOutput() throws Exception {
		ChatClient childClient = mock(ChatClient.class, CALLS_REAL_METHODS);
		when(childClient.capabilities()).thenReturn(ChatClientCapabilities.basic());
		when(childClient.call(any(ChatRequest.class)))
			.thenReturn(CompletableFuture.completedFuture(new ChatResponse(null, null,
					List.of(ChatMessage.assistant("child-result")), List.of(), FinishReason.STOP, null, Map.of())));

		ReactAgent child = ReactAgent.builder()
			.name("child")
			.chatClient(childClient)
			.systemPrompt("child system")
			.returnReasoningContents(false)
			.build();

		SequentialAgent parent = SequentialAgent.builder()
			.name("parent")
			.description("parent")
			.subAgents(List.of(child))
			.build();

		OverAllState state = parent.invoke("go").orElseThrow();
		@SuppressWarnings("unchecked")
		List<ChatMessage> messages = (List<ChatMessage>) state.value("messages").orElseThrow();

		ChatMessage handoff = messages.stream()
			.filter(message -> "child-result".equals(message.text()))
			.findFirst()
			.orElseThrow();
		assertThat(MessageProvenance.sourceOf(handoff)).contains(ChatSourceKind.CHILD_OUTPUT);
		assertThat(MessageProvenance.trustOf(handoff)).contains(ChatTrustLevel.UNTRUSTED);
		assertThat(MessageProvenance.isTrustedInstruction(handoff)).isFalse();
	}

}
