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
package io.github.agentic.ai.api.tool;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import java.util.concurrent.CompletionStage;

/** An executable ARGI tool. Model adapters receive only its declaration. */
public interface Tool {

	ToolDefinition definition();

	/**
	 * Executes one complete invocation. Implementations own their asynchronous resources.
	 */
	CompletionStage<ToolResult> execute(ToolCall call, ToolContext context);

	/**
	 * Whether the runtime should return this tool's result without another model turn.
	 */
	default boolean returnDirect() {
		return false;
	}

}
