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

import java.util.List;

/**
 * Store contract for implementations that expose atomic item version checks.
 * <p>
 * Conditional mutations compare the caller supplied expected version with the current
 * item version. A mismatch returns {@code false} without changing the item or version.
 * {@code expectedVersion} must be nonnegative. Every successful mutation increments the
 * version exactly once. Counter exhaustion must fail before mutation and must not wrap.
 * </p>
 * <p>
 * A never-written key has no item and version zero. Deleting an absent item returns
 * {@code false}. A successful delete leaves a positive tombstone version, so
 * {@code expectedVersion == 0} can only create a never-written key, not recreate a
 * previously deleted key.
 * </p>
 *
 * @since 2.1.0
 */
public interface VersionedStore extends Store {

	/**
	 * Retrieve an item with its current version. A never-written item has no value and
	 * version zero.
	 * @param namespace the hierarchical namespace path
	 * @param key the item key
	 * @return the current item envelope
	 */
	VersionedStoreItem getVersionedItem(List<String> namespace, String key);

	/**
	 * Store an item only when the current version matches {@code expectedVersion}.
	 * @param item the item to store
	 * @param expectedVersion the nonnegative caller-observed version
	 * @return true when the mutation was applied
	 */
	boolean putItemIfVersion(StoreItem item, long expectedVersion);

	/**
	 * Delete an item only when the current version matches {@code expectedVersion}.
	 * @param namespace the hierarchical namespace path
	 * @param key the item key
	 * @param expectedVersion the nonnegative caller-observed version
	 * @return true when the mutation was applied
	 */
	boolean deleteItemIfVersion(List<String> namespace, String key, long expectedVersion);

}
