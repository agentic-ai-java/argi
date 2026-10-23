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

package io.github.agentic.ai.graph.checkpoint.savers.oracle;

/**
 * Options which configure the creation of database schema objects, such as
 * tables and indexes.
 * <p>
 * Replacement: add artifact
 * {@code io.github.agentic-ai:argi-graph-persistence-jdbc}
 * and use
 * {@code io.github.agentic.ai.graph.persistence.jdbc.oracle.CreateOption}.
 *
 * @deprecated since 2.1.0 for removal. Use the replacement artifact and FQCN
 * listed above.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
public enum CreateOption {

	/** No attempt is made to create the schema object. */
	CREATE_NONE,

	/** An existing schema object is reused, otherwise it is created. */
	CREATE_IF_NOT_EXISTS,

	/** An existing schema object is dropped and replaced with a new one. */
	CREATE_OR_REPLACE
}
