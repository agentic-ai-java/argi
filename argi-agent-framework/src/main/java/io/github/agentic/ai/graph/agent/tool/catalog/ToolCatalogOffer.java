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

import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Result of releasing full schemas for a set of tool ids.
 *
 * @param offered data-only snapshot of every definition released by this offer, in
 * request order; record it to know exactly which versions the model was shown
 * @param tools executable bindings for {@code offered}, in the same order, each exposed
 * under its qualified name
 * @param rejected requested ids that were not released because they are unknown,
 * unauthorized, no longer resolvable or over the catalog bound; the reasons are not
 * distinguished so guessed ids cannot be used to probe the catalog
 */
public record ToolCatalogOffer(List<OfferedTool> offered, List<Tool> tools, List<ToolId> rejected) {

	public ToolCatalogOffer {
		offered = List.copyOf(offered);
		tools = List.copyOf(tools);
		rejected = List.copyOf(rejected);
		if (offered.size() != tools.size()) {
			throw new IllegalArgumentException("Every offered definition needs exactly one binding");
		}
		for (int i = 0; i < offered.size(); i++) {
			if (!offered.get(i).definition().equals(tools.get(i).definition())) {
				throw new IllegalArgumentException("Offered definition does not match its binding");
			}
		}
	}

	static ToolCatalogOffer empty(List<ToolId> rejected) {
		return new ToolCatalogOffer(List.of(), List.of(), rejected);
	}

	/** Immutable registry of the offered bindings, suitable for dynamic disclosure. */
	public ToolRegistry registry() {
		return new ToolRegistry(this.tools);
	}

	/** Finds the offered record for a model-facing qualified name. */
	public Optional<OfferedTool> find(String qualifiedName) {
		return this.offered.stream().filter(tool -> tool.definition().name().equals(qualifiedName)).findFirst();
	}

	public boolean isEmpty() {
		return this.offered.isEmpty();
	}

}
