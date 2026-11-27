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
package io.github.agentic.ai.agent.retry;

/** Terminal decision of the retry controller, including the last attempted dispatch. */
public final class ModelRetryFailure extends RuntimeException {

	private final RetryStopReason reason;

	private final ModelAttempt attempt;

	public ModelRetryFailure(String message, Throwable cause, RetryStopReason reason, ModelAttempt attempt) {
		super(message, cause);
		this.reason = reason;
		this.attempt = attempt;
	}

	public RetryStopReason reason() {
		return reason;
	}

	public ModelAttempt attempt() {
		return attempt;
	}

}
