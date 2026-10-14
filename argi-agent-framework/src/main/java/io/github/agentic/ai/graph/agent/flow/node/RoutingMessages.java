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
package io.github.agentic.ai.graph.agent.flow.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.OverAllState;
import java.util.ArrayList;
import java.util.List;

/** Validates neutral histories without flattening media or tool correlations. */
final class RoutingMessages {

	private RoutingMessages() {
	}

	static List<ChatMessage> read(OverAllState state) {
		Object history = state.value("messages").orElse(List.of());
		if (!(history instanceof List<?> messages)) {
			throw new IllegalArgumentException("Routing history must be a list of messages");
		}
		List<ChatMessage> result = new ArrayList<>();
		for (Object value : messages) {
			if (value instanceof ChatMessage message) {
				result.add(message);
			}
			else {
				throw new IllegalArgumentException("Unsupported message in routing history");
			}
		}
		return List.copyOf(result);
	}

	static String originalQuery(List<ChatMessage> messages) {
		for (int index = messages.size() - 1; index >= 0; index--) {
			if (messages.get(index).role() == ChatRole.USER) {
				return messages.get(index).text();
			}
		}
		return "";
	}

}
