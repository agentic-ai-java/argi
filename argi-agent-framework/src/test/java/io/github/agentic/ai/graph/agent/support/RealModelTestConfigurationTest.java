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
package io.github.agentic.ai.graph.agent.support;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealModelTestConfigurationTest {

	@Test
	void requiresBothNonblankVariables() {
		for (Map<String, String> environment : java.util.List.of(Map.<String, String>of(),
				Map.of("ARGI_MODEL", "model"), Map.of("ARGI_API_KEY", "test-key"),
				Map.of("ARGI_MODEL", " ", "ARGI_API_KEY", "test-key"),
				Map.of("ARGI_MODEL", "model", "ARGI_API_KEY", "\t"))) {
			assertThrows(TestAbortedException.class, () -> OpenAiCompatibleTestModels.options(environment));
		}
	}

	@Test
	void usesOnlyExplicitModelAndCredentialWithOptionalEndpoint() {
		var defaults = OpenAiCompatibleTestModels
			.options(Map.of("ARGI_MODEL", "test-model", "ARGI_API_KEY", "test-key"))
			.build();
		assertEquals("test-model", defaults.getModel());
		assertEquals("test-key", defaults.getApiKey());
		assertEquals("https://api.openai.com/v1", defaults.getBaseUrl());
		var custom = OpenAiCompatibleTestModels
			.options(Map.of("ARGI_MODEL", "test-model", "ARGI_API_KEY", "test-key", "ARGI_BASE_URL",
					"http://localhost:9999/v1"))
			.build();
		assertEquals("http://localhost:9999/v1", custom.getBaseUrl());
	}

	@Test
	void annotationAppliesTheSameDualCondition() {
		var conditions = EnabledForRealModel.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class);
		assertEquals(2, conditions.length);
		assertEquals(java.util.Set.of("ARGI_MODEL", "ARGI_API_KEY"),
				java.util.Arrays.stream(conditions)
					.map(EnabledIfEnvironmentVariable::named)
					.collect(java.util.stream.Collectors.toSet()));
		for (var condition : conditions) {
			assertTrue("value".matches(condition.matches()));
			assertTrue(!" \t".matches(condition.matches()));
		}
	}

}
