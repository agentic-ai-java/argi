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

/**
 * Why a retry sequence stopped instead of starting another attempt.
 */
public enum RetryStopReason {

	/** The configured number of attempts was used up. */
	ATTEMPTS_EXHAUSTED,

	/** The failure is not retryable under the active policy. */
	NON_RETRYABLE,

	/** The run deadline does not allow another attempt. */
	DEADLINE_EXCEEDED,

	/** The attempt was cancelled. */
	CANCELLED,

	/** The execution guard stopped the run. */
	EXECUTION_STOPPED

}
