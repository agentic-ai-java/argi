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
package io.github.agentic.ai.autoconfigure.graph;

import java.lang.reflect.Method;
import java.util.Map;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.graph.GraphRunnerContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the optional {@code spring-ai-deepseek} dependency (PR #80).
 *
 * <p>
 * This starter module depends on graph-core but carries no DeepSeek dependency at
 * runtime, mirroring a downstream application that never installed DeepSeek. The
 * reasoning-content extraction must therefore never force
 * {@code DeepSeekAssistantMessage} to link: with the previous {@code instanceof} probe,
 * the first streamed plain assistant chunk threw {@link NoClassDefFoundError}.
 */
class GraphRunnerContextNoDeepSeekRuntimeTest {

	private static Method extractReasoningContent;

	@BeforeAll
	static void findExtractionMethod() throws Exception {
		// Precondition guard: if a dependency change ever puts spring-ai-deepseek back
		// on this module's runtime classpath, this test can no longer exercise the
		// absence path and must not pass silently.
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("org.springframework.ai.deepseek.DeepSeekAssistantMessage", false,
						GraphRunnerContextNoDeepSeekRuntimeTest.class.getClassLoader()),
				"spring-ai-deepseek must be absent from this module's runtime classpath");
		extractReasoningContent = GraphRunnerContext.class.getDeclaredMethod("extractReasoningContent",
				ChatMessage.class);
		extractReasoningContent.setAccessible(true);
	}

	@Test
	void plainAssistantMessageDoesNotForceDeepSeekClassLinkage() {
		ChatMessage plain = ChatMessage.assistant("ordinary answer");
		String result = assertDoesNotThrow(() -> (String) extractReasoningContent.invoke(null, plain),
				"reasoning extraction must not link the optional DeepSeek class");
		assertNull(result);
	}

	@Test
	void metadataCarriedReasoningContentIsExtractedWithoutDeepSeek() {
		ChatMessage withReasoning = ChatMessage.assistant("answer")
			.withMetadata(Map.of(GraphRunnerContext.REASONING_CONTENT_METADATA_KEY, "thinking..."));
		String result = assertDoesNotThrow(() -> (String) extractReasoningContent.invoke(null, withReasoning));
		assertEquals("thinking...", result);
	}

	@Test
	void messageMetadataContractStaysIntact() {
		// The metadata key constants are the public contract downstream consumers code
		// against.
		assertEquals("isReasoning", GraphRunnerContext.IS_REASONING_METADATA_KEY);
		assertEquals("reasoning", GraphRunnerContext.REASONING_CONTENT_METADATA_KEY);
		assertTrue(GraphRunnerContext.REASONING_CONTENT_METADATA_KEY.length() > 0);
	}

}
