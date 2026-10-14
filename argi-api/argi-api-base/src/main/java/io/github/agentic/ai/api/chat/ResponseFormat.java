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

import java.util.Objects;

/** Structured output requirements for a model response. */
public record ResponseFormat(Type type, String name, String schema) {

	public enum Type {

		TEXT, JSON_OBJECT, JSON_SCHEMA

	}

	public ResponseFormat {
		Objects.requireNonNull(type, "type must not be null");
		if (type == Type.JSON_SCHEMA && (schema == null || schema.isBlank())) {
			throw new IllegalArgumentException("schema is required for JSON_SCHEMA response format");
		}
	}

	public static ResponseFormat text() {
		return new ResponseFormat(Type.TEXT, null, null);
	}

	public static ResponseFormat jsonObject() {
		return new ResponseFormat(Type.JSON_OBJECT, null, null);
	}

	public static ResponseFormat jsonSchema(String name, String schema) {
		return new ResponseFormat(Type.JSON_SCHEMA, name, schema);
	}
}
