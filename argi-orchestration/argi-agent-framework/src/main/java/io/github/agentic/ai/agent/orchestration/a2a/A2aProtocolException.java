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
 * The remote peer violated the A2A protocol: a response frame was not valid JSON, a
 * JSON-RPC error object was returned, a response carried no usable result, or an event
 * stream ended without a terminal state. The remote task state is unknown; any text
 * already delivered before the violation remains valid partial output.
 */
public class A2aProtocolException extends A2aException {

	/**
	 * Constructs a new protocol failure with the specified error message.
	 * @param message the detail message
	 */
	public A2aProtocolException(String message) {
		super(message);
	}

	/**
	 * Constructs a new protocol failure with the specified error message and cause.
	 * @param message the detail message
	 * @param cause the cause
	 */
	public A2aProtocolException(String message, Throwable cause) {
		super(message, cause);
	}

}
