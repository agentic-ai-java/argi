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
 * The streaming subscriber stopped requesting events for longer than the configured demand
 * timeout. No internal queue buffers the remote output: the producer blocks on downstream
 * demand, so a stalled consumer applies backpressure to the remote peer instead of growing
 * memory without bound. The stream ends with this failure; text chunks already delivered
 * keep their identity and are not replayed.
 */
public class A2aBackpressureException extends A2aException {

	/**
	 * Constructs a new backpressure failure with the specified error message.
	 * @param message the detail message
	 */
	public A2aBackpressureException(String message) {
		super(message);
	}

}
