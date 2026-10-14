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
package io.github.agentic.ai.adapter.springai;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/** Explicitly adapts Spring AI tools; the agent runtime owns scheduling and timeouts. */
public final class SpringAiToolAdapter implements Tool {

	private final ToolCallback callback;

	private final ToolDefinition definition;

	public SpringAiToolAdapter(ToolCallback callback) {
		this.callback = Objects.requireNonNull(callback, "callback must not be null");
		var nativeDefinition = callback.getToolDefinition();
		this.definition = new ToolDefinition(nativeDefinition.name(), nativeDefinition.description(),
				nativeDefinition.inputSchema());
	}

	public static ToolRegistry fromCallbacks(ToolCallback... callbacks) {
		return fromCallbacks(Arrays.asList(callbacks));
	}

	public static ToolRegistry fromCallbacks(Collection<? extends ToolCallback> callbacks) {
		Objects.requireNonNull(callbacks, "callbacks must not be null");
		return new ToolRegistry(callbacks.stream().map(SpringAiToolAdapter::new).toList());
	}

	/** Converts Spring AI's annotated method tools at the adapter boundary. */
	public static ToolRegistry fromMethods(Object... objects) {
		return fromCallbacks(ToolCallbacks.from(objects));
	}

	@Override
	public ToolDefinition definition() {
		return definition;
	}

	@Override
	public boolean returnDirect() {
		return callback.getToolMetadata().returnDirect();
	}

	@Override
	public CompletableFuture<ToolResult> execute(ToolCall call, ToolContext context) {
		Objects.requireNonNull(call, "call must not be null");
		Objects.requireNonNull(context, "context must not be null");
		try {
			String result = callback.call(call.arguments(),
					new org.springframework.ai.chat.model.ToolContext(context.attributes()));
			return CompletableFuture.completedFuture(ToolResult.text(result));
		}
		catch (Exception failure) {
			return CompletableFuture.failedFuture(failure);
		}
	}

}
