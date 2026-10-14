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
package io.github.agentic.ai.graph.agent.hook;

import java.util.List;
import java.util.Map;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenCounterTest {

	@Test
	void countsNeutralTextJsonToolArgumentsAndResults() {
		List<ChatMessage> messages = List.of(ChatMessage.system("abcd"), ChatMessage.user("abcdefgh"),
				new ChatMessage(ChatRole.ASSISTANT,
						List.of(ContentPart.text("abcd"), ContentPart.json("{}{}"),
								ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[100])),
						List.of(new ToolCall("id", "search", "abcdefgh")), null, null, Map.of()),
				ChatMessage.tool("id", "search", "abcdefgh"));
		assertEquals(9, TokenCounter.approximateMsgCounter().countTokens(messages));
		assertEquals(18, TokenCounter.approximateMsgCounter(2).countTokens(messages));
		assertEquals(0, TokenCounter.approximateMsgCounter().countTokens(List.of()));
	}

	@Test
	void rejectsNonPositiveRatios() {
		assertThrows(IllegalArgumentException.class, () -> TokenCounter.approximateMsgCounter(0));
		assertThrows(IllegalArgumentException.class, () -> TokenCounter.approximateMsgCounter(-1));
	}

}
