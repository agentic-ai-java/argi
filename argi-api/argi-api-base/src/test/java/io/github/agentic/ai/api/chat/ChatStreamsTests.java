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
package io.github.agentic.ai.api.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatStreamsTests {

	@Test
	void returnsOnlyUnseenTextIncludingEmptyAndUnicode() {
		assertEquals("hello", ChatStreams.unseenSuffix("hello", ""));
		assertEquals("", ChatStreams.unseenSuffix("hello", new StringBuilder("hello")));
		assertEquals(" world", ChatStreams.unseenSuffix("hello world", "hello"));
		assertEquals("!", ChatStreams.unseenSuffix("\uD83D\uDE00!", "\uD83D\uDE00"));
		assertEquals("", ChatStreams.unseenSuffix("", ""));
	}

	@Test
	void rejectsRewritesAndTruncatedAggregates() {
		assertThrows(IllegalStateException.class, () -> ChatStreams.unseenSuffix("hello", "hello "));
		assertThrows(IllegalStateException.class, () -> ChatStreams.unseenSuffix("Hello", "hello"));
		assertThrows(NullPointerException.class, () -> ChatStreams.unseenSuffix(null, ""));
		assertThrows(NullPointerException.class, () -> ChatStreams.unseenSuffix("", null));
	}

}
