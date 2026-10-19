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
package io.github.agentic.ai.graph.agent.hook.contentsafety;

import java.util.Map;

/** Result of inspecting one text payload. {@code output} is safe to forward. */
public record ContentSafetyDecision(ContentSafetyStage stage, ContentSafetyAction action, String output,
		int matchStart, int matchEnd, Map<String, Object> status) {

	public ContentSafetyDecision {
		status = status == null ? Map.of() : Map.copyOf(status);
	}

	public boolean blocksDispatch() {
		return action == ContentSafetyAction.BLOCK || action == ContentSafetyAction.REQUIRE_REVIEW;
	}

}
