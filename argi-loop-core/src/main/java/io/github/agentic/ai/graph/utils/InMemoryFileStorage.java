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

import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFileStorage {

	private static final String MAX_RECORDS_PROPERTY = "argi.graph.in-memory-file-storage.max-records";

	private static final String MAX_TOTAL_BYTES_PROPERTY = "argi.graph.in-memory-file-storage.max-total-bytes";

	private static final int DEFAULT_MAX_RECORDS = 1024;

	private static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024L * 1024L;

	private static final Map<String, FileRecord> CACHE = new ConcurrentHashMap<>();

	private static long totalBytes;

	public static class FileRecord {

		private final String id;

		private final String fileKey;

		private final String name;

		private final String mimetype;

		private final long size;

		private final byte[] content;

		public FileRecord(String id, String fileKey, String name, String mimetype, long size, byte[] content) {
			this.id = id;
			this.fileKey = fileKey;
			this.name = name;
			this.mimetype = mimetype;
			this.size = size;
			this.content = content;
		}

		public String getId() {
			return id;
		}

		public String getFileKey() {
			return fileKey;
		}

		public String getName() {
			return name;
		}

		public String getMimetype() {
			return mimetype;
		}

		public long getSize() {
			return size;
		}

		public byte[] getContent() {
			return content;
		}

	}

	public static synchronized FileRecord save(byte[] content, String mimetype, String originalFilename) {
		Objects.requireNonNull(content, "content must not be null");
		int maxRecords = intProperty(MAX_RECORDS_PROPERTY, DEFAULT_MAX_RECORDS);
		long maxTotalBytes = longProperty(MAX_TOTAL_BYTES_PROPERTY, DEFAULT_MAX_TOTAL_BYTES);
		if (maxRecords >= 0 && CACHE.size() >= maxRecords) {
			throw new IllegalStateException("Exceeded maximum in-memory file storage records: " + maxRecords);
		}
		if (maxTotalBytes >= 0 && totalBytes + content.length > maxTotalBytes) {
			throw new IllegalStateException("Exceeded maximum total in-memory file storage size: " + maxTotalBytes);
		}
		String id = UUID.randomUUID().toString();
		String extension = Optional.of(org.springframework.http.MediaType.parseMediaType(mimetype).getSubtype())
			.orElse("bin");
		String filename = StringUtils.hasText(originalFilename) ? originalFilename : id + "." + extension;
		String key = String.format("inmem://%s", id);
		FileRecord record = new FileRecord(id, key, filename, mimetype, content.length, content);
		CACHE.put(id, record);
		totalBytes += content.length;
		return record;
	}

	public static FileRecord get(String id) {
		return CACHE.get(id);
	}

	public static synchronized void remove(String id) {
		FileRecord removed = CACHE.remove(id);
		if (removed != null) {
			totalBytes -= removed.getSize();
		}
	}

	public static synchronized void clear() {
		CACHE.clear();
		totalBytes = 0;
	}

	private static int intProperty(String name, int defaultValue) {
		String value = System.getProperty(name);
		if (!StringUtils.hasText(value)) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ex) {
			return defaultValue;
		}
	}

	private static long longProperty(String name, long defaultValue) {
		String value = System.getProperty(name);
		if (!StringUtils.hasText(value)) {
			return defaultValue;
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException ex) {
			return defaultValue;
		}
	}

}
