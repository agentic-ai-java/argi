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

package io.github.agentic.ai.graph.agent.flow.agent.loop;

import io.github.agentic.ai.api.chat.ChatMessage;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.internal.JsonCodec;
import org.springframework.core.convert.converter.Converter;

import java.util.List;
import java.util.Map;

/**
 * JSON array loop strategy that retrieves a JSON array from the current message state,
 * sends each array element as a message to the model, and returns the result. By default,
 * the text of the last message is treated as a JSON array, but users can customize the
 * converter.
 *
 * @author vlsmb
 * @since 2025/11/1
 */
public class ArrayLoopStrategy implements LoopStrategy {

	private final Converter<List<ChatMessage>, List<?>> converter;

	public ArrayLoopStrategy(Converter<List<ChatMessage>, List<?>> converter) {
		this.converter = converter;
	}

	public ArrayLoopStrategy() {
		this(DEFAULT_MESSAGE_CONVERTER);
	}

	@Override
	public Map<String, Object> loopInit(OverAllState state) {
		@SuppressWarnings("unchecked")
		List<ChatMessage> messages = (List<ChatMessage>) state.value(LoopStrategy.MESSAGE_KEY).orElse(List.of());
		List<?> list = converter.convert(messages);
		if (list != null) {
			return Map.of(loopCountKey(), 0, loopFlagKey(), true, loopListKey(), list);
		}
		return Map.of(loopCountKey(), 0, loopFlagKey(), false, loopListKey(), List.of(), LoopStrategy.MESSAGE_KEY,
				ChatMessage.system("Invalid json array format"));
	}

	@Override
	public Map<String, Object> loopDispatch(OverAllState state) {
		List<?> list = state.value(loopListKey(), List.class).orElse(List.of());
		int index = state.value(loopCountKey(), maxLoopCount());
		if (index < list.size()) {
			ChatMessage message = ChatMessage.user(list.get(index).toString());
			return Map.of(loopCountKey(), index + 1, loopFlagKey(), true, LoopStrategy.MESSAGE_KEY, message);
		}
		else {
			return Map.of(loopFlagKey(), false);
		}
	}

	/**
	 * 默认的转换器，将最后一个消息的文本作为json数组
	 */
	private static final Converter<List<ChatMessage>, List<?>> DEFAULT_MESSAGE_CONVERTER = messages -> {
		String lastMessage;
		if (!messages.isEmpty()) {
			lastMessage = messages.get(messages.size() - 1).text();
		}
		else {
			lastMessage = null;
		}
		if (lastMessage == null) {
			return null;
		}
		return new JsonCodec().fromJson(lastMessage, List.class);
	};

}
