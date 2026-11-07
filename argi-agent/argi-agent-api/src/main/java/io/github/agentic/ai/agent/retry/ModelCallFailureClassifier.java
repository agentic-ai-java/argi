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
 * Classifies a failed model call into a {@link ModelCallFailureCategory}.
 *
 * <p>
 * Implementations must classify from the failure type and its cause chain. Matching
 * on the text of an exception message is not a classification: the same message can
 * describe a permanent rejection or a transient outage.
 */
@FunctionalInterface
public interface ModelCallFailureClassifier {

	/**
	 * Classifies a failure.
	 * @param failure the failure raised by the model call
	 * @return the classified failure, never {@code null}
	 */
	ModelCallFailure classify(Throwable failure);

}
