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

import java.util.regex.Pattern;

/**
 * Stable, provider-qualified identity of a catalog tool.
 *
 * <p>
 * Two providers may expose a tool with the same {@code name}; the {@code provider}
 * segment keeps their identities distinct. The {@link #qualifiedName() qualified name}
 * is also the name disclosed to the model, so it uses only characters accepted by common
 * model function-name rules. The provider segment cannot contain an underscore, which
 * makes the first {@value #SEPARATOR} in a qualified name an unambiguous separator.
 * </p>
 *
 * @param provider the catalog provider id, matching {@code [A-Za-z0-9-]+}
 * @param name the provider-local tool name, matching {@code [A-Za-z0-9_-]+}
 */
public record ToolId(String provider, String name) {

	/** Separator between the provider and the provider-local name. */
	public static final String SEPARATOR = "__";

	private static final Pattern PROVIDER = Pattern.compile("[A-Za-z0-9-]+");

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

	public ToolId {
		provider = requireProvider(provider);
		if (name == null || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Tool name must match [A-Za-z0-9_-]+: " + name);
		}
	}

	public static ToolId of(String provider, String name) {
		return new ToolId(provider, name);
	}

	/**
	 * Parses a qualified name produced by {@link #qualifiedName()}.
	 * @param qualifiedName the qualified name
	 * @return the tool id
	 * @throws IllegalArgumentException when the value is not a qualified tool name
	 */
	public static ToolId parse(String qualifiedName) {
		if (qualifiedName == null) {
			throw new IllegalArgumentException("Qualified tool name must not be null");
		}
		int separator = qualifiedName.indexOf(SEPARATOR);
		if (separator <= 0) {
			throw new IllegalArgumentException("Not a qualified tool name: " + qualifiedName);
		}
		return new ToolId(qualifiedName.substring(0, separator),
				qualifiedName.substring(separator + SEPARATOR.length()));
	}

	/** Validates a provider id using the same rules as the provider segment. */
	static String requireProvider(String provider) {
		if (provider == null || !PROVIDER.matcher(provider).matches()) {
			throw new IllegalArgumentException("Tool catalog provider id must match [A-Za-z0-9-]+: " + provider);
		}
		return provider;
	}

	/** Model-facing name that stays stable across turns and across providers. */
	public String qualifiedName() {
		return this.provider + SEPARATOR + this.name;
	}

	@Override
	public String toString() {
		return qualifiedName();
	}

}
