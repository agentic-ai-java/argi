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

/**
 * Opt-in, backend-neutral tool catalog: authorized summary retrieval and on-demand schema
 * release for large tool sets.
 *
 * <p>
 * Nothing in this package is active unless an application builds a
 * {@link io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalog} and installs it, for
 * example through
 * {@link io.github.agentic.ai.graph.agent.interceptor.toolcatalog.ToolCatalogInterceptor}.
 * Existing tool registries, tool selection, skills disclosure and checkpoint formats are
 * unchanged.
 * </p>
 *
 * <h2>Contract</h2>
 * <ul>
 * <li><b>Identity.</b> A {@link io.github.agentic.ai.graph.agent.tool.catalog.ToolId} is
 * {@code provider + name}. Its qualified name ({@code provider__name}) is also the name
 * disclosed to the model, so equal names from different providers never collide.</li>
 * <li><b>Retrieval.</b>
 * {@link io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogProvider providers}
 * expose lightweight {@link io.github.agentic.ai.graph.agent.tool.catalog.ToolSummary
 * summaries}. Every summary passes the trusted
 * {@link io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogAccessPolicy} before a
 * {@link io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogSearchStrategy} ranks
 * it; unauthorized tools are never recalled, not even as summaries. Keyword and rule
 * retrieval ships with the core; embedding or LLM strategies plug in through the same
 * interface without a new datastore.</li>
 * <li><b>Schema release.</b>
 * {@link io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalog#offer} re-checks access
 * for every id, so guessed ids are rejected, and records each released definition as an
 * {@link io.github.agentic.ai.graph.agent.tool.catalog.OfferedTool} pinned by version and
 * digest. A recorded offer resolves to exactly that version or fails; it maps onto a
 * {@code tool_catalog} definition reference of the run definition model.</li>
 * <li><b>Execution.</b> Retrieval and schema release are not execution grants. Tool calls
 * are still authorized at the tool execution boundary.</li>
 * <li><b>Failure and bounds.</b> Empty results, provider failures and strategy failures
 * disclose no tool. Results and offers are bounded. Only summaries are cached, per
 * provider revision; access decisions are never cached.</li>
 * </ul>
 */
package io.github.agentic.ai.graph.agent.tool.catalog;
