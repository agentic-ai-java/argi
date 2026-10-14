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
package io.github.agentic.ai.graph.agent.interceptor.toolerror;

import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;

public class ToolErrorInterceptor extends ToolInterceptor {

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		try {
			return handler.call(request);
		} catch (Exception e) {
			return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
					"Tool failed: " + e.getMessage());
		}
	}

	@Override
	public String getName() {
		return "ToolError";
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {
		public ToolErrorInterceptor build() {
			return new ToolErrorInterceptor();
		}
	}
}

