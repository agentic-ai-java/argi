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
 * Identity of one delegation inside an invocation.
 *
 * <p>
 * A delegation is the hand-over of work to another agent, plus the join of its result.
 * It is not a tool call: the delegated agent runs its own invocation with its own
 * lifecycle.
 *
 * @param value the opaque delegation identifier
 */
public record DelegationId(String value) {

	/**
	 * Creates a delegation identity.
	 * @param value the opaque delegation identifier, must not be null
	 */
	public DelegationId {
		Objects.requireNonNull(value, "value must not be null");
	}

	/**
	 * Creates a delegation identity from its textual form.
	 * @param value the opaque delegation identifier, must not be null
 * * @return the delegation identity
	 */
	public static DelegationId of(String value) {
		return new DelegationId(value);
	}

}
