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
package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.agent.internal.JsonCodec;
import io.github.agentic.ai.graph.agent.tool.multimodal.ToolMultimodalResult;

import java.lang.reflect.Type;

import java.util.List;
import java.util.Map;

/** Converts typed tool output without discarding structured or multimodal content. */
public class MessageToolCallResultConverter {

	/**
	 * Message metadata and media are preserved for adapters that support multimodal
	 * tools.
	 */
	public ToolResult convert(Object result, Type returnType) {
		if (result instanceof ToolResult toolResult) {
			return toolResult;
		}
		if (result instanceof ChatMessage message) {
			return new ToolResult(message.content(), false, message.metadata());
		}
		if (result instanceof ToolMultimodalResult multimodal) {
			return multimodal.toToolResult();
		}
		if (returnType == Void.TYPE || result == null) {
			return ToolResult.text("Done");
		}
		if (result instanceof String text) {
			return ToolResult.text(text);
		}
		return new ToolResult(List.of(ContentPart.json(new JsonCodec().toJson(result))), false, Map.of());
	}

}
