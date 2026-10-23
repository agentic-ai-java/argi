/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphResponseMetadataAllocationTest {

	@Test
	void emptyResponsesShareMetadataUntilMutation() {
		GraphResponse<?> first = GraphResponse.done();
		GraphResponse<?> second = GraphResponse.done("result");

		assertSame(first.metadata, second.metadata);
		assertTrue(first.metadata.isEmpty());

		first.addMetadata("traceId", "trace-1");

		assertEquals("trace-1", first.getMetadata("traceId"));
		assertFalse(second.hasMetadata("traceId"));
		assertTrue(second.metadata.isEmpty());
	}

	@Test
	void emptyMetadataConstructorUsesSharedStorage() {
		GraphResponse<?> first = GraphResponse.done("first", Map.of());
		GraphResponse<?> second = GraphResponse.of("second", Map.of());

		assertSame(first.metadata, second.metadata);
	}

	@Test
	void nonEmptyMetadataIsDefensivelyCopied() {
		Map<String, Object> source = new HashMap<>();
		source.put("traceId", "trace-1");

		GraphResponse<?> response = GraphResponse.done("result", source);
		source.put("traceId", "changed");

		assertEquals("trace-1", response.getMetadata("traceId"));
	}

	@Test
	void defaultConstructorSupportsMetadataMutation() {
		GraphResponse<?> response = new GraphResponse<>();

		assertNull(response.removeMetadata("missing"));
		response.addMetadata("traceId", "trace-1");
		assertEquals("trace-1", response.removeMetadata("traceId"));
		assertTrue(response.metadata.isEmpty());
	}

}
