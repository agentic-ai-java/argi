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

package io.github.agentic.ai.runtime.execution.run;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable semantic contract for {@link RunJournal} adapters. Backend suites should
 * extend this fixture and additionally verify restart persistence and atomic visibility
 * under real storage.
 */
public abstract class RunJournalContractTest {

	protected RunJournal journal;

	protected abstract RunJournal newJournal();

	@BeforeEach
	void setUp() {
		journal = newJournal();
	}

	@Test
	void unknownStreamAndEntryReadAsAbsent() {
		assertThat(journal.find("thread", "a")).isEmpty();
		assertThat(journal.entries("thread")).isEmpty();
	}

	@Test
	void preservesAppendOrderAcrossBatches() {
		journal.append("thread", List.of(entry("a"), entry("b")));
		journal.append("thread", List.of(entry("c")));
		assertThat(journal.entries("thread")).extracting(RunEntry::id).containsExactly("a", "b", "c");
	}

	@Test
	void reappendingIdenticalDataIsIdempotent() {
		RunEntry first = entry("a");
		journal.append("thread", List.of(first));
		journal.append("thread", List.of(entry("a")));
		assertThat(journal.entries("thread")).containsExactly(first);
	}

	@Test
	void rejectsSameIdWithDifferentDataWithoutPartialWrite() {
		journal.append("thread", List.of(entry("a")));
		assertThatThrownBy(() -> journal.append("thread", List.of(entry("b"), entry("a", "{\"value\":2}"))))
			.isInstanceOf(RunStoreConflictException.class);
		assertThat(journal.find("thread", "b")).isEmpty();
		assertThat(journal.entries("thread")).hasSize(1);
	}

	@Test
	void separatesStreamsWithIndependentIds() {
		journal.append("one", List.of(entry("a", "{\"value\":1}")));
		journal.append("two", List.of(entry("a", "{\"value\":2}")));
		assertThat(journal.find("one", "a")).hasValueSatisfying(
				value -> assertThat(value.payload().json()).contains("\"value\":1"));
		assertThat(journal.find("two", "a")).hasValueSatisfying(
				value -> assertThat(value.payload().json()).contains("\"value\":2"));
	}

	@Test
	void rejectsBlankIdentitiesAndNullEntries() {
		assertThatThrownBy(() -> journal.append(" ", List.of(entry("a")))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> journal.find("thread", " ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> journal.entries(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> journal.append("thread", List.of((RunEntry) null)))
			.isInstanceOf(NullPointerException.class);
	}

	protected static RunEntry entry(String id) {
		return entry(id, "{\"value\":1}");
	}

	protected static RunEntry entry(String id, String json) {
		return new RunEntry(1, id, RunEntry.Kind.MODEL_CALL, new RunPayload(1, "test.payload", json));
	}

}
