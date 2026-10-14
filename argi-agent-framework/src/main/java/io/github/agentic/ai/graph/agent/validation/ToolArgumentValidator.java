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
package io.github.agentic.ai.graph.agent.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

/**
 * Validates tool-call arguments against the tool's declared input schema before the
 * arguments reach the {@link io.github.agentic.ai.api.tool.Tool}.
 *
 * <p>
 * Deliberately a small, closed-rule validator rather than a full JSON Schema engine: JSON
 * parse, {@code required}, {@code type}, {@code enum}, numeric
 * {@code minimum}/{@code maximum} range, {@code additionalProperties: false} and
 * {@code null} type handling, plus resource limits on argument size, nesting depth and
 * collection cardinality. Remote references are never fetched: {@code $ref} values are
 * resolved locally within the schema document when they are {@code #/...} pointers, and
 * rejected explicitly otherwise. Recursion is bounded by the depth limit and a cycle
 * guard, so neither hostile arguments nor hostile schemas can recurse unboundedly.
 * </p>
 *
 * <p>
 * Failure messages and field paths are safe to surface: they identify the offending field
 * path and error code but never include argument values.
 * </p>
 */
public final class ToolArgumentValidator {

	/** Maximum accepted size of the raw arguments payload, in UTF-8 bytes. */
	static final int MAX_ARGUMENTS_BYTES = 64 * 1024;

	/** Maximum nesting depth traversed across arguments and schema. */
	static final int MAX_DEPTH = 64;

	/** Maximum number of entries in a validated object or array. */
	static final int MAX_COLLECTION_SIZE = 1_000;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ToolArgumentValidator() {
	}

	/**
	 * A validation failure with a machine-readable error code, the JSON-path-like field
	 * path of the offending value, and a value-free human-readable message.
	 *
	 * @param errorCode machine-readable error code (e.g. {@code INVALID_JSON})
	 * @param fieldPath path of the offending field, e.g. {@code $.user.name}
	 * @param message human-readable message that never contains argument values
	 */
	public record ToolArgumentValidationFailure(String errorCode, String fieldPath, String message) {
	}

	/**
	 * Validate the raw tool arguments against the tool's input schema.
	 * @param inputSchema the tool's declared JSON Schema (may be {@code null} or blank,
	 * which means "no declared constraints")
	 * @param arguments the raw JSON arguments string handed to the callback
	 * @return the first validation failure, or empty when the arguments are acceptable
	 */
	public static Optional<ToolArgumentValidationFailure> validate(String inputSchema, String arguments) {
		if (arguments != null && arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENTS_BYTES) {
			return Optional.of(new ToolArgumentValidationFailure("ARGUMENTS_TOO_LARGE", "$",
					"tool arguments exceed the maximum accepted size"));
		}

		JsonNode schema = null;
		if (inputSchema != null && !inputSchema.isBlank()) {
			try {
				schema = MAPPER.readTree(inputSchema);
			}
			catch (JsonProcessingException e) {
				return Optional.of(new ToolArgumentValidationFailure("SCHEMA_MALFORMED", "$",
						"tool input schema is not valid JSON"));
			}
			if (!schema.isObject()) {
				return Optional.of(new ToolArgumentValidationFailure("SCHEMA_MALFORMED", "$",
						"tool input schema is not an object"));
			}
		}

		JsonNode args;
		String trimmed = arguments == null ? "" : arguments.trim();
		if (trimmed.isEmpty()) {
			args = MAPPER.createObjectNode();
		}
		else {
			try {
				args = MAPPER.readTree(trimmed);
			}
			catch (JsonProcessingException e) {
				return Optional
					.of(new ToolArgumentValidationFailure("INVALID_JSON", "$", "tool arguments are not valid JSON"));
			}
		}

		if (!args.isObject()) {
			return Optional
				.of(new ToolArgumentValidationFailure("INVALID_TYPE", "$", "tool arguments must be a JSON object"));
		}

		if (schema == null) {
			return Optional.empty();
		}
		return validateValue(args, schema, "$", 1, schema, new ArrayDeque<>());
	}

