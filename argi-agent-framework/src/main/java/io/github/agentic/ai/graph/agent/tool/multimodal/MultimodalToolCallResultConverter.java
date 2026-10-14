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
import io.github.agentic.ai.graph.agent.MessageToolCallResultConverter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLConnection;
import java.util.ArrayList;

/**
 * Selects media references or inline bytes without reducing tool output to a JSON string.
 */
public class MultimodalToolCallResultConverter extends MessageToolCallResultConverter {

	private final OutputFormat outputFormat;

	public MultimodalToolCallResultConverter() {
		this(OutputFormat.url);
	}

	public MultimodalToolCallResultConverter(OutputFormat outputFormat) {
		this.outputFormat = outputFormat == null ? OutputFormat.url : outputFormat;
	}

	@Override
	public ToolResult convert(Object result, Type returnType) {
		ToolResult converted = super.convert(result, returnType);
		var content = new ArrayList<ContentPart>();
		for (ContentPart part : converted.content()) {
			Object uri = part.metadata().get("uri");
			if (outputFormat == OutputFormat.base64 && part.data() == null && uri instanceof String location) {
				content.add(ContentPart.binary(part.type(), part.mimeType(), readBytes(location)));
			}
			else if (outputFormat == OutputFormat.url && uri != null && part.data() != null) {
				content.add(new ContentPart(part.type(), part.text(), part.mimeType(), null, part.metadata()));
			}
			else {
				// Binary-only results stay inline; a local file URL is not accessible to
				// a remote model.
				content.add(part);
			}
		}
		return new ToolResult(content, converted.error(), converted.metadata());
	}

	private byte[] readBytes(String uri) {
		try {
			URLConnection connection = URI.create(uri).toURL().openConnection();
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(30_000);
			try (InputStream input = connection.getInputStream()) {
				return input.readAllBytes();
			}
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("Cannot read tool media reference", exception);
		}
	}

}
