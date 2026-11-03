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
package io.github.agentic.ai.agent.durable;

import static java.util.Objects.requireNonNull;

/**
 * Read-only view of a recorded tool plan, not permission to execute it. The definition
 * fingerprint describes the disclosed schema, not the tool implementation version, and
 * may be null when no definition was supplied. Arguments retain their stored form,
 * including malformed JSON. Tool resolution, argument validation and authorization are
 * execution-boundary responsibilities. The argument hash checks input consistency; it
 * does not identify the logical task.
 *
 * <p>
 * The wire field {@code definitionVersion} maps to {@code definitionFingerprint} here.
 * For protocol correlation, use the identity's ordinal to read the provider call id
 * from the corresponding response's {@code toolCalls()} list.
 */
public record PlannedToolCall(ToolTaskIdentity identity, String toolName, String definitionFingerprint,
		String argumentsHash, String arguments) {

	public PlannedToolCall {
		requireNonNull(identity, "identity cannot be null");
		requireNonNull(toolName, "toolName cannot be null");
		requireNonNull(argumentsHash, "argumentsHash cannot be null");
		requireNonNull(arguments, "arguments cannot be null");
	}

}
