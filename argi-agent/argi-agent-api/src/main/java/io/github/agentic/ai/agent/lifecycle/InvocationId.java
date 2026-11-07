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
 * Identity of one agent invocation.
 *
 * <p>
 * An invocation is the outermost unit of work: one call into an agent. Every attempt,
 * step, model turn, tool call and delegation belongs to exactly one invocation.
 *
 * @param value the opaque invocation identifier
 */
public record InvocationId(String value) {

	/**
	 * Creates an invocation identity.
	 * @param value the opaque invocation identifier, must not be null
	 */
	public InvocationId {
		Objects.requireNonNull(value, "value must not be null");
	}

	/**
	 * Creates an invocation identity from its textual form.
	 * @param value the opaque invocation identifier, must not be null
	 * @return the invocation identity
	 */
	public static InvocationId of(String value) {
		return new InvocationId(value);
	}

}
