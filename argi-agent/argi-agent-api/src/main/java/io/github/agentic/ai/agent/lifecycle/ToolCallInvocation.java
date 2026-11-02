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
package io.github.agentic.ai.agent.lifecycle;

import java.util.Objects;

/**
 * A tool call in the neutral lifecycle contract.
 *
 * <p>
 * The invocation is immutable. A restricted transformer may replace the arguments of
 * a tool call, but neither the identity, the tool call identity nor the tool name may
 * change: renaming a tool inside a transformation would silently redirect the call to
 * a different binding, which is an admission decision, not a transformation.
 *
 * <p>
 * The type deliberately carries no free-form context map. Extensions that need
 * additional data read it from the run state or the trusted execution context instead
 * of an untyped side channel.
 *
 * @param identity the identity of the work this call belongs to, never null
 * @param toolCall the tool call identity, never null
 * @param toolName the name of the tool binding, never null
 * @param arguments the serialized tool arguments, never null
 */
public record ToolCallInvocation(LifecycleIdentity identity, ToolCallId toolCall, String toolName, String arguments) {

	/**
	 * Creates a tool call invocation.
	 * @param identity the identity of the work this call belongs to, must not be null
	 * @param toolCall the tool call identity, must not be null
	 * @param toolName the name of the tool binding, must not be null
	 * @param arguments the serialized tool arguments, must not be null
	 */
	public ToolCallInvocation {
		Objects.requireNonNull(identity, "identity must not be null");
		Objects.requireNonNull(toolCall, "toolCall must not be null");
		Objects.requireNonNull(toolName, "toolName must not be null");
		Objects.requireNonNull(arguments, "arguments must not be null");
	}

}
