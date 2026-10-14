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
package io.github.agentic.ai.agent.studio.dto.messages;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NeutralMessageDTOTest {

	@Test
	void userHistoryPreservesMetadataAndMediaThroughBothFactoryDirections() {
		var message = new ChatMessage(ChatRole.USER,
				List.of(ContentPart.text("question"),
						ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2, 3 }),
						ContentPart.binary(ContentPart.Type.AUDIO, "audio/wav", new byte[] { 4, 5 }),
						ContentPart.binary(ContentPart.Type.OTHER, "application/pdf", new byte[] { 6 })),
				Map.of("source", "history"));
		var dto = assertInstanceOf(UserMessageDTO.class, MessageDTO.MessageDTOFactory.fromChatMessage(message));
		assertEquals(3, dto.getMedia().size());
		assertEquals(message, dto.toUserMessage());
		assertEquals(message, MessageDTO.MessageDTOFactory.toMessage(dto));
		assertEquals(message,
				MessageDTO.MessageDTOFactory.toMessage(MessageDTO.MessageDTOFactory.fromMessage(message)));
		((byte[]) dto.getMedia().get(0).getData())[0] = 9;
		assertArrayEquals(new byte[] { 1, 2, 3 }, message.content().get(1).data());
	}

	@Test
	void userHistoryMediaSurvivesJsonSerialization() throws Exception {
		var message = new ChatMessage(ChatRole.USER,
				List.of(ContentPart.text("question"),
						ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2, 3 })),
				Map.of("source", "history"));
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		var dto = MessageDTO.MessageDTOFactory.fromChatMessage(message);
		var restored = mapper.readValue(mapper.writeValueAsBytes(dto), MessageDTO.class);
		assertEquals(message, MessageDTO.MessageDTOFactory.toMessage(restored));
	}

	@Test
	void userInputPreservesMetadataAndBinaryOrBase64Media() {
		var dto = new UserMessageDTO("question");
		dto.setMetadata(Map.of("source", "caller"));
		dto.setMedia(List.of(media("image/png", new byte[] { 1, 2, 3 }), media("audio/wav", "AQID"),
				media("application/pdf", "AQID")));
		var message = dto.toChatMessage();
		assertEquals(ChatRole.USER, message.role());
		assertEquals("question", message.text());
		assertEquals(Map.of("source", "caller"), message.metadata());
		assertEquals(
				List.of(ContentPart.Type.TEXT, ContentPart.Type.IMAGE, ContentPart.Type.AUDIO, ContentPart.Type.OTHER),
				message.content().stream().map(ContentPart::type).toList());
		for (int index = 1; index < message.content().size(); index++) {
			assertArrayEquals(new byte[] { 1, 2, 3 }, message.content().get(index).data());
		}
	}

	@Test
	void userInputRejectsMissingMimeInvalidDataAndInvalidBase64() {
		for (var item : List.of(media(null, "AQID"), media(" ", "AQID"), media("image/png", null),
				media("image/png", 42), media("image/png", "not base64!"))) {
			var dto = new UserMessageDTO("question");
			dto.setMedia(List.of(item));
			assertThrows(IllegalArgumentException.class, dto::toChatMessage);
		}
		var dto = new UserMessageDTO();
		dto.setMetadata(null);
		dto.setMedia(null);
		assertEquals(List.of(), dto.toChatMessage().content());
		assertEquals(Map.of(), dto.toChatMessage().metadata());
	}

	private static UserMessageDTO.MediaDTO media(String mimeType, Object data) {
		var media = new UserMessageDTO.MediaDTO();
		media.setMimeType(mimeType);
		media.setData(data);
		return media;
	}

	@Test
	void mapsVisibleRolesAndHidesSystemMessages() {
		assertNull(MessageDTO.MessageDTOFactory.fromChatMessage(null));
		assertNull(MessageDTO.MessageDTOFactory.fromChatMessage(ChatMessage.system("instruction")));
		assertInstanceOf(UserMessageDTO.class,
				MessageDTO.MessageDTOFactory.fromChatMessage(ChatMessage.user("question")));
		var message = new ChatMessage(ChatRole.ASSISTANT, List.of(ContentPart.text("answer")),
				Map.of("isReasoning", false));
		var dto = assertInstanceOf(AssistantMessageDTO.class, MessageDTO.MessageDTOFactory.fromChatMessage(message));
		assertEquals("answer", dto.getContent());
		assertEquals(message.metadata(), dto.getMetadata());
	}

	@Test
	void retainsMultipleCallsAndIndividualResultIdentifiers() {
		var calls = List.of(new ToolCall("one", "lookup", "{\"key\":1}"), new ToolCall("two", "lookup", "{\"key\":2}"));
		var request = assertInstanceOf(ToolRequestMessageDTO.class,
				MessageDTO.MessageDTOFactory.fromChatMessage(ChatMessage.assistant("", calls)));
		assertEquals(2, request.getToolCalls().size());
		for (int index = 0; index < calls.size(); index++) {
			var call = calls.get(index);
			var tool = request.getToolCalls().get(index);
			assertEquals(call.id(), tool.getId());
			assertEquals(call.name(), tool.getName());
			assertEquals(call.arguments(), tool.getArguments());
			var result = assertInstanceOf(ToolResponseMessageDTO.class,
					MessageDTO.MessageDTOFactory.fromChatMessage(ChatMessage.tool(call.id(), call.name(), "result")));
			assertEquals(1, result.getResponses().size());
			assertEquals(call.id(), result.getResponses().get(0).getId());
			assertEquals(call.name(), result.getResponses().get(0).getName());
			assertEquals("result", result.getResponses().get(0).getResponseData());
		}
	}

}
