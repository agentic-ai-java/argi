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

package io.github.agentic.ai.graph.agent.tool.catalog;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Exposes a provider tool under its qualified name and translates calls back to the
 * provider-local name. Authorization is not performed here; the tool execution boundary
 * still authorizes the qualified name.
 */
final class CatalogBoundTool implements Tool {

	private final ToolId id;

	private final Tool delegate;

	private final ToolDefinition definition;

	CatalogBoundTool(ToolId id, Tool delegate) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		ToolDefinition source = Objects.requireNonNull(delegate.definition(), "tool definition must not be null");
		this.definition = new ToolDefinition(id.qualifiedName(), source.description(), source.inputSchema());
	}

	ToolId id() {
		return this.id;
	}

	@Override
	public ToolDefinition definition() {
		return this.definition;
	}

	@Override
	public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
		ToolCall local = new ToolCall(call.id(), this.delegate.definition().name(), call.arguments());
		return this.delegate.execute(local, context);
	}

	@Override
	public boolean returnDirect() {
		return this.delegate.returnDirect();
	}

}
