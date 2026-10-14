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
package io.github.agentic.ai.graph.agent.tool.multimodal;

import org.junit.jupiter.api.Test;

import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.tool.ToolResult;
import java.util.Base64;
import org.springframework.util.MimeTypeUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolMultimodalResultTest {

	@Test
	void preservesUrlAndBytesInNeutralContent() {
		String url = "https://example.com/image.png";
		String base64 = "aW1hZ2U=";

		ContentPart media = ToolMultimodalResult.mediaFromUrlAndBase64(url, base64, MimeTypeUtils.IMAGE_PNG);
		assertEquals(url, media.metadata().get("uri"));
		assertEquals(base64, Base64.getEncoder().encodeToString(media.data()));
		assertEquals("image/png", media.mimeType());
	}

	@Test
	void convertsCombinedMediaToRequestedOutputFormat() {
		String url = "https://example.com/image.png";
		String base64 = "aW1hZ2U=";
		ToolMultimodalResult result = ToolMultimodalResult.of("image",
				ToolMultimodalResult.mediaFromUrlAndBase64(url, base64, MimeTypeUtils.IMAGE_PNG));

		ToolResult urlResult = new MultimodalToolCallResultConverter(OutputFormat.url).convert(result,
				ToolMultimodalResult.class);
		ToolResult base64Result = new MultimodalToolCallResultConverter(OutputFormat.base64).convert(result,
				ToolMultimodalResult.class);

		assertEquals(url, urlResult.content().get(1).metadata().get("uri"));
		assertEquals(null, urlResult.content().get(1).data());
		assertEquals(base64, Base64.getEncoder().encodeToString(base64Result.content().get(1).data()));
	}

	@Test
	void convertsBase64OnlyMediaWithoutUrl() {
		String base64 = "YXVkaW8=";
		ToolMultimodalResult result = ToolMultimodalResult.of("audio",
				ToolMultimodalResult.mediaFromBase64(base64, MimeTypeUtils.parseMimeType("audio/mpeg")));

		ToolResult converted = new MultimodalToolCallResultConverter(OutputFormat.base64).convert(result,
				ToolMultimodalResult.class);

		assertEquals(base64, Base64.getEncoder().encodeToString(converted.content().get(1).data()));
		assertEquals(ContentPart.Type.AUDIO, converted.content().get(1).type());
	}

}
