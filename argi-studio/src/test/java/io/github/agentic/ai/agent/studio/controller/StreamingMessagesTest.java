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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingMessagesTest {

	@Test
	void splitsToolResultsWithoutLosingOriginUsageOrSnapshot() {
		var messages = List.of(ChatMessage.tool("one", "lookup", "first"), ChatMessage.tool("two", "lookup", "second"));
		var state = new OverAllState(Map.of("messages", messages));
		var output = new StreamingOutput<>(messages, "origin", "tools", "agent", state, OutputType.AGENT_TOOL_FINISHED);
		var usage = new Usage(3L, 4L, 7L);
		output.setTokenUsage(usage);
		output.setSubGraph(true);
		var split = StreamingMessages.split(output);
		assertEquals(2, split.size());
		for (int index = 0; index < split.size(); index++) {
			var item = (StreamingOutput<?>) split.get(index);
			assertEquals(List.of(messages.get(index)), item.messages());
			assertEquals("", item.text());
			assertEquals("origin", item.getOriginData());
			assertEquals("tools", item.node());
			assertEquals("agent", item.agent());
			assertSame(state, item.state());
			assertEquals(index == split.size() - 1 ? usage : Usage.unknown(), item.tokenUsage());
			assertTrue(item.isSubGraph());
			assertEquals(OutputType.AGENT_TOOL_FINISHED, item.getOutputType());
		}
	}

	@Test
	void leavesSingleMessageAndNonMessageEventsUntouched() {
		var plain = NodeOutput.of("node", null, new OverAllState(), Usage.unknown());
		var single = new StreamingOutput<>(ChatMessage.assistant("answer"), "node", new OverAllState());
		var empty = new StreamingOutput<>(ChatMessage.system(""), null, "node", null, null, null);
		for (var output : List.of(plain, single, empty)) {
			assertSame(output, StreamingMessages.split(output).get(0));
		}
		var usage = new StreamingOutput<>("node", "agent", null, Usage.unknown(), OutputType.AGENT_MODEL_FINISHED);
		assertSame(usage, StreamingMessages.split(usage).get(0));
	}

}
