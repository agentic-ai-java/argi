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
package io.github.agentic.ai.api.chat;

/** Token usage reported by a model. */
public record Usage(Long inputTokens, Long outputTokens, Long totalTokens) {

	public static Usage unknown() {
		return new Usage(null, null, null);
	}

	/**
	 * Returns the per-field sum of this usage and the given one: a field is the sum of
	 * the two known values, and a field that is unknown ({@code null}) on either side
	 * stays unknown instead of being treated as zero, so an unreported component can
	 * never turn into a fabricated exact total. A {@code null} other usage adds nothing
	 * known and returns this usage unchanged.
	 * @param other the usage to add, for example the segment reported by another call
	 * of the same delivery; may be {@code null}
	 */
	public Usage plus(Usage other) {
		if (other == null) {
			return this;
		}
		return new Usage(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens),
				sum(totalTokens, other.totalTokens));
	}

	private static Long sum(Long left, Long right) {
		return left == null || right == null ? null : left + right;
	}

}
