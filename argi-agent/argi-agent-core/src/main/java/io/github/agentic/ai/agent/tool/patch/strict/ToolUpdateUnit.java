/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.agent.tool.patch.strict;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool call's outcome and its state patch, bound as a single update unit.
 *
 * <p>
 * Strict mode admits state updates at the granularity of a whole call: the tool result
 * and the patch it produced commit or discard together. The result message itself still
 * reaches the conversation — the model must see failures — but the patch reaches the
 * next turn's state only when {@link #resultOk()} is {@code true} and the patch passes
 * validation. A failed call's stray writes therefore can never land half-merged.
 * </p>
 *
 * @param toolName the tool whose call produced the unit
 * @param resultOk whether the call succeeded (error results, guard rejections,
 * cancellations and timeouts all fold into {@code false})
 * @param patch the state updates the call produced; stored as an unmodifiable copy
 */
public record ToolUpdateUnit(String toolName, boolean resultOk, Map<String, Object> patch) {

	public ToolUpdateUnit {
		patch = patch == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(patch));
	}

	/**
	 * Factory for a successful call's unit.
	 * @param toolName the tool that succeeded
	 * @param patch the patch the tool produced, or {@code null} for none
	 * @return the unit
	 */
	public static ToolUpdateUnit success(String toolName, Map<String, Object> patch) {
		return new ToolUpdateUnit(toolName, true, patch);
	}

	/**
	 * Factory for a failed call's unit — error result, guard rejection, cancellation or
	 * timeout. The patch, when present, is expected to be discarded on admission.
	 * @param toolName the tool that failed
	 * @param patch the partial patch the tool had written, or {@code null}
	 * @return the unit
	 */
	public static ToolUpdateUnit failure(String toolName, Map<String, Object> patch) {
		return new ToolUpdateUnit(toolName, false, patch);
	}

}
