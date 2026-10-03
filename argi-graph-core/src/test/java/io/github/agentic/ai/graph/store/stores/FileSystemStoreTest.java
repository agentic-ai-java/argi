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
package io.github.agentic.ai.graph.store.stores;

import io.github.agentic.ai.graph.store.NamespaceListRequest;
import io.github.agentic.ai.graph.store.StoreItem;
import io.github.agentic.ai.graph.store.StoreSearchRequest;
import io.github.agentic.ai.graph.store.StoreSearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for FileSystemStore implementation.
 *
 */
class FileSystemStoreTest {

	@TempDir
	Path tempDir;

	private FileSystemStore store;

	@BeforeEach
	void setUp() {
		store = new FileSystemStore(tempDir);
	}

	@Test
	void testPutAndGetItem() throws IOException {
		// Given
		List<String> namespace = List.of("users", "user123");
		String key = "preferences";
		Map<String, Object> value = Map.of("theme", "dark", "language", "en-US");
		StoreItem item = StoreItem.of(namespace, key, value);

		// When
		store.putItem(item);
		Optional<StoreItem> retrieved = store.getItem(namespace, key);

		// Then
		assertThat(retrieved).isPresent();
		assertThat(retrieved.get().getNamespace()).isEqualTo(namespace);
		assertThat(retrieved.get().getKey()).isEqualTo(key);
		assertThat(retrieved.get().getValue()).isEqualTo(value);

		// Verify file structure
		Path expectedFile = tempDir.resolve("users").resolve("user123").resolve("preferences.json");
		assertThat(Files.exists(expectedFile)).isTrue();
	}

	@Test
	void testDeleteItem() {
		// Given
		List<String> namespace = List.of("test", "namespace");
		String key = "test_key";
		Map<String, Object> value = Map.of("data", "test_value");
		StoreItem item = StoreItem.of(namespace, key, value);

		store.putItem(item);
		assertThat(store.getItem(namespace, key)).isPresent();

		// When
		boolean deleted = store.deleteItem(namespace, key);

		// Then
		assertThat(deleted).isTrue();
		assertThat(store.getItem(namespace, key)).isEmpty();
		assertThat(store.deleteItem(namespace, key)).isFalse(); // Already deleted
	}

	@ParameterizedTest
	@CsvSource({ "relative, true", "relative, false", "absolute, true", "non-normalized, true" })
	void testDeleteItemPreservesRootDirectory(String pathType, boolean nestedNamespace) throws IOException {
		Path parent = Files.createDirectories(tempDir.resolve("parent"));
		Path root = Files.createDirectories(parent.resolve("store"));
		Path outsideFile = Files.writeString(tempDir.resolve("outside.txt"), "keep");
		Path configuredRoot = switch (pathType) {
			case "relative" -> Path.of("").toAbsolutePath().normalize().relativize(root);
			case "non-normalized" -> root.resolve("..").resolve("store");
			default -> root;
		};
		FileSystemStore fileStore = new FileSystemStore(configuredRoot);
		List<String> namespace = nestedNamespace ? List.of("users", "user1") : List.of();
		fileStore.putItem(StoreItem.of(namespace, "only", Map.of("value", "test")));

		assertThat(fileStore.deleteItem(namespace, "only")).isTrue();

		assertThat(Files.exists(root.resolve("users"))).isFalse();
		assertThat(Files.isDirectory(root)).isTrue();
		assertThat(Files.isDirectory(parent)).isTrue();
		assertThat(Files.exists(outsideFile)).isTrue();
	}

	@Test
	void testDeleteItemPreservesNonEmptySiblingNamespace() {
		Path root = tempDir.resolve("store");
		Path relativeRoot = Path.of("").toAbsolutePath().normalize().relativize(root);
		FileSystemStore fileStore = new FileSystemStore(relativeRoot);
		List<String> deletedNamespace = List.of("users", "user1");
		List<String> remainingNamespace = List.of("users", "user2");
		fileStore.putItem(StoreItem.of(deletedNamespace, "only", Map.of("value", "delete")));
		fileStore.putItem(StoreItem.of(remainingNamespace, "only", Map.of("value", "keep")));

		assertThat(fileStore.deleteItem(deletedNamespace, "only")).isTrue();

		assertThat(Files.exists(root.resolve("users").resolve("user1"))).isFalse();
		assertThat(Files.isDirectory(root)).isTrue();
		assertThat(fileStore.getItem(remainingNamespace, "only")).isPresent();
	}

