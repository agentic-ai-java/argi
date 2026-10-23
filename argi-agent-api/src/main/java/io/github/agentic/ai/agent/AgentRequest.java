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
package io.github.agentic.ai.agent;

import io.github.agentic.ai.api.chat.ChatMessage;
import java.util.List;
import java.util.Map;

/**
 * Conversation messages and application inputs, not mutable engine state or merge
 * strategies. Collections are shallow snapshots; applications own nested values. A resume
 * token is opaque, not a credential. The executor must bind it to the authenticated
 * tenant, run and exact definition version, and reject stale/replayed tokens. Inputs
 * carry application-defined feedback on resume. A null token starts a new execution;
 * engines without resume support must reject non-null tokens.
 */
public record AgentRequest(List<ChatMessage> messages, Map<String, Object> inputs, String resumeToken) {

	public AgentRequest {
		messages = List.copyOf(messages);
		inputs = Map.copyOf(inputs);
		if (resumeToken != null && resumeToken.isBlank()) {
			throw new IllegalArgumentException("resumeToken must not be blank");
		}
	}

	public static AgentRequest messages(List<ChatMessage> messages) {
		return new AgentRequest(messages, Map.of(), null);
	}

}
