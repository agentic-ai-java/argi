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
 * Identity of one model turn inside an invocation.
 *
 * <p>
 * A model turn is one request to a model and its answer. It is deliberately not the
 * message identity: {@code AgentEvent.messageId} identifies a message, while a model
 * turn identifies the model interaction that produced it. A workflow step does not
 * create a model turn on its own.
 *
 * @param value the opaque model turn identifier
 */
public record ModelTurnId(String value) {

	/**
	 * Creates a model turn identity.
	 * @param value the opaque model turn identifier, must not be null
	 */
	public ModelTurnId {
		Objects.requireNonNull(value, "value must not be null");
	}

	/**
	 * Creates a model turn identity from its textual form.
	 * @param value the opaque model turn identifier, must not be null
	 * @return the model turn identity
	 */
	public static ModelTurnId of(String value) {
		return new ModelTurnId(value);
	}

}
