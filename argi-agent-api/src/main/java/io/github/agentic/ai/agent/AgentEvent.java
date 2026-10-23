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
import io.github.agentic.ai.api.chat.Usage;
import java.util.Map;
import java.util.Objects;

/**
 * Invocation-local ordered events. Message deltas share an executor-assigned message ID;
 * Message supplies its complete snapshot. Usage updates replace, not add to, earlier
 * invocation totals. Progress is application data, never routing or state.
 */
public sealed interface AgentEvent {

	record MessageDelta(String messageId, String text) implements AgentEvent {
		public MessageDelta {
			requireId(messageId);
			Objects.requireNonNull(text, "text must not be null");
		}
	}

	record Message(String messageId, ChatMessage message) implements AgentEvent {
		public Message {
			requireId(messageId);
			Objects.requireNonNull(message, "message must not be null");
		}
	}

	record Progress(String step, Map<String, Object> details) implements AgentEvent {
		public Progress {
			requireId(step);
			details = Map.copyOf(details);
		}
	}

	record UsageUpdate(Usage usage) implements AgentEvent {
		public UsageUpdate {
			Objects.requireNonNull(usage, "usage must not be null");
		}
	}

	record Finished(AgentOutcome outcome) implements AgentEvent {
		public Finished {
			Objects.requireNonNull(outcome, "outcome must not be null");
		}
	}

	private static void requireId(String value) {
		Objects.requireNonNull(value, "event identifier must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("event identifier must not be blank");
		}
	}

}
