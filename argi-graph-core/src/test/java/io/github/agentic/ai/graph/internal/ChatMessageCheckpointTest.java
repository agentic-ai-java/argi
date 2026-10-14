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
package io.github.agentic.ai.graph.internal;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import io.github.agentic.ai.graph.serializer.std.ObjectStreamStateSerializer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChatMessageCheckpointTest {

	private static ChatMessage message(ChatRole role) {
		return new ChatMessage(role,
				List.of(ContentPart.text("text"), ContentPart.json("{\"ok\":true}"),
						ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2, 3 })),
				role == ChatRole.ASSISTANT ? List.of(new ToolCall("call", "lookup", "{}")) : List.of(),
				role == ChatRole.TOOL ? "call" : null, role == ChatRole.TOOL ? "lookup" : null,
				Map.of("source", "caller", "messageType", "caller", "argi.contentParts", "application value"));
	}

	@Test
	void preservesAllRolesContentToolsAndCallerMetadataInBothSerializers() throws Exception {
		for (var serializer : List.of(new ObjectStreamStateSerializer(OverAllState::new),
				new ArgiJacksonStateSerializer(OverAllState::new))) {
			for (ChatRole role : ChatRole.values()) {
				ChatMessage original = message(role);
				var restored = serializer.dataFromBytes(serializer.dataToBytes(Map.of("message", original)));
				ChatMessage actual = assertInstanceOf(ChatMessage.class, restored.get("message"));
				assertEquals(original.role(), actual.role());
				assertEquals(original.text(), actual.text());
				assertEquals(original.toolCalls(), actual.toolCalls());
				assertEquals(original.toolCallId(), actual.toolCallId());
				assertEquals(original.toolName(), actual.toolName());
				assertEquals(original.metadata(), actual.metadata());
				assertEquals(original.content().stream().map(ContentPart::type).toList(),
						actual.content().stream().map(ContentPart::type).toList());
				assertArrayEquals(original.content().get(2).data(), actual.content().get(2).data());
			}
		}
	}

	@Test
	void editedTextPreservesMediaToolIdentityAndMetadata() {
		for (ChatRole role : ChatRole.values()) {
			ChatMessage original = message(role);
			ChatMessage edited = original.mutate().text("updated").build();
			assertEquals("updated", edited.text());
			assertEquals(List.of(ContentPart.Type.TEXT, ContentPart.Type.IMAGE),
					edited.content().stream().map(ContentPart::type).toList());
			assertArrayEquals(new byte[] { 1, 2, 3 }, edited.content().get(1).data());
			assertEquals(original.metadata(), edited.metadata());
			assertEquals(original.toolCalls(), edited.toolCalls());
			assertEquals(original.toolCallId(), edited.toolCallId());
			assertEquals(original.toolName(), edited.toolName());
		}
	}

}
