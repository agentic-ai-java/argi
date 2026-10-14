/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.streaming;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.stream.Collectors;
import static java.lang.String.format;

public class StreamingOutput<T> extends NodeOutput {

	private final List<ChatMessage> messages;

	@JsonIgnore
	private final T originData;

	private OutputType outputType;

	public StreamingOutput(T originData, String node, OverAllState state) {
		this(originData, node, null, state, null);
	}

	// agentName is for graph and node working on Agent mode
	public StreamingOutput(T originData, String node, String agentName, OverAllState state) {
		this(originData, node, agentName, state, null);
	}

	public StreamingOutput(T originData, String node, String agentName, OverAllState state, OutputType outputType) {
		this(messagesFrom(originData), originData, node, agentName, state, outputType);
	}

	public StreamingOutput(ChatMessage message, T originData, String node, String agentName, OverAllState state,
			OutputType outputType) {
		this(List.of(message), originData, node, agentName, state, outputType);
	}

	/** A tool turn may contain multiple independent result messages. */
	public StreamingOutput(List<ChatMessage> messages, T originData, String node, String agentName, OverAllState state,
			OutputType outputType) {
		super(node, agentName, state);
		this.messages = List.copyOf(messages);
		this.originData = originData;
		this.outputType = outputType;
		trySetTokenUsage(originData);
	}

	public StreamingOutput(List<ChatMessage> messages, String node, String agentName, OverAllState state, Usage usage,
			OutputType outputType) {
		this(messages, null, node, agentName, state, outputType);
		setTokenUsage(usage);
	}

	public StreamingOutput(String node, String agentName, OverAllState state, Usage usage, OutputType outputType) {
		this(List.of(), node, agentName, state, usage, outputType);
	}

	private static List<ChatMessage> messagesFrom(Object origin) {
		if (origin instanceof ChatMessage message) {
			return List.of(message);
		}
		if (origin instanceof ChatResponse response) {
			return response.messages();
		}
		if (origin instanceof ChatEvent event) {
			return switch (event.type()) {
				case TEXT_DELTA -> event.text() == null ? List.of() : List.of(new ChatMessage(ChatRole.ASSISTANT,
						List.of(io.github.agentic.ai.api.chat.ContentPart.text(event.text())), event.metadata()));
				case RESPONSE -> event.response() == null ? List.of() : event.response().messages();
				case TOOL_CALL, USAGE -> List.of();
			};
		}
		if (origin instanceof String text) {
			return List.of(ChatMessage.assistant(text));
		}
		return List.of();
	}

	private void trySetTokenUsage(T originData) {
		if (originData instanceof ChatResponse chatResponse) {
			setTokenUsage(chatResponse.usage());
		}
		else if (originData instanceof ChatEvent event) {
			if (event.usage() != null) {
				setTokenUsage(event.usage());
			}
			else if (event.response() != null) {
				setTokenUsage(event.response().usage());
			}
		}
		else if (originData instanceof Usage usage) {
			setTokenUsage(usage);
		}
	}

	/** Visible assistant text only, excluding tool requests and results. */
	public String text() {
		return messages.stream()
			.filter(message -> message.role() == ChatRole.ASSISTANT && message.toolCalls().isEmpty())
			.map(ChatMessage::text)
			.collect(Collectors.joining());
	}

	@JsonIgnore
	public T getOriginData() {
		return originData;
	}

	public List<ChatMessage> messages() {
		return messages;
	}

	public OutputType getOutputType() {
		return outputType;
	}

	public void setOutputType(OutputType outputType) {
		this.outputType = outputType;
	}

	@Override
	public String toString() {
		return format("StreamingOutput{node=%s, agent=%s, messages=%s, tokenUsage=%s, state=%s, subGraph=%s}", node(),
				agent(), messages(), tokenUsage(), state(), isSubGraph());
	}

}
