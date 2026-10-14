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
package io.github.agentic.ai.graph.node;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpNodeTest {

	@Test
	void redactsSensitiveMapValuesBeforeLogging() {
		Object redacted = McpNode.redactForLogging(Map.of("Authorization", "Bearer secret-token", "query",
				"normal value", "nested", Map.of("apiKey", "secret-key")));

		String logValue = redacted.toString();

		assertTrue(logValue.contains("Authorization=<redacted>"));
		assertTrue(logValue.contains("apiKey=<redacted>"));
		assertTrue(logValue.contains("query=normal value"));
		assertFalse(logValue.contains("secret-token"));
		assertFalse(logValue.contains("secret-key"));
	}

	@Test
	void truncatesLongStringLogValues() {
		String value = "x".repeat(160);

		Object redacted = McpNode.redactForLogging(value);

		assertEquals(142, redacted.toString().length());
		assertTrue(redacted.toString().endsWith("...<truncated>"));
	}

	@Test
	void removesCredentialsFromLoggedUrls() {
		String logged = McpNode
			.redactUrlForLogging("https://user:password@mcp.example:8443/sse?api_key=secret-token#session-secret");

		assertEquals("https://mcp.example:8443/sse", logged);
		assertFalse(logged.contains("password"));
		assertFalse(logged.contains("secret-token"));
		assertFalse(logged.contains("session-secret"));
	}

}
