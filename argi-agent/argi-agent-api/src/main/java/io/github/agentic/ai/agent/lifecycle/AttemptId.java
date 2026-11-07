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
 * Identity of one attempt inside an invocation.
 *
 * <p>
 * An attempt is a retry of a unit of work. It is distinct from the invocation itself
 * and from a step: a retried model call is a new attempt of the same step.
 *
 * @param value the opaque attempt identifier
 */
public record AttemptId(String value) {

	/**
	 * Creates an attempt identity.
	 * @param value the opaque attempt identifier, must not be null
	 */
	public AttemptId {
		Objects.requireNonNull(value, "value must not be null");
	}

	/**
	 * Creates an attempt identity from its textual form.
	 * @param value the opaque attempt identifier, must not be null
	 * @return the attempt identity
	 */
	public static AttemptId of(String value) {
		return new AttemptId(value);
	}

}
