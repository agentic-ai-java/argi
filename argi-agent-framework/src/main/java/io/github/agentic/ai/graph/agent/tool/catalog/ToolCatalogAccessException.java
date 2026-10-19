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

/**
 * Thrown when a single schema request or a pinned resolution cannot be satisfied.
 *
 * <p>
 * The message intentionally does not reveal whether the tool is unknown, unauthorized or
 * no longer available, so a guessed id cannot be used to probe the catalog.
 * </p>
 */
public class ToolCatalogAccessException extends RuntimeException {

	private final ToolId toolId;

	public ToolCatalogAccessException(ToolId toolId) {
		super("Tool is not available from the catalog: " + toolId);
		this.toolId = toolId;
	}

	public ToolId getToolId() {
		return this.toolId;
	}

}
