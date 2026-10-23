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

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.List;
import java.util.Map;

/** Field serializers keep checkpoint storage out of the public chat contracts. */
final class ArgiChatSerializers {

	private ArgiChatSerializers() {
	}

	static boolean supports(Object value) {
		return value instanceof ChatEvent || value instanceof ChatMessage || value instanceof ChatResponse
				|| value instanceof ContentPart || value instanceof ToolCall || value instanceof Usage;
	}

	static void register(SerializerMapper mapper) {
		mapper.register(ChatEvent.class, new EventSerializer());
		mapper.register(ChatMessage.class, new MessageSerializer());
		mapper.register(ChatResponse.class, new ResponseSerializer());
		mapper.register(ContentPart.class, new ContentSerializer());
		mapper.register(ToolCall.class, new ToolCallSerializer());
		mapper.register(Usage.class, new UsageSerializer());
	}

	private static final class EventSerializer implements NullableObjectSerializer<ChatEvent> {

		@Override
		public void write(ChatEvent event, ObjectOutput out) throws IOException {
			out.writeObject(event.type());
			writeNullableUTF(event.text(), out);
			writeNullableObject(event.toolCall(), out);
			writeNullableObject(event.response(), out);
			writeNullableObject(event.usage(), out);
			out.writeObject(event.metadata());
		}

		@Override
		@SuppressWarnings("unchecked")
		public ChatEvent read(ObjectInput in) throws IOException, ClassNotFoundException {
			return new ChatEvent((ChatEvent.Type) in.readObject(), readNullableUTF(in).orElse(null),
					(ToolCall) readNullableObject(in).orElse(null), (ChatResponse) readNullableObject(in).orElse(null),
					(Usage) readNullableObject(in).orElse(null), (Map<String, Object>) in.readObject());
		}

	}

	private static final class MessageSerializer implements NullableObjectSerializer<ChatMessage> {

		@Override
		public void write(ChatMessage message, ObjectOutput out) throws IOException {
			out.writeObject(message.role());
			out.writeObject(message.content());
			out.writeObject(message.toolCalls());
			writeNullableUTF(message.toolCallId(), out);
			writeNullableUTF(message.toolName(), out);
			out.writeObject(message.metadata());
		}

		@Override
		@SuppressWarnings("unchecked")
		public ChatMessage read(ObjectInput in) throws IOException, ClassNotFoundException {
			return new ChatMessage((ChatRole) in.readObject(), (List<ContentPart>) in.readObject(),
					(List<ToolCall>) in.readObject(), readNullableUTF(in).orElse(null),
					readNullableUTF(in).orElse(null), (Map<String, Object>) in.readObject());
		}

	}

	private static final class ResponseSerializer implements NullableObjectSerializer<ChatResponse> {

		@Override
		public void write(ChatResponse response, ObjectOutput out) throws IOException {
			writeNullableUTF(response.id(), out);
			writeNullableUTF(response.model(), out);
			out.writeObject(response.messages());
			out.writeObject(response.toolCalls());
			writeNullableObject(response.finishReason(), out);
			out.writeObject(response.usage());
			out.writeObject(response.metadata());
		}

		@Override
		@SuppressWarnings("unchecked")
		public ChatResponse read(ObjectInput in) throws IOException, ClassNotFoundException {
			return new ChatResponse(readNullableUTF(in).orElse(null), readNullableUTF(in).orElse(null),
					(List<ChatMessage>) in.readObject(), (List<ToolCall>) in.readObject(),
					(FinishReason) readNullableObject(in).orElse(null), (Usage) in.readObject(),
					(Map<String, Object>) in.readObject());
		}

	}

	private static final class ContentSerializer implements NullableObjectSerializer<ContentPart> {

		@Override
		public void write(ContentPart part, ObjectOutput out) throws IOException {
			out.writeObject(part.type());
			writeNullableUTF(part.text(), out);
			writeNullableUTF(part.mimeType(), out);
			writeNullableObject(part.data(), out);
			out.writeObject(part.metadata());
		}

		@Override
		@SuppressWarnings("unchecked")
		public ContentPart read(ObjectInput in) throws IOException, ClassNotFoundException {
			return new ContentPart((ContentPart.Type) in.readObject(), readNullableUTF(in).orElse(null),
					readNullableUTF(in).orElse(null), (byte[]) readNullableObject(in).orElse(null),
					(Map<String, Object>) in.readObject());
		}

	}

	private static final class ToolCallSerializer implements NullableObjectSerializer<ToolCall> {

		@Override
		public void write(ToolCall call, ObjectOutput out) throws IOException {
			writeNullableUTF(call.id(), out);
			writeNullableUTF(call.name(), out);
			writeNullableUTF(call.arguments(), out);
		}

		@Override
		public ToolCall read(ObjectInput in) throws IOException {
			return new ToolCall(readNullableUTF(in).orElse(null), readNullableUTF(in).orElse(null),
					readNullableUTF(in).orElse(null));
		}

	}

	private static final class UsageSerializer implements NullableObjectSerializer<Usage> {

		@Override
		public void write(Usage usage, ObjectOutput out) throws IOException {
			writeNullableObject(usage.inputTokens(), out);
			writeNullableObject(usage.outputTokens(), out);
			writeNullableObject(usage.totalTokens(), out);
		}

		@Override
		public Usage read(ObjectInput in) throws IOException, ClassNotFoundException {
			return new Usage((Long) readNullableObject(in).orElse(null), (Long) readNullableObject(in).orElse(null),
					(Long) readNullableObject(in).orElse(null));
		}

	}

}
