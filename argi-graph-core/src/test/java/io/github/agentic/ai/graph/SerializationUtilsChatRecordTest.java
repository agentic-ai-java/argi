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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.utils.SerializationUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SerializationUtilsChatRecordTest {

	@Test
	void neutralConversationRetainsTypedContentAcrossRepeatedStateCopies() {
		var image = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2, 3 });
		for (ChatRole role : ChatRole.values()) {
			var original = new ChatMessage(role, List.of(ContentPart.text("text"), image), List.of(),
					role == ChatRole.TOOL ? "call" : null, role == ChatRole.TOOL ? "lookup" : null,
					Map.of("nested", new ArrayList<>(List.of("caller"))));
			ChatMessage value = original;
			for (int index = 0; index < 3; index++) {
				value = (ChatMessage) SerializationUtils.deepCopyValue(value);
				var restored = value;
				assertThat(restored.metadata()).isEqualTo(original.metadata());
				assertThat(restored.content().get(1).data()).containsExactly(1, 2, 3);
				assertThat(restored.content().get(0)).isEqualTo(original.content().get(0));
			}
		}
	}

	@Test
	void toolHistoryRetainsNeutralRecordsAcrossRepeatedCopies() {
		ChatMessage result = result();
		Map<String, Object> state = Map.of("messages", List.of(result));
		for (int index = 0; index < 3; index++) {
			state = SerializationUtils.deepCopyMap(state);
			List<?> history = (List<?>) state.get("messages");
			ChatMessage message = (ChatMessage) history.get(0);
			assertThat(message.toolCallId()).isEqualTo("call");
			assertThat(message.toolName()).isEqualTo("lookup");
			assertThat(message.content().get(1).data()).containsExactly(1, 2, 3);
			assertThat(message.metadata()).containsEntry("nested", result.metadata().get("nested"));
		}
	}

	@Test
	void nestedRecordsAndMutableMetadataAreCopiedWithoutTypeErasure() {
		ChatMessage original = result();
		ChatResponse response = new ChatResponse("response", "model", List.of(original),
				List.of(new ToolCall("next", "lookup", "{}")), null, null, Map.of("original", original));
		ChatEvent event = ChatEvent.response(response);
		ChatEvent copied = (ChatEvent) SerializationUtils.deepCopyValue(event);
		assertThat(copied).isNotSameAs(event);
		assertThat(copied.response().metadata().get("original")).isInstanceOf(ChatMessage.class);
		ChatMessage message = copied.response().messages().get(0);
		assertThat(message.content().get(1).metadata().get("original")).isInstanceOf(ChatMessage.class);
		@SuppressWarnings("unchecked")
		List<String> nested = (List<String>) message.metadata().get("nested");
		nested.add("copy-only");
		assertThat((List<?>) original.metadata().get("nested")).hasSize(1);
		assertThat(copied.response().toolCalls()).containsExactly(new ToolCall("next", "lookup", "{}"));
	}

	private static ChatMessage result() {
		ContentPart image = new ContentPart(ContentPart.Type.IMAGE, null, "image/png", new byte[] { 1, 2, 3 },
				Map.of("original", ChatMessage.user("source")));
		return new ChatMessage(ChatRole.TOOL, List.of(ContentPart.json("{\"ok\":true}"), image), List.of(), "call",
				"lookup", Map.of("nested", new ArrayList<>(List.of("original"))));
	}

}
