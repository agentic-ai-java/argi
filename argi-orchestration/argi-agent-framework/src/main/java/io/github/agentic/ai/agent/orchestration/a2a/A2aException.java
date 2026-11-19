/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.agent.orchestration.a2a;

/**
 * Base type of the structured A2A client failures. Transport, protocol and remote task
 * failures are propagated as these exceptions (issue #213) so callers can distinguish a
 * failed remote call from a normal completion whose text merely mentions an error.
 *
 * <p>The hierarchy extends {@link IllegalStateException} because that is the exception
 * type the A2A client historically threw for these conditions; existing
 * {@code catch (IllegalStateException)} callers keep working unchanged.</p>
 */
public class A2aException extends IllegalStateException {

	/**
	 * Constructs a new A2A failure with the specified error message.
	 * @param message the detail message
	 */
	protected A2aException(String message) {
		super(message);
	}

	/**
	 * Constructs a new A2A failure with the specified error message and cause.
	 * @param message the detail message
	 * @param cause the cause
	 */
	protected A2aException(String message, Throwable cause) {
		super(message, cause);
	}

}
