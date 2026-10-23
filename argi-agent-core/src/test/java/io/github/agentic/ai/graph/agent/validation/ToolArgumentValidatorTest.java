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

import io.github.agentic.ai.graph.agent.validation.ToolArgumentValidator.ToolArgumentValidationFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentValidatorTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "name": { "type": "string" },
			    "level": { "type": "integer", "minimum": 1, "maximum": 5 },
			    "mode": { "enum": ["fast", "slow"] },
			    "tags": { "type": "array", "items": { "type": "string" } }
			  },
			  "required": ["name"],
			  "additionalProperties": false
			}""";

	@Test
	@DisplayName("rejects arguments that are not valid JSON")
	void invalidJsonIsRejected() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "{\"name\": ");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_JSON", failure.get().errorCode());
		assertEquals("$", failure.get().fieldPath());
	}

	@Test
	@DisplayName("rejects non-object arguments")
	void nonObjectArgumentsAreRejected() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "[1,2,3]");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
	}

	@Test
	@DisplayName("missing required property fails with its field path")
	void missingRequiredPropertyFails() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "{\"level\": 3}");
		assertTrue(failure.isPresent());
		assertEquals("REQUIRED_MISSING", failure.get().errorCode());
		assertEquals("$.name", failure.get().fieldPath());
	}

	@Test
	@DisplayName("type violation fails with the property path")
	void typeViolationFails() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": 42}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
		assertEquals("$.name", failure.get().fieldPath());
	}

	@Test
	@DisplayName("integer type accepts integral numbers and rejects fractional ones")
	void integerTypeIsEnforced() {
		assertTrue(ToolArgumentValidator.validate(SCHEMA, "{\"name\": \"x\", \"level\": 5}").isEmpty());
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"level\": 2.5}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
		assertEquals("$.level", failure.get().fieldPath());
	}

	@Test
	@DisplayName("enum violation fails without echoing the value")
	void enumViolationFails() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"mode\": \"medium\"}");
		assertTrue(failure.isPresent());
		assertEquals("ENUM_VIOLATION", failure.get().errorCode());
		assertFalse(failure.get().message().contains("medium"), "message must not echo argument values");
	}

	@Test
	@DisplayName("range violations fail below minimum and above maximum")
	void rangeViolationsFail() {
		Optional<ToolArgumentValidationFailure> below = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"level\": 0}");
		assertTrue(below.isPresent());
		assertEquals("RANGE_VIOLATION", below.get().errorCode());

		Optional<ToolArgumentValidationFailure> above = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"level\": 6}");
		assertTrue(above.isPresent());
		assertEquals("RANGE_VIOLATION", above.get().errorCode());
	}

	@Test
	@DisplayName("undeclared property fails when additionalProperties is false")
	void additionalPropertyFailsWhenProhibited() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"secret\": \"value\"}");
		assertTrue(failure.isPresent());
		assertEquals("ADDITIONAL_PROPERTY", failure.get().errorCode());
		assertEquals("$.secret", failure.get().fieldPath());
		assertFalse(failure.get().message().contains("value"), "message must not echo argument values");
	}

	@Test
	@DisplayName("null is rejected for a typed property but accepted for nullable types")
	void nullHandlingFollowsDeclaredTypes() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": null}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());

		String nullableSchema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":[\"string\",\"null\"]}}}";
		assertTrue(ToolArgumentValidator.validate(nullableSchema, "{\"name\": null}").isEmpty());
	}

	@Test
	@DisplayName("nested violations report the nested path")
	void nestedPathsAreReported() {
		String nestedSchema = """
				{"type":"object","properties":{"user":{"type":"object","properties":{
				  "age":{"type":"integer"}},"required":["age"]}}}""";
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(nestedSchema,
				"{\"user\": {}}");
		assertTrue(failure.isPresent());
		assertEquals("$.user.age", failure.get().fieldPath());
	}

	@Test
	@DisplayName("array item violations report the indexed path")
	void arrayItemPathsAreReported() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"tags\": [\"ok\", 7]}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
		assertEquals("$.tags[1]", failure.get().fieldPath());
	}

	@Test
	@DisplayName("an unresolvable $ref fails explicitly instead of being skipped")
	void unresolvableRefIsNotSilentlySkipped() {
		String refSchema = "{\"type\":\"object\",\"properties\":{\"name\":{\"$ref\":\"#/definitions/name\"}}}";
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(refSchema, "{\"name\": 1}");
		assertTrue(failure.isPresent());
		assertEquals("REF_UNRESOLVED", failure.get().errorCode());
	}

	@Test
	@DisplayName("malformed schema fails explicitly")
	void malformedSchemaFailsClosed() {
		Optional<ToolArgumentValidationFailure> broken = ToolArgumentValidator.validate("{not json", "{}");
		assertTrue(broken.isPresent());
		assertEquals("SCHEMA_MALFORMED", broken.get().errorCode());

		Optional<ToolArgumentValidationFailure> notAnObject = ToolArgumentValidator.validate("[1]", "{}");
		assertTrue(notAnObject.isPresent());
		assertEquals("SCHEMA_MALFORMED", notAnObject.get().errorCode());
	}

	@Test
	@DisplayName("missing or blank schema means no constraints")
	void absentSchemaPasses() {
		assertTrue(ToolArgumentValidator.validate(null, "{\"anything\": 1}").isEmpty());
		assertTrue(ToolArgumentValidator.validate("  ", "{\"anything\": 1}").isEmpty());
	}

	@Test
	@DisplayName("blank arguments become an empty object so required rules still apply")
	void blankArgumentsTreatedAsEmptyObject() {
		assertTrue(ToolArgumentValidator.validate("{}", "").isEmpty());
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "");
		assertTrue(failure.isPresent());
		assertEquals("REQUIRED_MISSING", failure.get().errorCode());
	}

	@Test
	@DisplayName("a fully valid call passes all rules")
	void validCallPasses() {
		assertTrue(ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"agent\", \"level\": 3, \"mode\": \"fast\", \"tags\": [\"a\", \"b\"]}").isEmpty());
	}

	@Test
	@DisplayName("arguments above the byte limit are rejected before parsing")
	void oversizeArgumentsAreRejected() {
		String oversized = "{\"name\": \"" + "a".repeat(ToolArgumentValidator.MAX_ARGUMENTS_BYTES) + "\"}";
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, oversized);
		assertTrue(failure.isPresent());
		assertEquals("ARGUMENTS_TOO_LARGE", failure.get().errorCode());
		assertFalse(failure.get().message().contains("a".repeat(100)), "message must not echo argument values");
	}

	@Test
	@DisplayName("nesting depth above the limit is rejected instead of recursing unboundedly")
	void excessiveDepthIsRejected() {
		int levels = ToolArgumentValidator.MAX_DEPTH + 10;
		StringBuilder schema = new StringBuilder("{\"type\":\"object\",\"properties\":{\"child\":");
		StringBuilder args = new StringBuilder();
		for (int i = 0; i < levels; i++) {
			schema.append("{\"type\":\"object\",\"properties\":{\"child\":");
			args.append("{\"child\":");
		}
		schema.append("{\"type\":\"string\"}}".repeat(1));
		args.append("\"leaf\"");
		for (int i = 0; i < levels; i++) {
			schema.append("}}");
			args.append("}");
		}
		schema.append("}}");

		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(schema.toString(),
				args.toString());
		assertTrue(failure.isPresent());
		assertEquals("DEPTH_EXCEEDED", failure.get().errorCode());
	}

	@Test
	@DisplayName("arrays above the collection limit are rejected")
	void oversizedArrayIsRejected() {
		String arraySchema = "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";
		StringBuilder args = new StringBuilder("{\"tags\": [");
		for (int i = 0; i <= ToolArgumentValidator.MAX_COLLECTION_SIZE; i++) {
			args.append(i > 0 ? "," : "").append("\"v\"");
		}
		args.append("]}");

		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(arraySchema, args.toString());
		assertTrue(failure.isPresent());
		assertEquals("COLLECTION_TOO_LARGE", failure.get().errorCode());
		assertEquals("$.tags", failure.get().fieldPath());
	}

	@Test
	@DisplayName("objects above the collection limit are rejected")
	void oversizedObjectIsRejected() {
		StringBuilder args = new StringBuilder("{");
		for (int i = 0; i <= ToolArgumentValidator.MAX_COLLECTION_SIZE; i++) {
			args.append(i > 0 ? "," : "").append("\"k").append(i).append("\":1");
		}
		args.append("}");

		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate("{}", args.toString());
		assertTrue(failure.isPresent());
		assertEquals("COLLECTION_TOO_LARGE", failure.get().errorCode());
	}

	@Test
	@DisplayName("local $ref is expanded and its rules enforced with the property path")
	void localRefIsExpanded() {
		String refSchema = """
				{
				  "type": "object",
				  "properties": {
				    "name": { "$ref": "#/definitions/str" }
				  },
				  "definitions": { "str": { "type": "string" } }
				}""";
		assertTrue(ToolArgumentValidator.validate(refSchema, "{\"name\": \"ok\"}").isEmpty());

		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(refSchema, "{\"name\": 1}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
		assertEquals("$.name", failure.get().fieldPath());
	}

	@Test
	@DisplayName("a root-level $ref schema is expanded")
	void rootLevelRefIsExpanded() {
		String refSchema = "{\"$ref\": \"#/definitions/obj\", \"definitions\": {\"obj\": "
				+ "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"required\":[\"n\"]}}}";
		assertTrue(ToolArgumentValidator.validate(refSchema, "{\"n\": 2}").isEmpty());

		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(refSchema, "{}");
		assertTrue(failure.isPresent());
		assertEquals("REQUIRED_MISSING", failure.get().errorCode());
	}

	@Test
	@DisplayName("remote $ref is rejected explicitly and never fetched")
	void remoteRefIsRejected() {
		String remoteSchema = "{\"type\":\"object\",\"properties\":{\"name\":{\"$ref\":\"https://example.com/schema\"}}}";
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(remoteSchema, "{\"name\": 1}");
		assertTrue(failure.isPresent());
		assertEquals("REF_NOT_LOCAL", failure.get().errorCode());
	}

	@Test
	@DisplayName("unresolvable local pointer fails explicitly")
	void unresolvableRefFails() {
		String brokenSchema = "{\"type\":\"object\",\"properties\":{\"name\":{\"$ref\":\"#/definitions/missing\"}}}";
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(brokenSchema, "{\"name\": 1}");
		assertTrue(failure.isPresent());
		assertEquals("REF_UNRESOLVED", failure.get().errorCode());
	}

	@Test
	@DisplayName("cyclic $ref definitions are detected instead of looping")
	void cyclicRefIsDetected() {
		String cyclicSchema = """
				{
				  "type": "object",
				  "properties": {
				    "a": { "$ref": "#/definitions/b" }
				  },
				  "definitions": {
				    "a": { "$ref": "#/definitions/b" },
				    "b": { "$ref": "#/definitions/a" }
				  }
				}""";
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(cyclicSchema, "{\"a\": {}}");
		assertTrue(failure.isPresent());
		assertEquals("REF_CYCLE", failure.get().errorCode());
	}

	@Test
	@DisplayName("JSON pointer ~0 and ~1 escapes are honored")
	void pointerEscapesAreHonored() {
		String escapedSchema = """
				{
				  "type": "object",
				  "properties": {
				    "k": { "$ref": "#/definitions/a~1b" }
				  },
				  "definitions": {
				    "a/b": { "type": "string" }
				  }
				}""";
		assertTrue(ToolArgumentValidator.validate(escapedSchema, "{\"k\": \"ok\"}").isEmpty());

		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(escapedSchema, "{\"k\": 5}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
	}

	@Test
	@DisplayName("collections at exactly the limit still pass")
	void collectionAtLimitPasses() {
		String arraySchema = "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";
		StringBuilder args = new StringBuilder("{\"tags\": [");
		for (int i = 0; i < ToolArgumentValidator.MAX_COLLECTION_SIZE; i++) {
			args.append(i > 0 ? "," : "").append("\"v\"");
		}
		args.append("]}");
		assertTrue(ToolArgumentValidator.validate(arraySchema, args.toString()).isEmpty());
	}

}
