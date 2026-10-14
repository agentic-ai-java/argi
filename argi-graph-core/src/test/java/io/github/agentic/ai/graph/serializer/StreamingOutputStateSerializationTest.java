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
package io.github.agentic.ai.graph.serializer;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.JacksonStateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import io.github.agentic.ai.graph.serializer.std.ObjectStreamStateSerializer;
import io.github.agentic.ai.graph.state.AgentStateFactory;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.store.Store;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StreamingOutputStateSerializationTest {

	private static List<StateSerializer> serializers(AgentStateFactory<OverAllState> factory) {
		return List.of(new ObjectStreamStateSerializer(factory), new ObjectStreamStateSerializer(factory),
				new JacksonStateSerializer(factory) {
				}, new ArgiJacksonStateSerializer(factory));
	}

	private static ChatResponse response() {
		ToolCall call = new ToolCall("call", "lookup", "{\"query\":\"" + "x".repeat(80_000) + "\"}");
		return new ChatResponse("id", "model", List.of(ChatMessage.assistant("answer", List.of(call))), List.of(call),
				FinishReason.TOOL_CALLS, new Usage(3_000_000_000L, null, null),
				Map.of("@type", "literal", "@class", "business", "nested", ChatMessage.user("nested")));
	}

	private static List<ChatEvent> events() {
		ChatResponse response = response();
		return List.of(ChatEvent.textDelta("x".repeat(100_000)), ChatEvent.toolCall(response.toolCalls().get(0)),
				ChatEvent.response(response), ChatEvent.usage(new Usage(null, 5L, null)),
				new ChatEvent(ChatEvent.Type.TEXT_DELTA, null, null, null, null,
						Map.of("@type", "ARGI_CHAT_EVENT", "nested", response)));
	}

	@Test
	void preservesEveryEventAndStreamingSnapshotInBinaryAndJson() throws Exception {
		for (StateSerializer serializer : serializers(OverAllState::new)) {
			for (ChatEvent event : events()) {
				StreamingOutput<?> output = new StreamingOutput<>(event, "model", "agent",
						new OverAllState(
								Map.of("messages", List.of(ChatMessage.user("question")), "last_event", event)),
						OutputType.AGENT_MODEL_STREAMING);
				output.setSubGraph(true);
				Map<String, Object> data = Map.of("event", event, "output", output);
				for (Map<String, Object> restored : List.of(serializer.dataFromBytes(serializer.dataToBytes(data)),
						serializer.cloneObject(data).data())) {
					assertEquals(event, restored.get("event"));
					StreamingOutput<?> actual = assertInstanceOf(StreamingOutput.class, restored.get("output"));
					assertNotSame(output, actual);
					assertEquals(event, actual.getOriginData());
					assertEquals(output.state().data(), actual.state().data());
					assertEquals(output.tokenUsage(), actual.tokenUsage());
					assertEquals(output.getOutputType(), actual.getOutputType());
					assertEquals("model", actual.node());
					assertEquals("agent", actual.agent());
					assertTrue(actual.isSubGraph());
				}
			}
		}
	}

	@Test
	void restoresRuntimeStrategiesAndStoreThroughTheConfiguredFactory() throws Exception {
		Store store = mock(Store.class);
		AgentStateFactory<OverAllState> factory = data -> new OverAllState(data, store)
			.registerKeyAndStrategy("messages", new AppendStrategy());
		for (StateSerializer serializer : serializers(factory)) {
			StreamingOutput<?> output = new StreamingOutput<>(ChatEvent.textDelta("answer"), "model",
					factory.apply(Map.of("messages", List.of(ChatMessage.user("question")))));
			StreamingOutput<?> restored = assertInstanceOf(StreamingOutput.class,
					serializer.cloneObject(Map.of("output", output)).data().get("output"));
			assertSame(store, restored.state().getStore());
			restored.state().updateState(Map.of("messages", List.of(ChatMessage.assistant("answer"))));
			assertEquals(List.of(ChatMessage.user("question"), ChatMessage.assistant("answer")),
					restored.state().data().get("messages"));
		}
	}

	@Test
	void preservesNullStatePartialUsageAndExplicitUsageOverrides() throws Exception {
		for (StateSerializer serializer : serializers(OverAllState::new)) {
			for (Object origin : List.of(response(), ChatMessage.user("message"), new ToolCall("id", "tool", "{}"),
					Usage.unknown(), "text")) {
				StreamingOutput<?> output = new StreamingOutput<>(origin, null, null, null, null);
				Usage usage = new Usage(null, 3_000_000_000L, null);
				output.setTokenUsage(usage);
				StreamingOutput<?> restored = assertInstanceOf(StreamingOutput.class,
						serializer.dataFromBytes(serializer.dataToBytes(Map.of("output", output))).get("output"));
				assertEquals(origin, restored.getOriginData());
				assertEquals(usage, restored.tokenUsage());
				assertNull(restored.node());
				assertNull(restored.agent());
				assertNull(restored.state());
				assertNull(restored.getOutputType());
			}
		}
	}

	@Test
	void reloadsEventAndSnapshotFromDiskWithANewSaver(@TempDir Path folder) throws Exception {
		int index = 0;
		for (StateSerializer serializer : serializers(OverAllState::new)) {
			Path target = folder.resolve("serializer-" + index++);
			RunnableConfig config = RunnableConfig.builder().threadId("stream").build();
			ChatEvent event = ChatEvent.response(response());
			StreamingOutput<?> output = new StreamingOutput<>(event, "model", "agent",
					new OverAllState(Map.of("response", response())), OutputType.AGENT_MODEL_FINISHED);
			FileSystemSaver writer = FileSystemSaver.builder().targetFolder(target).stateSerializer(serializer).build();
			writer.put(config,
					Checkpoint.builder()
						.nodeId("model")
						.nextNodeId("tools")
						.state(Map.of("event", event, "output", output))
						.build());
			FileSystemSaver reader = FileSystemSaver.builder().targetFolder(target).stateSerializer(serializer).build();
			Map<String, Object> restored = reader.get(config).orElseThrow().getState();
			assertEquals(event, restored.get("event"));
			StreamingOutput<?> actual = assertInstanceOf(StreamingOutput.class, restored.get("output"));
			assertEquals(event, actual.getOriginData());
			assertEquals(output.tokenUsage(), actual.tokenUsage());
			assertEquals(output.state().data(), actual.state().data());
			assertEquals(OutputType.AGENT_MODEL_FINISHED, actual.getOutputType());
		}
	}

	@Test
	void preservesNeutralMessagesWhileNativeOriginDataRemainsTransient() throws Exception {
		for (StateSerializer serializer : List.of(new ObjectStreamStateSerializer(OverAllState::new),
				new ArgiJacksonStateSerializer(OverAllState::new))) {
			StreamingOutput<?> output = new StreamingOutput<>(ChatMessage.assistant("answer"), new Object(), "model",
					"agent", new OverAllState(Map.of("message", ChatMessage.assistant("question"))),
					OutputType.AGENT_MODEL_STREAMING);
			output.setTokenUsage(new Usage(8L, 2L, 10L));
			StreamingOutput<?> restored = assertInstanceOf(StreamingOutput.class,
					serializer.cloneObject(Map.of("output", output)).data().get("output"));
			assertEquals(output.messages(), restored.messages());
			assertEquals(output.text(), restored.text());
			assertEquals(output.tokenUsage(), restored.tokenUsage());
			assertEquals(output.state().data(), restored.state().data());
			assertNull(restored.getOriginData());
		}
	}

	@Test
	void preservesBinaryContentOriginsAndChunkOnlyOutputs() throws Exception {
		byte[] bytes = new byte[] { 0, 1, -1, 42 };
		ContentPart content = ContentPart.binary(ContentPart.Type.IMAGE, "image/png", bytes);
		for (StateSerializer serializer : serializers(OverAllState::new)) {
			StreamingOutput<?> output = new StreamingOutput<>(content, "model", new OverAllState());
			StreamingOutput<?> restored = assertInstanceOf(StreamingOutput.class,
					serializer.cloneObject(Map.of("output", output)).data().get("output"));
			ContentPart actual = assertInstanceOf(ContentPart.class, restored.getOriginData());
			assertEquals(content.type(), actual.type());
			assertEquals(content.mimeType(), actual.mimeType());
			assertArrayEquals(bytes, actual.data());

			StreamingOutput<?> chunk = new StreamingOutput<>(ChatMessage.assistant("chunk"), null, "node", "agent",
					null, OutputType.AGENT_MODEL_STREAMING);
			chunk.setOutputType(OutputType.AGENT_MODEL_STREAMING);
			StreamingOutput<?> restoredChunk = assertInstanceOf(StreamingOutput.class,
					serializer.cloneObject(Map.of("output", chunk)).data().get("output"));
			assertEquals("chunk", restoredChunk.text());
			assertNull(restoredChunk.getOriginData());
			assertNull(restoredChunk.state());
			assertEquals(OutputType.AGENT_MODEL_STREAMING, restoredChunk.getOutputType());
		}
	}

	@Test
	void preservesMultipleToolResultsInEverySerializer() throws Exception {
		var results = List.of(ChatMessage.tool("first", "lookup", "x".repeat(100_000)),
				new ChatMessage(io.github.agentic.ai.api.chat.ChatRole.TOOL, List.of(ContentPart.text("second")),
						List.of(), "second", "lookup", Map.of("binding", "turn-1")));
		for (StateSerializer serializer : serializers(OverAllState::new)) {
			var output = new StreamingOutput<>(results, null, "tools", "agent", new OverAllState(),
					OutputType.AGENT_TOOL_FINISHED);
			output.setTokenUsage(new Usage(8L, 2L, 10L));
			output.setSubGraph(true);
			var restored = assertInstanceOf(StreamingOutput.class,
					serializer.dataFromBytes(serializer.dataToBytes(Map.of("output", output))).get("output"));
			assertEquals(results, restored.messages());
			assertEquals("", restored.text());
			assertEquals(output.tokenUsage(), restored.tokenUsage());
			assertEquals(OutputType.AGENT_TOOL_FINISHED, restored.getOutputType());
			assertTrue(restored.isSubGraph());
		}
	}

	@Test
	void rejectsCorruptStreamingJsonInsteadOfSilentlyRestoringABusinessMap() throws Exception {
		for (JacksonStateSerializer serializer : List.of(new JacksonStateSerializer(OverAllState::new) {
		}, new ArgiJacksonStateSerializer(OverAllState::new))) {
			for (String field : List.of("\"outputType\":\"UNKNOWN\"", "\"state\":[]", "\"state\":{}",
					"\"state\":{\"data\":[]}", "\"messages\":\"not a message\"",
					"\"messages\":[{\"content\":\"text\"}]")) {
				String json = "{\"output\":{\"@type\":\"ARGI_STREAMING_OUTPUT\","
						+ (field.startsWith("\"messages\"") ? "" : "\"messages\":[],") + field + "}}";
				assertThrows(IOException.class, () -> serializer.read(json), field);
			}
		}
	}

}
