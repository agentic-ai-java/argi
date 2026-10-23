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
package io.github.agentic.ai.graph.store;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable envelope for a store item and its compare-and-set version. The envelope is
 * immutable; the contained {@link StoreItem} keeps the existing mutable DTO contract and
 * is not deep-immutable.
 *
 * @param item the current item, or empty for absence/tombstone
 * @param version the nonnegative version
 * @since 2.1.0
 */
public record VersionedStoreItem(Optional<StoreItem> item, long version) {

	public VersionedStoreItem {
		requireNonNull(item, "item cannot be null");
		if (version < 0) {
			throw new IllegalArgumentException("version must be nonnegative");
		}
		if (item.isPresent() && version == 0) {
			throw new IllegalArgumentException("live item version must be positive");
		}
	}

}
