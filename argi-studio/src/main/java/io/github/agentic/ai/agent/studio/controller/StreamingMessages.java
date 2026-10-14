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
package io.github.agentic.ai.agent.studio.controller;

import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.util.List;
import java.util.stream.IntStream;
import io.github.agentic.ai.api.chat.Usage;

/** Projects multi-message turns onto the Studio's single-message SSE envelope. */
final class StreamingMessages {

	private StreamingMessages() {
	}

	static List<NodeOutput> split(NodeOutput output) {
		if (!(output instanceof StreamingOutput<?> streamed) || streamed.messages().size() < 2) {
			return List.of(output);
		}
		return IntStream.range(0, streamed.messages().size()).<NodeOutput>mapToObj(index -> {
			var message = streamed.messages().get(index);
			StreamingOutput<?> item = new StreamingOutput<>(message, streamed.getOriginData(), output.node(),
					output.agent(), output.state(), streamed.getOutputType());
			// Usage belongs to the turn, not each projected message.
			item.setTokenUsage(index == streamed.messages().size() - 1 ? output.tokenUsage() : Usage.unknown());
			item.setSubGraph(output.isSubGraph());
			return item;
		}).toList();
	}

}