	private static Optional<ToolArgumentValidationFailure> validateValue(JsonNode value, JsonNode schema, String path,
			int depth, JsonNode schemaRoot, Deque<String> activeRefs) {
		if (depth > MAX_DEPTH) {
			return Optional.of(new ToolArgumentValidationFailure("DEPTH_EXCEEDED", path,
					"nesting depth exceeds the configured limit"));
		}

		JsonNode refNode = schema.path("$ref");
		if (refNode.isTextual()) {
			String ref = refNode.asText();
			if (!ref.startsWith("#")) {
				return Optional.of(new ToolArgumentValidationFailure("REF_NOT_LOCAL", path,
						"only local $ref pointers are supported; remote references are never fetched"));
			}
			if (activeRefs.contains(ref)) {
				return Optional.of(new ToolArgumentValidationFailure("REF_CYCLE", path, "cyclic $ref detected"));
			}
			JsonNode target = resolvePointer(schemaRoot, ref);
			if (target == null || !target.isObject()) {
				return Optional.of(new ToolArgumentValidationFailure("REF_UNRESOLVED", path,
						"$ref does not resolve to a schema object within the document"));
			}
			activeRefs.push(ref);
			Optional<ToolArgumentValidationFailure> failure = validateValue(value, target, path, depth + 1, schemaRoot,
					activeRefs);
			activeRefs.pop();
			return failure;
		}

		JsonNode typeNode = schema.path("type");
		if (!typeNode.isMissingNode() && !matchesType(value, typeNode)) {
			return Optional
				.of(new ToolArgumentValidationFailure("INVALID_TYPE", path, "value does not match the declared type"));
		}

		JsonNode enumNode = schema.path("enum");
		if (enumNode.isArray()) {
			boolean allowed = false;
			for (JsonNode allowedValue : enumNode) {
				if (allowedValue.equals(value)) {
					allowed = true;
					break;
				}
			}
			if (!allowed) {
				return Optional.of(new ToolArgumentValidationFailure("ENUM_VIOLATION", path,
						"value is not one of the allowed enum values"));
			}
		}

		if (value.isNumber()) {
			JsonNode minimum = schema.path("minimum");
			if (minimum.isNumber() && value.decimalValue().compareTo(minimum.decimalValue()) < 0) {
				return Optional.of(new ToolArgumentValidationFailure("RANGE_VIOLATION", path,
						"value is below the allowed minimum"));
			}
			JsonNode maximum = schema.path("maximum");
			if (maximum.isNumber() && value.decimalValue().compareTo(maximum.decimalValue()) > 0) {
				return Optional.of(new ToolArgumentValidationFailure("RANGE_VIOLATION", path,
						"value exceeds the allowed maximum"));
			}
		}

		if (value.isObject()) {
			return validateObjectFields(value, schema, path, depth, schemaRoot, activeRefs);
		}

		if (value.isArray() && schema.path("items").isObject()) {
			if (value.size() > MAX_COLLECTION_SIZE) {
				return Optional.of(new ToolArgumentValidationFailure("COLLECTION_TOO_LARGE", path,
						"array exceeds the maximum number of items"));
			}
			for (int i = 0; i < value.size(); i++) {
				Optional<ToolArgumentValidationFailure> failure = validateValue(value.get(i), schema.path("items"),
						path + "[" + i + "]", depth + 1, schemaRoot, activeRefs);
				if (failure.isPresent()) {
					return failure;
				}
			}
		}
		return Optional.empty();
	}

	private static Optional<ToolArgumentValidationFailure> validateObjectFields(JsonNode value, JsonNode schema,
			String path, int depth, JsonNode schemaRoot, Deque<String> activeRefs) {
		if (value.size() > MAX_COLLECTION_SIZE) {
			return Optional.of(new ToolArgumentValidationFailure("COLLECTION_TOO_LARGE", path,
					"object exceeds the maximum number of properties"));
		}

		JsonNode required = schema.path("required");
		if (required.isArray()) {
			for (JsonNode requirement : required) {
				String key = requirement.asText();
				if (!value.has(key)) {
					return Optional.of(new ToolArgumentValidationFailure("REQUIRED_MISSING", childPath(path, key),
							"required property is missing"));
				}
			}
		}

		JsonNode properties = schema.path("properties");
		Iterator<String> names = value.fieldNames();
		while (names.hasNext()) {
			String key = names.next();
			JsonNode propertySchema = properties.isObject() ? properties.get(key) : null;
			String childPath = childPath(path, key);
			if (propertySchema == null || !propertySchema.isObject()) {
				if (isProhibited(schema.path("additionalProperties"))) {
					return Optional.of(new ToolArgumentValidationFailure("ADDITIONAL_PROPERTY", childPath,
							"property is not declared in the schema"));
				}
				continue;
			}
			Optional<ToolArgumentValidationFailure> failure = validateValue(value.get(key), propertySchema, childPath,
					depth + 1, schemaRoot, activeRefs);
			if (failure.isPresent()) {
				return failure;
			}
		}
		return Optional.empty();
	}

	private static JsonNode resolvePointer(JsonNode schemaRoot, String ref) {
		if ("#".equals(ref)) {
			return schemaRoot;
		}
		if (!ref.startsWith("#/")) {
			return null;
		}
		JsonNode current = schemaRoot;
		for (String rawToken : ref.substring(2).split("/")) {
			String token = rawToken.replace("~1", "/").replace("~0", "~");
			if (current.isArray()) {
				int index;
				try {
					index = Integer.parseInt(token);
				}
				catch (NumberFormatException e) {
					return null;
				}
				current = index >= 0 && index < current.size() ? current.get(index) : null;
			}
			else {
				current = current.path(token);
			}
			if (current == null || current.isMissingNode()) {
				return null;
			}
		}
		return current;
	}

	private static boolean matchesType(JsonNode value, JsonNode typeNode) {
		if (typeNode.isArray()) {
			for (JsonNode candidate : typeNode) {
				if (matchesSingleType(value, candidate)) {
					return true;
				}
			}
			return false;
		}
		return matchesSingleType(value, typeNode);
	}

	private static boolean matchesSingleType(JsonNode value, JsonNode typeNode) {
		return switch (typeNode.asText()) {
			case "string" -> value.isTextual();
			case "number" -> value.isNumber();
			case "integer" -> value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
			case "boolean" -> value.isBoolean();
			case "object" -> value.isObject();
			case "array" -> value.isArray();
			case "null" -> value.isNull();
			default -> true;
		};
	}

	private static boolean isProhibited(JsonNode additionalProperties) {
		return additionalProperties.isBoolean() && !additionalProperties.asBoolean();
	}

	private static String childPath(String path, String key) {
		return path + "." + key;
	}

}
