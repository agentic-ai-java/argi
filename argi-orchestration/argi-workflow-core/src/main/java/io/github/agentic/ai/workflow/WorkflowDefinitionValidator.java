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
package io.github.agentic.ai.workflow;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Structural validation performed before a definition can be published.
 *
 * <p>
 * Declared input and output schemas are checked for syntax against the supported
 * JSON Schema dialect (draft 2020-12, the same validator the agent layer uses for
 * structured outputs). A definition that declares no schema is unaffected; a blank
 * schema is still rejected.
 */
public final class WorkflowDefinitionValidator {

	private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

	private static final Schema META_SCHEMA = SCHEMAS
		.getSchema(SchemaLocation.of(SpecificationVersion.DRAFT_2020_12.getDialectId()));

	private WorkflowDefinitionValidator() {
	}

	public static void validate(WorkflowDefinition definition) {
		validateSchema("inputSchema", definition.inputSchema());
		validateSchema("outputSchema", definition.outputSchema());
		Set<String> ids = new HashSet<>();
		definition.steps().forEach(step -> validateStep(step, ids));
	}

	/**
	 * Detects cycles among the subflow references that the registry can already
	 * resolve, so a self-referencing or mutually referencing set of definitions is
	 * rejected at publish time instead of exhausting the JVM stack at run time
	 * (issue #273). The resolver maps an {@code id@version} reference to an already
	 * published definition; unresolvable references are legal forward references and
	 * are skipped here — the run keeps its own call chain as the second line of
	 * defense.
	 */
	public static void detectSubflowCycles(WorkflowDefinition definition,
			Function<String, Optional<WorkflowDefinition>> resolver) {
		String root = definition.id() + "@" + definition.version();
		List<String> chain = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		chain.add(root);
		visit(definition, resolver, chain, visited);
	}

	private static void visit(WorkflowDefinition definition, Function<String, Optional<WorkflowDefinition>> resolver,
			List<String> chain, Set<String> visited) {
		Set<String> references = new LinkedHashSet<>();
		collectSubflowReferences(definition.steps(), references);
		for (String reference : references) {
			int cut = chain.indexOf(reference);
			if (cut >= 0) {
				throw new IllegalArgumentException("workflow subflow reference closes a cycle: " + String.join(" -> ",
						chain.subList(cut, chain.size())) + " -> " + reference);
			}
			if (!visited.add(reference)) {
				continue;
			}
			Optional<WorkflowDefinition> resolved = resolver.apply(reference);
			if (resolved.isEmpty()) {
				continue;
			}
			chain.add(reference);
			visit(resolved.get(), resolver, chain, visited);
			chain.remove(chain.size() - 1);
		}
	}

	private static void collectSubflowReferences(List<WorkflowStep> steps, Set<String> references) {
		for (WorkflowStep step : steps) {
			collectSubflowReferences(step, references);
		}
	}

	private static void collectSubflowReferences(WorkflowStep step, Set<String> references) {
		if (step instanceof WorkflowStep.Subflow subflow) {
			references.add(subflow.workflowId() + "@" + subflow.version());
		}
		else if (step instanceof WorkflowStep.Sequence sequence) {
			collectSubflowReferences(sequence.steps(), references);
		}
		else if (step instanceof WorkflowStep.Condition condition) {
			collectSubflowReferences(condition.whenTrue(), references);
			if (condition.whenFalse() != null) {
				collectSubflowReferences(condition.whenFalse(), references);
			}
		}
		else if (step instanceof WorkflowStep.Parallel parallel) {
			parallel.branches().forEach(branch -> collectSubflowReferences(branch, references));
		}
		else if (step instanceof WorkflowStep.Loop loop) {
			collectSubflowReferences(loop.body(), references);
		}
	}

	private static void validateSchema(String field, String schema) {
		if (schema == null) {
			return;
		}
		if (schema.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		// Compiling the document is not enough: a non-object document or an invalid
		// keyword value would be accepted here and silently ignored at run time.
		List<Error> errors;
		try {
			errors = META_SCHEMA.validate(schema, InputFormat.JSON);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException(field + " is not valid JSON: " + exception.getMessage(), exception);
		}
		if (!errors.isEmpty()) {
			throw new IllegalArgumentException(
					field + " is not a valid JSON Schema (draft 2020-12): " + errors.get(0).getMessage());
		}
		try {
			SCHEMAS.getSchema(schema);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException(
					field + " is not a valid JSON Schema (draft 2020-12): " + exception.getMessage(), exception);
		}
	}

	private static void validateStep(WorkflowStep step, Set<String> ids) {
		if (!ids.add(step.id())) {
			throw new IllegalArgumentException("duplicate workflow step id: " + step.id());
		}
		if (step instanceof WorkflowStep.Sequence sequence) {
			sequence.steps().forEach(child -> validateStep(child, ids));
		}
		else if (step instanceof WorkflowStep.Condition condition) {
			validateStep(condition.whenTrue(), ids);
			if (condition.whenFalse() != null) {
				validateStep(condition.whenFalse(), ids);
			}
		}
		else if (step instanceof WorkflowStep.Parallel parallel) {
			parallel.branches().forEach(child -> validateStep(child, ids));
		}
		else if (step instanceof WorkflowStep.Loop loop) {
			validateStep(loop.body(), ids);
		}
	}

}
