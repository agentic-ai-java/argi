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

package io.github.agentic.ai.graph.agent.tool.catalog;

import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import io.github.agentic.ai.graph.execution.definition.DefinitionReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Data-only record of one tool definition offered to the model.
 *
 * <p>
 * It pins the exact version and a digest of the disclosed definition so a later step can
 * resolve the same offered version through
 * {@link ToolCatalog#resolveOffered(OfferedTool, io.github.agentic.ai.graph.execution.TrustedExecutionContext)}
 * and detect a provider that changed a version in place. It carries no executable handle.
 * </p>
 *
 * @param id the qualified tool id
 * @param version the offered version
 * @param definition the model-facing definition; its name is {@link ToolId#qualifiedName()}
 * @param schemaDigest SHA-256 of the model-facing definition
 */
public record OfferedTool(ToolId id, String version, ToolDefinition definition, String schemaDigest) {

	public OfferedTool {
		Objects.requireNonNull(id, "id must not be null");
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("version must not be blank");
		}
		Objects.requireNonNull(definition, "definition must not be null");
		if (!id.qualifiedName().equals(definition.name())) {
			throw new IllegalArgumentException("Offered definition name must be the qualified tool name");
		}
		if (schemaDigest == null || schemaDigest.isBlank()) {
			throw new IllegalArgumentException("schemaDigest must not be blank");
		}
	}

	static OfferedTool of(ToolId id, String version, ToolDefinition definition) {
		return new OfferedTool(id, version, definition, digest(definition));
	}

	/**
	 * Version-pinned reference of this offer in the run definition model.
	 * @return a {@link DefinitionKind#TOOL_CATALOG} reference
	 */
	public DefinitionReference definitionReference() {
		return DefinitionReference.of(DefinitionKind.TOOL_CATALOG, this.id.qualifiedName(), this.version);
	}

	static String digest(ToolDefinition definition) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			for (String part : new String[] { definition.name(), definition.description(),
					definition.inputSchema() }) {
				byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
				sha256.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
				sha256.update((byte) ':');
				sha256.update(bytes);
			}
			return HexFormat.of().formatHex(sha256.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
