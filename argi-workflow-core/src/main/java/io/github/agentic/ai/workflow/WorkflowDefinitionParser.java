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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the versioned JSON workflow DSL without evaluating arbitrary code. */
public final class WorkflowDefinitionParser {

	private final ObjectMapper mapper;

	public WorkflowDefinitionParser() {
		this(new ObjectMapper());
	}

	public WorkflowDefinitionParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public WorkflowDefinition parse(String json) {
		try {
			return parse(mapper.readTree(json));
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("Invalid workflow definition", exception);
		}
	}

	public WorkflowDefinition parse(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw new IllegalArgumentException("workflow definition must be an object");
		}
		List<WorkflowStep> steps = new ArrayList<>();
		root.withArray("steps").forEach(node -> steps.add(step(node)));
		Map<String, Object> metadata = root.has("metadata")
				? mapper.convertValue(root.get("metadata"), Map.class) : Map.of();
		WorkflowDefinition definition = new WorkflowDefinition(text(root, "id"), text(root, "version"), steps,
				optionalText(root, "inputSchema"), optionalText(root, "outputSchema"), metadata);
		definition.validate();
		return definition;
	}

	private WorkflowStep step(JsonNode node) {
		String type = text(node, "type");
		String id = text(node, "id");
		return switch (type) {
			case "agent" -> new WorkflowStep.Agent(id, text(node, "agentRef"), values(node, "inputs"),
					outputKey(node, id), retry(node));
			case "tool" -> new WorkflowStep.Tool(id, text(node, "toolName"), values(node, "arguments"),
					outputKey(node, id), retry(node));
			case "sequence" -> new WorkflowStep.Sequence(id, children(node, "steps"));
			case "condition" -> new WorkflowStep.Condition(id, value(node.get("condition")), step(node.get("then")),
					node.has("else") ? step(node.get("else")) : null);
			case "parallel" -> new WorkflowStep.Parallel(id, children(node, "branches"),
					node.path("maxConcurrency").asInt(node.withArray("branches").size()));
			case "loop" -> new WorkflowStep.Loop(id, value(node.get("condition")), step(node.get("body")),
				node.path("maxIterations").asInt(1));
			case "subflow" -> new WorkflowStep.Subflow(id, text(node, "workflowId"), text(node, "version"));
			default -> throw new IllegalArgumentException("Unsupported workflow step type: " + type);
		};
	}

	private List<WorkflowStep> children(JsonNode node, String field) {
		if (!node.has(field) || !node.get(field).isArray()) {
			throw new IllegalArgumentException(field + " must be an array");
		}
		List<WorkflowStep> children = new ArrayList<>();
		node.get(field).forEach(child -> children.add(step(child)));
		return children;
	}

	private Map<String, WorkflowValue> values(JsonNode node, String field) {
		JsonNode values = node.get(field);
		if (values == null || !values.isObject()) {
			return Map.of();
		}
		Map<String, WorkflowValue> result = new LinkedHashMap<>();
		Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> fieldValue = fields.next();
			result.put(fieldValue.getKey(), value(fieldValue.getValue()));
		}
		return result;
	}

	private WorkflowValue value(JsonNode node) {
		if (node == null || node.isNull()) {
			return new WorkflowValue.Literal(null);
		}
		if (!node.isObject() || !node.has("type")) {
			return new WorkflowValue.Literal(mapper.convertValue(node, Object.class));
		}
		return switch (text(node, "type")) {
			case "literal" -> new WorkflowValue.Literal(mapper.convertValue(node.get("value"), Object.class));
			case "input" -> new WorkflowValue.Input(text(node, "path"));
			case "state" -> new WorkflowValue.State(text(node, "path"));
			case "step" -> new WorkflowValue.StepOutput(text(node, "stepId"), text(node, "path"));
			case "all" -> new WorkflowValue.All(valuesList(node, "values"));
			case "any" -> new WorkflowValue.Any(valuesList(node, "values"));
			case "not" -> new WorkflowValue.Not(value(node.get("value")));
			case "compare" -> new WorkflowValue.Compare(WorkflowValue.Operator.valueOf(text(node, "operator")),
					value(node.get("left")), value(node.get("right")));
			default -> throw new IllegalArgumentException("Unsupported workflow expression type: " + text(node, "type"));
		};
	}

	private List<WorkflowValue> valuesList(JsonNode node, String field) {
		if (!node.has(field) || !node.get(field).isArray()) {
			throw new IllegalArgumentException(field + " must be an array");
		}
		List<WorkflowValue> result = new ArrayList<>();
		node.get(field).forEach(item -> result.add(value(item)));
		return result;
	}

	private WorkflowStep.RetryPolicy retry(JsonNode node) {
		JsonNode retry = node.get("retry");
		if (retry == null || retry.isNull()) {
			return WorkflowStep.RetryPolicy.none();
		}
		return new WorkflowStep.RetryPolicy(retry.path("maxAttempts").asInt(1),
				Duration.ofMillis(retry.path("backoffMillis").asLong(0)));
	}

	private static String text(JsonNode node, String field) {
		String value = optionalText(node, field);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must be a non-blank string");
		}
		return value;
	}

	private static String optionalText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static String outputKey(JsonNode node, String defaultValue) {
		String value = optionalText(node, "outputKey");
		return value == null ? defaultValue : value;
	}

}
