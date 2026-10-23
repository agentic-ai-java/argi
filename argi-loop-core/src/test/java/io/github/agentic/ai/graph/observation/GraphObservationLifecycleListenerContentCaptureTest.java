/*
 * Copyright 2024-2026 the original author or authors.
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

package io.github.agentic.ai.graph.observation;

import io.github.agentic.ai.graph.GraphLifecycleListener;
import io.github.agentic.ai.graph.observation.metric.ArgiObservationMetricAttributes;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static org.assertj.core.api.Assertions.assertThat;

class GraphObservationLifecycleListenerContentCaptureTest {

	@Test
	void shouldNotCaptureRawStateByDefault() {
		CapturingObservationHandler handler = new CapturingObservationHandler();
		ObservationRegistry registry = registry(handler);
		GraphObservationLifecycleListener listener = new GraphObservationLifecycleListener(registry);

		Map<String, Object> state = Map.of(GraphLifecycleListener.EXECUTION_ID_KEY, "execution-default", "input",
				"secret prompt", "apiToken", "secret-token");

		listener.onStart(START, state, null);
		listener.onComplete(END, state, null);

		assertThat(handler.highCardinalityValues(ArgiObservationMetricAttributes.LANGFUSE_INPUT.value()))
			.isEmpty();
		assertThat(handler.highCardinalityValues(ArgiObservationMetricAttributes.GEN_AI_PROMPT.value()))
			.isEmpty();
		assertThat(handler.allHighCardinalityValues()).doesNotContain("secret prompt", "secret-token");
	}

	@Test
	void shouldCaptureSanitizedStateWhenExplicitlyEnabled() {
		CapturingObservationHandler handler = new CapturingObservationHandler();
		ObservationRegistry registry = registry(handler);
		GraphObservationLifecycleListener listener = new GraphObservationLifecycleListener(registry, true, 24);

		Map<String, Object> state = Map.of(GraphLifecycleListener.EXECUTION_ID_KEY, "execution-enabled", "input",
				"abcdefghijklmnopqrstuvwxyz", "apiToken", "secret-token");

		listener.onStart(START, state, null);
		listener.onComplete(END, state, null);

		assertThat(handler.highCardinalityValues(ArgiObservationMetricAttributes.LANGFUSE_INPUT.value()))
			.anySatisfy(value -> {
				assertThat(value).contains("abcdefghijklmnopqrstuvwx");
				assertThat(value).contains("apiToken=<redacted>");
				assertThat(value).doesNotContain("secret-token");
				assertThat(value).doesNotContain("abcdefghijklmnopqrstuvwxyz");
			});
	}

	@Test
	void shouldRedactCompleteEmbeddedSensitiveValuesContainingWhitespace() {
		String sanitized = ObservationContentSanitizer.sanitizeText(
				"password=my secret value; {\"apiKey\":\"abc def ghi\"}; authorization=Bearer token suffix", 500);

		assertThat(sanitized).doesNotContain("my secret value", "abc def ghi", "token suffix")
			.contains("password=<redacted>", "\"apiKey\":\"<redacted>\"", "authorization=<redacted>");
	}

	private ObservationRegistry registry(CapturingObservationHandler handler) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(handler);
		return registry;
	}

	static class CapturingObservationHandler implements ObservationHandler<Observation.Context> {

		private final List<Observation.Context> stopped = new ArrayList<>();

		@Override
		public void onStop(Observation.Context context) {
			stopped.add(context);
		}

		@Override
		public boolean supportsContext(Observation.Context context) {
			return true;
		}

		List<String> highCardinalityValues(String key) {
			return stopped.stream()
				.flatMap(context -> context.getHighCardinalityKeyValues().stream())
				.filter(keyValue -> keyValue.getKey().equals(key))
				.map(KeyValue::getValue)
				.toList();
		}

		String allHighCardinalityValues() {
			return stopped.stream()
				.flatMap(context -> context.getHighCardinalityKeyValues().stream())
				.map(KeyValue::getValue)
				.reduce("", (left, right) -> left + right);
		}

	}

}
