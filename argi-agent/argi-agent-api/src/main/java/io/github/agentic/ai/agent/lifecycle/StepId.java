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
 * Identity of one step inside an invocation.
 *
 * <p>
 * A step is a unit of work that may contain model turns and tool calls. A step is
 * stable across retries of the same work; the attempts below it are not.
 *
 * @param value the opaque step identifier
 */
public record StepId(String value) {

	/**
	 * Creates a step identity.
	 * @param value the opaque step identifier, must not be null
	 */
	public StepId {
		Objects.requireNonNull(value, "value must not be null");
	}

	/**
	 * Creates a step identity from its textual form.
	 * @param value the opaque step identifier, must not be null
	 * @return the step identity
	 */
	public static StepId of(String value) {
		return new StepId(value);
	}

}
