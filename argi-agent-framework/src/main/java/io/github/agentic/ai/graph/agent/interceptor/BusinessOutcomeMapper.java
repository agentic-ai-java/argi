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
package io.github.agentic.ai.graph.agent.interceptor;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.ToolResult;

/**
 * Maps the raw {@link ToolResult} of a single, explicitly registered tool to its
 * outcome, so that a business-level failure reported inside an otherwise successful
 * tool response can be surfaced as a framework-visible failure.
 *
 * <p>
 * The framework never inspects result content to guess whether a tool failed: only a
 * mapper registered for that exact tool name (see
 * {@link BusinessOutcomeMapperRegistry}) is consulted. A successful tool call whose
 * payload happens to contain an {@code "error"} field stays a success unless a mapper
 * says otherwise.
 * </p>
 *
 * <p>
 * Contract:
 * </p>
 * <ul>
 * <li>return the passed-in {@code result} unchanged when the call succeeded;</li>
 * <li>return an error-marked result (e.g. {@code ToolResult.error(...)} enriched with
 * metadata such as an {@code errorCode}) when the business outcome is a failure — the
 * response status then becomes {@link ToolOutcome#BUSINESS_FAILED};</li>
 * <li>never return {@code null}; implementations should avoid throwing, but an
 * exception escapes as a regular tool execution failure rather than being swallowed.</li>
 * </ul>
 */
@FunctionalInterface
public interface BusinessOutcomeMapper {

	/**
	 * Maps the outcome of one tool call.
	 * @param call the tool call being finalized
	 * @param result the raw result produced by the tool
	 * @return the outcome to report; must not be {@code null}
	 */
	ToolResult mapOutcome(ToolCall call, ToolResult result);

}
