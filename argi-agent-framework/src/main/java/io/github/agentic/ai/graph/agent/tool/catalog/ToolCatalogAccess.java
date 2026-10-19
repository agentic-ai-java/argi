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
 * The catalog operation an access check is made for.
 *
 * <p>
 * Both operations are checked independently on every call. Neither one authorizes tool
 * execution: execution is still decided by the tool execution boundary, for example
 * {@link io.github.agentic.ai.graph.agent.interceptor.authorization.ToolAuthorizationInterceptor}.
 * </p>
 */
public enum ToolCatalogAccess {

	/** Whether the caller may see the tool's summary in search results. */
	DISCOVER,

	/** Whether the caller may receive the tool's full input schema and binding. */
	LOAD_SCHEMA

}
