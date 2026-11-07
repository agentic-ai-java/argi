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
 * Identity of one tool call inside an invocation.
 *
 * <p>
 * A tool call is one request to a tool binding and its result. Retries of the same
 * tool call keep the same identity and differ in their attempt identity.
 *
 * @param value the opaque tool call identifier
 */
public record ToolCallId(String value) {

	/**
	 * Creates a tool call identity.
	 * @param value the opaque tool call identifier, must not be null
	 */
	public ToolCallId {
		Objects.requireNonNull(value, "value must not be null");
	}

	/**
	 * Creates a tool call identity from its textual form.
	 * @param value the opaque tool call identifier, must not be null
	 * @return the tool call identity
	 */
	public static ToolCallId of(String value) {
		return new ToolCallId(value);
	}

}
