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

import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ToolErrorInterceptor} swallows handler exceptions into an
 * explicitly-shaped error response (status + error metadata) instead of a status-less
 * response, and passes successful results through untouched.
 */
class ToolErrorInterceptorTest {

	private ToolCallRequest request() {
		return new ToolCallRequest("t", "{}", "id-1", new HashMap<>());
	}

	@Test
	void successfulCallPassesThrough() {
		ToolCallResponse response = ToolErrorInterceptor.builder().build()
			.interceptToolCall(request(), r -> ToolCallResponse.success(r.getToolCallId(), r.getToolName(), "ok"));

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.SUCCESS_STATUS);
		assertThat(response.getResult()).isEqualTo("ok");
	}

	@Test
	void handlerExceptionBecomesExplicitErrorResponse() {
		ToolCallResponse response = ToolErrorInterceptor.builder().build()
			.interceptToolCall(request(), r -> {
				throw new IllegalStateException("backend down");
			});

		assertThat(response.getStatus()).isEqualTo(ToolCallResponse.ERROR_STATUS);
		assertThat(response.isError()).isTrue();
		assertThat(response.getResult()).contains("backend down");
		assertThat(response.getMetadata())
			.containsEntry("error", true)
			.containsEntry("errorMessage", "Tool failed: backend down");
	}

}
