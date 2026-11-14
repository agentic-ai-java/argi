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
 * An A2A call failed at the transport layer: the HTTP exchange returned a non-success
 * status, carried no entity, was interrupted, or could not be started at all (for example
 * a missing agent card URL). The remote agent was never reached or never answered; no
 * statement can be made about its task state.
 */
public class A2aTransportException extends A2aException {

	/**
	 * Constructs a new transport failure with the specified error message.
	 * @param message the detail message
	 */
	public A2aTransportException(String message) {
		super(message);
	}

	/**
	 * Constructs a new transport failure with the specified error message and cause.
	 * @param message the detail message
	 * @param cause the cause
	 */
	public A2aTransportException(String message, Throwable cause) {
		super(message, cause);
	}

}
