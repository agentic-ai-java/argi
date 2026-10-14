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
package io.github.agentic.ai.api.chat;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** A provider-neutral content part in a chat message. */
public record ContentPart(Type type, String text, String mimeType, byte[] data, Map<String, Object> metadata) {

	public enum Type {

		TEXT, JSON, IMAGE, AUDIO, OTHER

	}

	public ContentPart {
		Objects.requireNonNull(type, "type must not be null");
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		data = data == null ? null : data.clone();
	}

	public static ContentPart text(String text) {
		return new ContentPart(Type.TEXT, Objects.requireNonNull(text, "text must not be null"), null, null, Map.of());
	}

	public static ContentPart json(String json) {
		return new ContentPart(Type.JSON, Objects.requireNonNull(json, "json must not be null"), "application/json",
				null, Map.of());
	}

	public static ContentPart binary(Type type, String mimeType, byte[] data) {
		if (type == Type.TEXT || type == Type.JSON) {
			throw new IllegalArgumentException("Binary content must use a non-text type");
		}
		return new ContentPart(type, null, mimeType, data, Map.of());
	}

	@Override
	public byte[] data() {
		return data == null ? null : data.clone();
	}

	/**
	 * Binary content participates in value equality, including after checkpoint
	 * restoration.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ContentPart part)) {
			return false;
		}
		return type == part.type && Objects.equals(text, part.text) && Objects.equals(mimeType, part.mimeType)
				&& Arrays.equals(data, part.data) && metadata.equals(part.metadata);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(type, text, mimeType, metadata);
		return 31 * result + Arrays.hashCode(data);
	}
}
