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
package io.github.agentic.ai.graph.serializer.std;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArgiChatObjectStreamTest {

	private final ObjectStreamStateSerializer serializer = new ObjectStreamStateSerializer(OverAllState::new);

	@Test
	void restoresNestedChatRecordsAndLargeTextThroughPersistenceAndClone() throws Exception {
		ToolCall call = new ToolCall("call-1", "lookup", "{\"query\":\"" + "x".repeat(80_000) + "\"}");
		List<ChatMessage> messages = List.of(ChatMessage.system("system"), ChatMessage.user("x".repeat(100_000)),
				ChatMessage.assistant(null, List.of(call)), ChatMessage.tool(call.id(), call.name(), "result"));
		Usage usage = new Usage(3_000_000_000L, null, 3_000_000_002L);
		ChatResponse response = new ChatResponse("response", "model", List.of(messages.get(2)), List.of(call),
				FinishReason.TOOL_CALLS, usage, Map.of("nested", messages.get(0)));
		Map<String, Object> state = Map.of("messages", messages, "response", response, "usage", usage, "tool", call,
				"nested", Map.of("response", response));
		for (Map<String, Object> restored : List.of(serializer.dataFromBytes(serializer.dataToBytes(state)),
				serializer.cloneObject(state).data())) {
			assertEquals(state, restored);
			assertInstanceOf(ChatResponse.class, restored.get("response"));
			assertInstanceOf(ChatMessage.class, ((List<?>) restored.get("messages")).get(0));
			assertNotSame(response, restored.get("response"));
		}
	}

	@Test
	void preservesEveryContentTypeAndNestedMetadataWithoutSharingBinaryData() throws Exception {
		byte[] bytes = new byte[] { 0, 1, -1 };
		Map<String, Object> metadata = Map.of("@type", "literal", "message", ChatMessage.user("nested"));
		for (ContentPart.Type type : ContentPart.Type.values()) {
			ContentPart part = new ContentPart(type, null, null, bytes, metadata);
			ChatMessage message = new ChatMessage(ChatRole.USER, List.of(part), metadata);
			ChatMessage restored = assertInstanceOf(ChatMessage.class,
					serializer.dataFromBytes(serializer.dataToBytes(Map.of("message", message))).get("message"));
			ContentPart actual = restored.content().get(0);
			assertEquals(type, actual.type());
			assertNull(actual.text());
			assertNull(actual.mimeType());
			assertArrayEquals(bytes, actual.data());
			assertEquals(metadata, actual.metadata());
			assertEquals(metadata, restored.metadata());
			byte[] copy = actual.data();
			copy[0] = 9;
			assertArrayEquals(bytes, actual.data());
		}
	}

	@Test
	void preservesOptionalResponseFieldsAndNullCollectionEntries() throws Exception {
		ChatResponse response = new ChatResponse(null, null, null, null, null, null, null);
		List<Object> values = new ArrayList<>();
		values.add(null);
		values.add(response);
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("values", values);
		state.put("nullable", null);
		assertEquals(state, serializer.dataFromBytes(serializer.dataToBytes(state)));
	}

	@Test
	void reloadsCheckpointHistoryFromDiskWithANewSaver(@TempDir Path folder) throws Exception {
		FileSystemSaver writer = saver(folder);
		RunnableConfig config = RunnableConfig.builder().threadId("neutral-chat").build();
		writer.put(config,
				Checkpoint.builder()
					.nodeId("model")
					.nextNodeId("tools")
					.state(Map.of("messages", List.of(ChatMessage.user("question"))))
					.build());
		ToolCall call = new ToolCall("call", "lookup", "{}");
		Map<String, Object> latest = Map.of("messages",
				List.of(ChatMessage.assistant(null, List.of(call)), ChatMessage.tool(call.id(), call.name(), "answer")),
				"usage", Usage.unknown());
		writer.put(config, Checkpoint.builder().nodeId("tools").nextNodeId("model").state(latest).build());
		FileSystemSaver reader = saver(folder);
		assertEquals(latest, reader.get(config).orElseThrow().getState());
		assertEquals(2, reader.list(config).size());
		assertEquals(List.of(ChatMessage.user("question")),
				new ArrayList<>(reader.list(config)).get(1).getState().get("messages"));
	}

	private FileSystemSaver saver(Path folder) {
		return FileSystemSaver.builder().targetFolder(folder).stateSerializer(serializer).build();
	}

}
