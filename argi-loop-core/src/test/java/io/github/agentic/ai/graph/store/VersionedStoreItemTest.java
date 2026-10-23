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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedStoreItemTest {

	@Test
	void rejectsNegativeVersion() {
		assertThrows(IllegalArgumentException.class, () -> new VersionedStoreItem(Optional.empty(), -1));
	}

	@Test
	void rejectsLiveItemAtZeroVersion() {
		StoreItem item = StoreItem.of(List.of("users", "u1"), "profile", Map.of("name", "Ada"));

		assertThrows(IllegalArgumentException.class, () -> new VersionedStoreItem(Optional.of(item), 0));
	}

	@Test
	void allowsNeverWrittenVersionZero() {
		VersionedStoreItem item = new VersionedStoreItem(Optional.empty(), 0);

		assertEquals(Optional.empty(), item.item());
		assertEquals(0, item.version());
	}

	@Test
	void allowsPositiveTombstone() {
		assertDoesNotThrow(() -> new VersionedStoreItem(Optional.empty(), 3));
	}

	@Test
	void allowsLiveItemWithPositiveVersion() {
		StoreItem storeItem = StoreItem.of(List.of("users", "u1"), "profile", Map.of("name", "Ada"));
		VersionedStoreItem item = new VersionedStoreItem(Optional.of(storeItem), 1);

		assertEquals(Optional.of(storeItem), item.item());
		assertEquals(1, item.version());
	}

}
