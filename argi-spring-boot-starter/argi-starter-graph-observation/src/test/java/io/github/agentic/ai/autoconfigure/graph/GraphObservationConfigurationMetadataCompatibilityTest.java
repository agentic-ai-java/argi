/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.autoconfigure.graph;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphObservationConfigurationMetadataCompatibilityTest {

	private static final String PRODUCTION_METADATA = "/META-INF/spring-configuration-metadata.json";

	private static final String CONTRACT_METADATA = "/compatibility/2.1.0/graph-observation-metadata.json";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void metadataKeepsLegacyPropertyNamesTypesAndDefaultsCompatible() throws IOException {
		JsonNode production = read(PRODUCTION_METADATA);
		JsonNode expected = read(CONTRACT_METADATA);

		assertThat(contract(production)).isEqualTo(contract(expected));
	}

	private JsonNode read(String resourcePath) throws IOException {
		InputStream inputStream = getClass().getResourceAsStream(resourcePath);
		assertThat(inputStream).as("Classpath resource %s", resourcePath).isNotNull();
		try (inputStream) {
			return mapper.readTree(inputStream);
		}
	}

	private JsonNode contract(JsonNode root) {
		ArrayNode result = mapper.createArrayNode();
		StreamSupport.stream(root.path("properties").spliterator(), false)
			.sorted(Comparator.comparing(node -> node.path("name").asText()))
			.map(node -> {
				ObjectNode property = mapper.createObjectNode();
				property.set("name", node.path("name"));
				property.set("type", node.path("type"));
				property.set("defaultValue", node.has("defaultValue") ? node.get("defaultValue") : NullNode.instance);
				return property;
			})
			.forEach(result::add);
		return result;
	}

}
