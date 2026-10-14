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

import java.util.List;
import java.util.Map;

/** Model generation options that can be represented across supported adapters. */
public record GenerationOptions(Double temperature, Double topP, Integer maxTokens, List<String> stopSequences,
		ToolChoice toolChoice, ResponseFormat responseFormat, Map<String, Object> metadata) {

	public GenerationOptions {
		stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
		toolChoice = toolChoice == null ? ToolChoice.auto() : toolChoice;
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		if (temperature != null && (!Double.isFinite(temperature) || temperature < 0)) {
			throw new IllegalArgumentException("temperature must be finite and non-negative");
		}
		if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
			throw new IllegalArgumentException("topP must be between 0 and 1");
		}
		if (maxTokens != null && maxTokens <= 0) {
			throw new IllegalArgumentException("maxTokens must be positive");
		}
	}

	public static GenerationOptions defaults() {
		return new GenerationOptions(null, null, null, List.of(), ToolChoice.auto(), ResponseFormat.text(), Map.of());
	}

	public static Builder builder() {
		return new Builder();
	}

	public Builder mutate() {
		return new Builder().temperature(temperature)
			.topP(topP)
			.maxTokens(maxTokens)
			.stopSequences(stopSequences)
			.toolChoice(toolChoice)
			.responseFormat(responseFormat)
			.metadata(metadata);
	}

	/** Builds immutable, provider-neutral generation options. */
	public static final class Builder {

		private Double temperature;

		private Double topP;

		private Integer maxTokens;

		private List<String> stopSequences = List.of();

		private ToolChoice toolChoice = ToolChoice.auto();

		private ResponseFormat responseFormat = ResponseFormat.text();

		private Map<String, Object> metadata = Map.of();

		public Builder temperature(Double value) {
			temperature = value;
			return this;
		}

		public Builder topP(Double value) {
			topP = value;
			return this;
		}

		public Builder maxTokens(Integer value) {
			maxTokens = value;
			return this;
		}

		public Builder stopSequences(List<String> value) {
			stopSequences = value == null ? List.of() : List.copyOf(value);
			return this;
		}

		public Builder toolChoice(ToolChoice value) {
			toolChoice = value;
			return this;
		}

		public Builder responseFormat(ResponseFormat value) {
			responseFormat = value;
			return this;
		}

		public Builder metadata(Map<String, Object> value) {
			metadata = value == null ? Map.of() : Map.copyOf(value);
			return this;
		}

		public GenerationOptions build() {
			return new GenerationOptions(temperature, topP, maxTokens, stopSequences, toolChoice, responseFormat,
					metadata);
		}

	}
}
