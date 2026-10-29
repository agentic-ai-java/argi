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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Single-process reference journal. A shared monitor makes each append atomic and
 * validate-before-publish: every entry is checked against the recorded stream before any
 * of them becomes visible, so a rejected batch leaves the journal unchanged.
 *
 * <p>
 * This implementation keeps data only in memory. It is a test and development backend,
 * not a durability guarantee; a journal that must survive a JVM must be backed by
 * storage and verified against {@code RunJournalContractTest}.
 */
public final class MemoryRunJournal implements RunJournal {

	private final Map<String, Map<String, RunEntry>> streams = new HashMap<>();

	@Override
	public synchronized Optional<RunEntry> find(String streamId, String entryId) {
		RunValidation.text(streamId, "streamId");
		RunValidation.text(entryId, "entryId");
		Map<String, RunEntry> stream = streams.get(streamId);
		return stream == null ? Optional.empty() : Optional.ofNullable(stream.get(entryId));
	}

	@Override
	public synchronized List<RunEntry> entries(String streamId) {
		RunValidation.text(streamId, "streamId");
		Map<String, RunEntry> stream = streams.get(streamId);
		return stream == null ? List.of() : List.copyOf(stream.values());
	}

	@Override
	public synchronized void append(String streamId, List<RunEntry> entries) {
		RunValidation.text(streamId, "streamId");
		requireNonNull(entries, "entries cannot be null");
		List<RunEntry> batch = List.copyOf(entries);
		for (RunEntry entry : batch) {
			requireNonNull(entry, "entries cannot contain null");
		}
		Map<String, RunEntry> stream = streams.get(streamId);
		// Validate the whole batch first: no partial write may survive a conflict.
		for (RunEntry entry : batch) {
			RunEntry existing = stream == null ? null : stream.get(entry.id());
			if (existing != null && !existing.equals(entry)) {
				throw new RunStoreConflictException(
						"Journal entry id identifies different data: " + streamId + "/" + entry.id());
			}
		}
		if (stream == null) {
			stream = new LinkedHashMap<>();
			streams.put(streamId, stream);
		}
		for (RunEntry entry : batch) {
			stream.putIfAbsent(entry.id(), entry);
		}
	}

	/** Entry count of one stream; a convenience for assertions and diagnostics. */
	public synchronized int size(String streamId) {
		Map<String, RunEntry> stream = streams.get(streamId);
		return stream == null ? 0 : stream.size();
	}

	/** All entry ids of one stream in append order. */
	public synchronized List<String> entryIds(String streamId) {
		Map<String, RunEntry> stream = streams.get(streamId);
		return stream == null ? List.of() : new ArrayList<>(stream.keySet());
	}

}
