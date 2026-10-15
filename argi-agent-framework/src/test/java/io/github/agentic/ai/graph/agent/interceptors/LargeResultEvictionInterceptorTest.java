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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.agent.extension.file.FilesystemBackend;
import io.github.agentic.ai.graph.agent.extension.file.WriteResult;
import io.github.agentic.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link LargeResultEvictionInterceptor} preserves the structured
 * metadata of the original response when evicting a large result, instead of silently
 * dropping the failure context it carried.
 */
@DisplayName("LargeResultEvictionInterceptor metadata preservation")
class LargeResultEvictionInterceptorTest {

	@Test
	@DisplayName("evicting a large result keeps the original response metadata")
	void evictionKeepsOriginalMetadata() {
		FilesystemBackend backend = mock(FilesystemBackend.class);
		when(backend.write(anyString(), anyString())).thenReturn(new WriteResult("large_tool_results/call_1", null, null));

		// 1 token limit triggers eviction for any content longer than 4 characters.
		LargeResultEvictionInterceptor interceptor = LargeResultEvictionInterceptor.builder()
			.toolTokenLimitBeforeEvict(1)
			.backend(backend)
			.build();

		Map<String, Object> metadata = new HashMap<>();
		metadata.put(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_EXECUTION);
		metadata.put("errorCode", "SLOW_QUERY");
		ToolCallResponse original = ToolCallResponse.builder()
			.content("x".repeat(500))
			.toolName("reportTool")
			.toolCallId("call_1")
			.status(ToolCallResponse.SUCCESS_STATUS)
			.metadata(metadata)
			.build();

		ToolCallResponse evicted = interceptor.interceptToolCall(
				new ToolCallRequest("reportTool", "{}", "call_1", new HashMap<>()), r -> original);

		assertThat(evicted.getStatus()).isEqualTo("evicted_to_filesystem");
		assertThat(evicted.getMetadata())
			.containsEntry(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_EXECUTION)
			.containsEntry("errorCode", "SLOW_QUERY");
	}

}
