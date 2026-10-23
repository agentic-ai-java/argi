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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static io.github.agentic.ai.graph.serializer.plain_text.jackson.TypeMapper.TYPE_PROPERTY;

final class MapEnvelopeSupport {

	static final String MAP_ENVELOPE_TYPE = "ARGI_MAP";

	static final String MAP_CLASS_PROPERTY = "mapClass";

	static final String MAP_ENTRIES_PROPERTY = "entries";

	private MapEnvelopeSupport() {
	}

	static Map<Object, Object> mapFromEnvelope(JsonNode valueNode, ObjectMapper objectMapper, TypeMapper typeMapper)
			throws IOException {
		Map<Object, Object> result = instantiateMap(valueNode.path(MAP_CLASS_PROPERTY).asText(null));
		JsonNode entries = unwrapTypedArray(valueNode.get(MAP_ENTRIES_PROPERTY));
		if (entries == null || !entries.isArray()) {
			return result;
		}
		for (JsonNode entry : entries) {
			entry = unwrapTypedArray(entry);
			Object key;
			Object value;
			if (entry.isArray() && entry.size() == 2) {
				key = JacksonDeserializer.valueFromNode(entry.get(0), objectMapper, typeMapper);
				value = JacksonDeserializer.valueFromNode(entry.get(1), objectMapper, typeMapper);
			}
			else {
				continue;
			}
			result.put(key, value);
		}
		return result;
	}

	static boolean isMapEnvelope(JsonNode node) {
		if (node == null || !node.isObject() || !node.has(TYPE_PROPERTY)
				|| !MAP_ENVELOPE_TYPE.equals(node.get(TYPE_PROPERTY).asText())) {
			return false;
		}
		if (!node.has(MAP_CLASS_PROPERTY) || !node.get(MAP_CLASS_PROPERTY).isTextual()
				|| !isMapClass(node.get(MAP_CLASS_PROPERTY).asText())) {
			return false;
		}
		if (hasUnexpectedMapEnvelopeFields(node)) {
			return false;
		}
		JsonNode entries = unwrapTypedArray(node.get(MAP_ENTRIES_PROPERTY));
		if (entries == null || !entries.isArray()) {
			return false;
		}
		for (JsonNode entry : entries) {
			JsonNode unwrapped = unwrapTypedArray(entry);
			if (!unwrapped.isArray() || unwrapped.size() != 2) {
				return false;
			}
		}
		return true;
	}

	static boolean hasMapEnvelopeMarker(JsonNode node) {
		return node != null && node.isObject() && node.has(TYPE_PROPERTY)
				&& MAP_ENVELOPE_TYPE.equals(node.get(TYPE_PROPERTY).asText());
	}

	private static boolean hasUnexpectedMapEnvelopeFields(JsonNode node) {
		var fields = node.fieldNames();
		while (fields.hasNext()) {
			String field = fields.next();
			if (TYPE_PROPERTY.equals(field) || MAP_CLASS_PROPERTY.equals(field) || MAP_ENTRIES_PROPERTY.equals(field)) {
				continue;
			}
			if ("@class".equals(field) && isMapClass(node.get(field).asText())) {
				continue;
			}
			return true;
		}
		return false;
	}

	private static boolean isMapClass(String className) {
		try {
			return Map.class.isAssignableFrom(Class.forName(className));
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

	private static JsonNode unwrapTypedArray(JsonNode node) {
		if (node != null && node.isArray() && node.size() == 2 && node.get(0).isTextual()
				&& node.get(0).asText().startsWith("[")) {
			return node.get(1);
		}
		return node;
	}

	private static Map<Object, Object> instantiateMap(String className) {
		if (className != null) {
			try {
				Class<?> mapClass = Class.forName(className);
				if (Map.class.isAssignableFrom(mapClass) && !mapClass.isInterface()
						&& !Modifier.isAbstract(mapClass.getModifiers())) {
					@SuppressWarnings("unchecked")
					Map<Object, Object> result = (Map<Object, Object>) mapClass.getDeclaredConstructor().newInstance();
					return result;
				}
			}
			catch (ReflectiveOperationException | LinkageError ex) {
				// Fall back to an insertion-ordered map for non-instantiable custom maps.
			}
		}
		return new LinkedHashMap<>();
	}

}
