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

/** One numbered dispatch in a logical model call. The first dispatch counts as one.
 * Immutable so independent subscriptions can resume at the same position.
 */
public record ModelAttempt(int number, int limit) {

	public ModelAttempt {
		if (number < 1 || number > limit) {
			throw new IllegalArgumentException("attempt must be between one and the total limit");
		}
	}

	public static ModelAttempt first() {
		return new ModelAttempt(1, Integer.MAX_VALUE);
	}

	public ModelAttempt boundedBy(int maximum) {
		return new ModelAttempt(number, Math.min(limit, maximum));
	}

	public boolean exhausted() {
		return number == limit;
	}

	public ModelAttempt next() {
		return new ModelAttempt(Math.addExact(number, 1), limit);
	}

}
