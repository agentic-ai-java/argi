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
package io.github.agentic.ai.graph.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryFileStorageTest {

	private static final String MAX_RECORDS_PROPERTY = "argi.graph.in-memory-file-storage.max-records";

	private static final String MAX_TOTAL_BYTES_PROPERTY = "argi.graph.in-memory-file-storage.max-total-bytes";

	@BeforeEach
	void setUp() {
		InMemoryFileStorage.clear();
	}

	@AfterEach
	void tearDown() {
		System.clearProperty(MAX_RECORDS_PROPERTY);
		System.clearProperty(MAX_TOTAL_BYTES_PROPERTY);
		InMemoryFileStorage.clear();
	}

	@Test
	void saveRejectsFilesWhenTotalByteLimitWouldBeExceeded() {
		System.setProperty(MAX_TOTAL_BYTES_PROPERTY, "4");

		InMemoryFileStorage.FileRecord record = InMemoryFileStorage.save(new byte[] { 1, 2 }, "text/plain", "a.txt");

		assertThat(InMemoryFileStorage.get(record.getId())).isNotNull();
		assertThatThrownBy(() -> InMemoryFileStorage.save(new byte[] { 3, 4, 5 }, "text/plain", "b.txt"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("maximum total in-memory file storage size");
	}

	@Test
	void saveRejectsFilesWhenRecordLimitWouldBeExceeded() {
		System.setProperty(MAX_RECORDS_PROPERTY, "1");

		InMemoryFileStorage.save(new byte[] { 1 }, "text/plain", "a.txt");

		assertThatThrownBy(() -> InMemoryFileStorage.save(new byte[] { 2 }, "text/plain", "b.txt"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("maximum in-memory file storage records");
	}

}
