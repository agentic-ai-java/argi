/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.workflow;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Captures supported definition data without retaining caller-owned mutable references. */
final class WorkflowDataSnapshot {

	private static final int MAX_CONTAINER_DEPTH = 128;

	private final Map<Object, Snapshot> snapshots = new IdentityHashMap<>();

	private final Map<Object, Boolean> visiting = new IdentityHashMap<>();

	private WorkflowDataSnapshot() {
	}

	static Object value(Object value) {
		return new WorkflowDataSnapshot().copy(value, 0);
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> metadata(Map<String, Object> metadata) {
		// Preserve the existing rejection of null top-level metadata keys and values.
		for (Map.Entry<?, ?> entry : metadata.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "metadata key must not be null");
			Objects.requireNonNull(entry.getValue(), "metadata value must not be null");
		}
		// copy() validates all keys and produces only Map<String, Object> map snapshots.
		return (Map<String, Object>) value(metadata);
	}

	private Object copy(Object value, int depth) {
		if (value == null || isScalar(value.getClass())) {
			return value;
		}
		if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
			throw new IllegalArgumentException("Unsupported workflow definition value type: " + value.getClass().getName());
		}
		final Snapshot cached = snapshots.get(value);
		if (depth >= MAX_CONTAINER_DEPTH || (cached != null && depth + cached.height() > MAX_CONTAINER_DEPTH)) {
			throw new IllegalArgumentException("Workflow definition data exceeds " + MAX_CONTAINER_DEPTH
					+ " nested collection levels");
		}
		if (visiting.containsKey(value)) {
			throw new IllegalArgumentException("Workflow definition data must not contain cyclic collections");
		}
		if (cached != null) {
			return cached.value();
		}
		visiting.put(value, true);
		try {
			final Object snapshot;
			int height = 1;
			if (value instanceof Map<?, ?> map) {
				final Map<String, Object> result = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (!(entry.getKey() instanceof String key)) {
						throw new IllegalArgumentException("Workflow definition data requires string map keys");
					}
					result.put(key, copy(entry.getValue(), depth + 1));
					height = Math.max(height, 1 + heightOf(entry.getValue()));
				}
				snapshot = Collections.unmodifiableMap(result);
			}
			else {
				final List<Object> result = new ArrayList<>();
				for (Object element : (List<?>) value) {
					result.add(copy(element, depth + 1));
					height = Math.max(height, 1 + heightOf(element));
				}
				snapshot = Collections.unmodifiableList(result);
			}
			snapshots.put(value, new Snapshot(snapshot, height));
			return snapshot;
		}
		finally {
			visiting.remove(value);
		}
	}

	private int heightOf(Object value) {
		final Snapshot snapshot = snapshots.get(value);
		return snapshot == null ? 0 : snapshot.height();
	}

	private record Snapshot(Object value, int height) {
	}

	private static boolean isScalar(Class<?> type) {
		return type == String.class || type == Boolean.class || type == Character.class || type == Byte.class
				|| type == Short.class || type == Integer.class || type == Long.class || type == Float.class
				|| type == Double.class || type == BigInteger.class || type == BigDecimal.class;
	}

}
