/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.tool.multimodal;

import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.tool.ToolResult;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tool media expressed as neutral content parts, retaining references and inline bytes.
 */
public final class ToolMultimodalResult {

	private final String text;

	private final List<ContentPart> media;

	private ToolMultimodalResult(String text, List<ContentPart> media) {
		this.text = text;
		this.media = List.copyOf(media);
	}

	public String text() {
		return text;
	}

	public List<ContentPart> media() {
		return media;
	}

	public ToolResult toToolResult() {
		List<ContentPart> content = new ArrayList<>();
		if (text != null && !text.isEmpty()) {
			content.add(ContentPart.text(text));
		}
		content.addAll(media);
		return new ToolResult(content, false, Map.of());
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ToolMultimodalResult of(String text, ContentPart... media) {
		return builder().text(text).media(media).build();
	}

	private static ContentPart.Type type(MimeType mimeType) {
		return switch (mimeType.getType()) {
			case "image" -> ContentPart.Type.IMAGE;
			case "audio" -> ContentPart.Type.AUDIO;
			default -> ContentPart.Type.OTHER;
		};
	}

	public static ContentPart mediaFromUrl(String url, MimeType mimeType) {
		return mediaFromUri(URI.create(url), mimeType);
	}

	public static ContentPart mediaFromUri(URI uri, MimeType mimeType) {
		Objects.requireNonNull(uri, "uri must not be null");
		return new ContentPart(type(mimeType), null, mimeType.toString(), null, Map.of("uri", uri.toString()));
	}

	public static ContentPart mediaFromBytes(byte[] data, MimeType mimeType) {
		return ContentPart.binary(type(mimeType), mimeType.toString(), Objects.requireNonNull(data));
	}

	public static ContentPart mediaFromResource(Resource resource, MimeType mimeType) {
		try {
			return mediaFromBytes(resource.getContentAsByteArray(), mimeType);
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("Cannot read tool media resource", exception);
		}
	}

	public static ContentPart mediaFromBase64(String base64, MimeType mimeType) {
		return mediaFromBytes(Base64.getDecoder().decode(base64), mimeType);
	}

	public static ContentPart mediaFromUrlAndBase64(String url, String base64, MimeType mimeType) {
		if (base64 == null || base64.isBlank()) {
			return mediaFromUrl(url, mimeType);
		}
		return new ContentPart(type(mimeType), null, mimeType.toString(), Base64.getDecoder().decode(base64),
				url == null ? Map.of() : Map.of("uri", URI.create(url).toString()));
	}

	public static final class Builder {

		private String text;

		private final List<ContentPart> media = new ArrayList<>();

		public Builder text(String text) {
			this.text = text;
			return this;
		}

		public Builder media(ContentPart... items) {
			if (items != null) {
				media.addAll(Arrays.asList(items));
			}
			return this;
		}

		public Builder media(List<ContentPart> items) {
			if (items != null) {
				media.addAll(items);
			}
			return this;
		}

		public ToolMultimodalResult build() {
			return new ToolMultimodalResult(text, media);
		}

	}

}
