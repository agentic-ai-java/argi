/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromptTemplateTest {

	@Test
	void rendersVariablesWithoutReinterpretingTheirContents() {
		assertEquals("Hello {name}\nready", PromptTemplate.of("Hello {name}\n{status}")
			.render(Map.of("name", "{name}", "status", "ready", "unused", "value")));
	}

	@Test
	void supportsCustomAndMatchingDelimiters() {
		assertEquals("Hello Ada; {literal}",
				PromptTemplate.of("Hello <name>; {literal}", '<', '>').render(Map.of("name", "Ada")));
		assertEquals("Hello Ada", PromptTemplate.of("Hello $name$", '$', '$').render(Map.of("name", "Ada")));
	}

	@Test
	void preservesEscapedLiteralBraces() {
		assertEquals("Result: {\"value\":1}; Ada",
				PromptTemplate.of("Result: \\{\"value\":1\\}; {name}").render(Map.of("name", "Ada")));
	}

	@Test
	void supportsListsAndStringTemplateControlFlow() {
		assertEquals("a, b", PromptTemplate.of("{if(items)}{items; separator=\", \"}{else}none{endif}")
			.render(Map.of("items", List.of("a", "b"))));
	}

	@Test
	void allowsExplicitNullValuesAndEmptyTemplates() {
		Map<String, Object> variables = new HashMap<>();
		variables.put("value", null);
		assertEquals("[]", PromptTemplate.of("[{value}]").render(variables));
		assertEquals("", PromptTemplate.of("").render(Map.of()));
	}

	@Test
	void rejectsMissingVariablesAndInvalidSyntax() {
		assertThrows(IllegalArgumentException.class, () -> PromptTemplate.of("{missing}").render(Map.of()));
		assertThrows(IllegalArgumentException.class, () -> PromptTemplate.of("{if(name)}").render(Map.of("name", "x")));
	}

	@Test
	void rejectsNullTemplateAndContext() {
		assertThrows(NullPointerException.class, () -> PromptTemplate.of(null));
		assertThrows(NullPointerException.class, () -> PromptTemplate.of("text").render(null));
	}

	@Test
	void concurrentRendersDoNotShareAttributes() {
		PromptTemplate template = PromptTemplate.of("{name}:{name}");
		List<CompletableFuture<String>> renders = IntStream.range(0, 100)
			.mapToObj(index -> CompletableFuture.supplyAsync(() -> template.render(Map.of("name", index))))
			.toList();
		for (int index = 0; index < renders.size(); index++) {
			assertEquals(index + ":" + index, renders.get(index).join());
		}
	}

}