	@Test
	void testSearchItems() {
		// Given
		setupTestData();

		// When - search all items
		StoreSearchRequest request = StoreSearchRequest.builder().build();
		StoreSearchResult result = store.searchItems(request);

		// Then
		assertThat(result.getItems()).hasSize(3);
		assertThat(result.getTotalCount()).isEqualTo(3);
	}

	@Test
	void testListNamespaces() {
		// Given
		setupTestData();

		// When
		NamespaceListRequest request = NamespaceListRequest.builder().build();
		List<String> namespaces = store.listNamespaces(request);

		// Then
		assertThat(namespaces).hasSize(6);
		assertThat(namespaces).containsExactlyInAnyOrder("users", "users/admin", "users/user1",
				"users/user1/preferences", "users/user2", "users/user2/preferences");
	}

	@Test
	void testValidationErrors() {
		// Test null item
		assertThrows(IllegalArgumentException.class, () -> store.putItem(null));

		// Test null namespace
		assertThrows(IllegalArgumentException.class, () -> store.getItem(null, "key"));

		// Test null key
		assertThrows(IllegalArgumentException.class, () -> store.getItem(List.of("namespace"), null));

		// Test empty key
		assertThrows(IllegalArgumentException.class, () -> store.getItem(List.of("namespace"), ""));

		// Test null search request
		assertThrows(IllegalArgumentException.class, () -> store.searchItems(null));

		// Test null namespace request
		assertThrows(IllegalArgumentException.class, () -> store.listNamespaces(null));
	}

	@Test
	void testSizeAndClear() {
		// Given
		assertThat(store.isEmpty()).isTrue();
		assertThat(store.size()).isEqualTo(0);

		setupTestData();

		// When
		assertThat(store.isEmpty()).isFalse();
		assertThat(store.size()).isEqualTo(3);

		store.clear();

		// Then
		assertThat(store.isEmpty()).isTrue();
		assertThat(store.size()).isEqualTo(0);
	}

	@Test
	void testRejectsNamespaceTraversalSegments() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> store.putItem(
				StoreItem.of(List.of(".."), "escaped", Map.of("x", "y"))));
		assertThat(ex).hasCauseInstanceOf(IllegalArgumentException.class);
		assertThat(ex.getCause()).hasMessageContaining("unsafe path segment");
	}

	@Test
	void testRejectsKeyTraversalSegments() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> store.getItem(List.of("safe"), "../escaped"));
		assertThat(ex).hasCauseInstanceOf(IllegalArgumentException.class);
		assertThat(ex.getCause()).hasMessageContaining("unsafe path segment");
	}

	@Test
	void testDirectoryCreation() {
		// Given
		List<String> deepNamespace = List.of("level1", "level2", "level3", "level4");
		String key = "deep_item";
		Map<String, Object> value = Map.of("depth", 4);
		StoreItem item = StoreItem.of(deepNamespace, key, value);

		// When
		store.putItem(item);

		// Then
		Path expectedDir = tempDir.resolve("level1").resolve("level2").resolve("level3").resolve("level4");
		assertThat(Files.exists(expectedDir)).isTrue();
		assertThat(Files.isDirectory(expectedDir)).isTrue();

		Optional<StoreItem> retrieved = store.getItem(deepNamespace, key);
		assertThat(retrieved).isPresent();
		assertThat(retrieved.get().getValue()).isEqualTo(value);
	}

	private void setupTestData() {
		// User admin data
		store.putItem(
				StoreItem.of(List.of("users", "admin"), "profile", Map.of("name", "Administrator", "role", "admin")));

		// User1 preferences
		store.putItem(StoreItem.of(List.of("users", "user1", "preferences"), "ui_settings",
				Map.of("theme", "dark", "language", "en-US")));

		// User2 preferences
		store.putItem(StoreItem.of(List.of("users", "user2", "preferences"), "ui_settings",
				Map.of("theme", "light", "language", "zh-CN")));
	}

}
