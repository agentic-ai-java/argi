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
package io.github.agentic.ai.graph.agent.planner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import io.github.agentic.ai.graph.agent.Agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.chat.ToolDefinition;

import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.INPUT;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.OUTPUT;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.SEARCH;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.plan;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.step;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PlanValidatorTest {

	private final PlanValidator validator = PlannerFixtures.validator();

	@Test
	void serialParallelFanInAndDisconnectedGraphsAreValidEvenWhenUnordered() {
		assertDoesNotThrow(() -> this.validator.validateGenerated(plan(step("a"), step("b", "a"))));
		assertDoesNotThrow(() -> this.validator
			.validateGenerated(plan(step("merge", "a", "b"), step("b"), step("a"), step("independent"))));
		assertEquals(PlanValidator.DEFAULT_MAX_STEPS, this.validator.maxSteps());
		assertEquals(PlanValidator.DEFAULT_MAX_DEPTH, this.validator.maxDepth());
	}

	@Test
	void duplicateIdsMissingDependenciesAndAllCyclesAreRejected() {
		rejected(plan(step("same"), step("same")), "Duplicate stepId");
		rejected(plan(step("a", "absent")), "Missing dependency");
		rejected(plan(step("a", "a")), "cycle");
		rejected(plan(step("a", "b"), step("b", "a")), "cycle");
		rejected(plan(step("ok"), step("a", "b"), step("b", "a")), "cycle");
		assertThrows(PlanValidationException.class,
				() -> this.validator.validate(plan(step("a"), step("b", "a", "a"))));
	}

	@Test
	void countAndLongestPathAreBoundedAtInclusiveLimits() {
		PlanValidator limited = new PlanValidator(List.of(SEARCH), 4, 3);
		assertDoesNotThrow(() -> limited
			.validate(plan(step("merge", "short", "long"), step("long", "short"), step("short"), step("other"))));
		assertThrows(PlanValidationException.class,
				() -> limited.validate(plan(step("1"), step("2", "1"), step("3", "2"), step("4", "3"))));
		assertThrows(PlanValidationException.class,
				() -> limited.validate(plan(step("1"), step("2"), step("3"), step("4"), step("5"))));
		assertDoesNotThrow(() -> new PlanValidator(List.of(SEARCH), 1, 1).validate(plan(step("single"))));
	}

	@Test
	void wideAndDeepGraphsDoNotRecurseAndRejectBeyondLimits() {
		PlanValidator large = new PlanValidator(List.of(SEARCH), 2048, 2048);
		PlanStep[] chain = IntStream.range(0, 2048)
			.mapToObj(i -> i == 0 ? step("0") : step("" + i, "" + (i - 1)))
			.toArray(PlanStep[]::new);
		PlanStep[] wide = IntStream.range(0, 2048).mapToObj(i -> step("" + i)).toArray(PlanStep[]::new);
		assertTimeout(Duration.ofSeconds(10), () -> {
			large.validateGenerated(plan(chain));
			large.validateGenerated(plan(wide));
		});
		assertThrows(PlanValidationException.class, () -> this.validator.validate(plan(chain)));
	}

	@Test
	void targetsResolveExactlyAndContractChangesCannotExpandCapabilities() {
		PlanStep original = step("a");
		for (String target : List.of("tool:invented", "tool:search ", "TOOL:SEARCH", "agent:search")) {
			rejected(plan(copy(original, target, INPUT, OUTPUT, original.status(), null, null)),
					"Unregistered execution target");
		}
		rejected(plan(copy(original, "tool:search", "true", OUTPUT, original.status(), null, null)), "schemas differ");
		rejected(plan(copy(original, "tool:search", INPUT, "{}", original.status(), null, null)), "schemas differ");
		assertThrows(PlanValidationException.class, () -> this.validator
			.validate(plan(copy(original, "tool:search", "broken", OUTPUT, original.status(), null, null))));
		// Property order and whitespace are not authority changes.
		assertDoesNotThrow(() -> this.validator.validate(plan(
				copy(original, "tool:search", "{ \"type\" : \"object\" }", OUTPUT, original.status(), null, null))));
		assertEquals(SEARCH, this.validator.resolve(SEARCH.executionTarget()));
		assertThrows(PlanValidationException.class, () -> this.validator.resolve(null));
	}

	@Test
	void generatedStatusesResultsAndErrorsAreNotTrusted() {
		PlanStep original = step("a");
		for (PlanStep.Status status : PlanStep.Status.values()) {
			if (status != PlanStep.Status.PENDING) {
				assertThrows(PlanValidationException.class, () -> this.validator
					.validateGenerated(plan(copy(original, "tool:search", INPUT, OUTPUT, status, null, null))));
			}
		}
		assertThrows(PlanValidationException.class, () -> this.validator.validateGenerated(
				plan(copy(original, "tool:search", INPUT, OUTPUT, original.status(), "\"already done\"", null))));
		assertThrows(PlanValidationException.class, () -> this.validator.validateGenerated(
				plan(copy(original, "tool:search", INPUT, OUTPUT, original.status(), null, "already failed"))));
	}

	@Test
	void malformedAndBlankPlanFieldsAreRejected() {
		assertThrows(PlanValidationException.class, () -> this.validator.validate(null));
		assertThrows(PlanValidationException.class,
				() -> this.validator.validate(new PlanSpec(1, 1, "Objective", null)));
		assertThrows(PlanValidationException.class,
				() -> this.validator.validate(new PlanSpec(1, 1, "Objective", List.of())));
		assertThrows(PlanValidationException.class,
				() -> this.validator.validate(new PlanSpec(1, 1, " ", List.of(step("a")))));
		assertThrows(PlanValidationException.class,
				() -> this.validator.validate(new PlanSpec(1, 1, "Objective", Arrays.asList((PlanStep) null))));
		assertThrows(PlanValidationException.class, () -> this.validator.validate(plan(step(" "))));
		PlanStep valid = step("a");
		assertThrows(PlanValidationException.class, () -> this.validator.validate(plan(new PlanStep("a", " ", List.of(),
				"tool:search", INPUT, OUTPUT, "Criteria", valid.status(), null, null))));
		assertThrows(PlanValidationException.class, () -> this.validator.validate(plan(new PlanStep("a", "Objective",
				List.of(), "tool:search", INPUT, OUTPUT, " ", valid.status(), null, null))));
	}

	@Test
	void invalidRegistriesAndLimitsFailAtConstruction() {
		assertThrows(PlanValidationException.class, () -> new PlanValidator(null));
		assertThrows(PlanValidationException.class, () -> new PlanValidator(List.of()));
		assertThrows(PlanValidationException.class, () -> new PlanValidator(Arrays.asList((PlanCapability) null)));
		assertThrows(PlanValidationException.class, () -> new PlanValidator(List.of(SEARCH, SEARCH)));
		assertThrows(PlanValidationException.class, () -> new PlanValidator(List.of(SEARCH), 0, 1));
		assertThrows(PlanValidationException.class, () -> new PlanValidator(List.of(SEARCH), 1, 0));
		assertThrows(PlanValidationException.class,
				() -> new PlanValidator(List.of(new PlanCapability(" ", "Description", INPUT, OUTPUT))));
		assertThrows(PlanValidationException.class,
				() -> new PlanValidator(List.of(new PlanCapability("target", null, INPUT, OUTPUT))));
		List<PlanCapability> mutable = new ArrayList<>(List.of(SEARCH));
		PlanValidator snapshot = new PlanValidator(mutable);
		mutable.clear();
		assertEquals(List.of(SEARCH), List.copyOf(snapshot.capabilities()));
		assertThrows(UnsupportedOperationException.class, () -> snapshot.capabilities().clear());
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "null", "[]", "broken", "{\"type\":\"invented\"}", "{\"required\":\"query\"}",
			"{\"$ref\":\"https://example.invalid/schema\"}", "{\"$dynamicRef\":\"file:///schema.json\"}",
			"{\"$schema\":\"https://json-schema.org/draft-07/schema\"}", "{\"$ref\":\"#/$defs/missing\"}" })
	void invalidUnresolvedOrRemoteContractsFailAtRegistration(String schema) {
		assertThrows(PlanValidationException.class,
				() -> new PlanValidator(List.of(new PlanCapability("target", "Description", schema, OUTPUT))));
	}

	@Test
	void validLocalSchemaReferencesAndBooleanSchemasAreSupported() {
		String local = "{\"$defs\":{\"text\":{\"type\":\"string\"}},\"$ref\":\"#/$defs/text\"}";
		assertDoesNotThrow(
				() -> new PlanValidator(List.of(new PlanCapability("target", "Description", local, "false"))));
		assertDoesNotThrow(
				() -> new PlanValidator(List.of(new PlanCapability("target", "Description", "true", OUTPUT))));
		assertDoesNotThrow(() -> new PlanValidator(List.of(new PlanCapability("target", "Description",
				"{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"properties\":{\"values\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
				OUTPUT))));
	}

	@Test
	void literalReferenceKeysAndPropertyNamesAreNotSchemaReferences() {
		String schema = """
				{"type":"object","properties":{"$ref":{"type":"string"},"$schema":{"type":"string"}},
				 "const":{"$ref":"https://example.invalid/literal"},
				 "default":{"$ref":"https://example.invalid/default"},
				 "examples":[{"$ref":"https://example.invalid/example"}]}
				""";
		assertDoesNotThrow(
				() -> new PlanValidator(List.of(new PlanCapability("target", "Description", schema, OUTPUT))));
		for (String nested : List.of("{\"properties\":{\"query\":{\"$ref\":\"https://example.invalid/schema\"}}}",
				"{\"allOf\":[{\"$ref\":\"https://example.invalid/schema\"}]}",
				"{\"items\":{\"$ref\":\"https://example.invalid/schema\"}}")) {
			assertThrows(PlanValidationException.class,
					() -> new PlanValidator(List.of(new PlanCapability("target", "Description", nested, OUTPUT))));
		}
	}

	@Test
	void descriptorsComeFromRealRegisteredDefinitionsWithoutInvokingThem() {
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition("search", "Search documents", INPUT));
		Agent agent = mock(Agent.class);
		when(agent.name()).thenReturn("writer");
		when(agent.description()).thenReturn("Write a summary");
		assertEquals(SEARCH, PlanCapability.tool(tool, OUTPUT));
		assertEquals(PlannerFixtures.WRITER, PlanCapability.agent(agent, INPUT, OUTPUT));
		verify(tool).definition();
		verify(agent).name();
		verify(agent).description();
		verifyNoMoreInteractions(tool, agent);
		assertThrows(NullPointerException.class, () -> PlanCapability.tool(null, OUTPUT));
		assertThrows(NullPointerException.class, () -> PlanCapability.agent(null, INPUT, OUTPUT));
	}

	private void rejected(PlanSpec spec, String message) {
		assertTrue(assertThrows(PlanValidationException.class, () -> this.validator.validate(spec)).getMessage()
			.contains(message));
	}

	private static PlanStep copy(PlanStep original, String target, String input, String output, PlanStep.Status status,
			String result, String error) {
		return new PlanStep(original.stepId(), original.objective(), original.dependencies(), target, input, output,
				original.completionCriteria(), status, result, error);
	}

}
