/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OverAllStateToStringTest {

	@Test
	void formatsNeutralMessagesAndLargeUsageWithoutChangingState() throws Exception {
		Map<String, Object> data = new HashMap<>();
		data.put("messages", List.of(ChatMessage.user("quoted \"text\"\nnext line")));
		data.put("usage", new Usage(3_000_000_000L, null, null));
		data.put("nullable", null);
		OverAllState state = new OverAllState(data);
		JsonNode root = new ObjectMapper().readTree(state.toString());
		JsonNode view = root.path("OverAllState").path("data");
		assertThat(view.path("messages").get(0).path("role").asText()).isEqualTo("USER");
		assertThat(view.path("messages").get(0).path("content").get(0).path("text").asText())
			.isEqualTo("quoted \"text\"\nnext line");
		assertThat(view.path("usage").path("inputTokens").asLong()).isEqualTo(3_000_000_000L);
		assertThat(view.path("nullable").isNull()).isTrue();
		assertThat(state.data()).isEqualTo(data);
	}

	@Test
	void keepsDiagnosticFallbackForObjectsThatCannotBeEncoded() {
		Object unsupported = new Object();
		OverAllState state = new OverAllState(Map.of("unsupported", unsupported));
		assertThat(state.toString()).startsWith("OverAllState{data=").contains(unsupported.toString());
	}

	@Test
	void keepsDiagnosticFallbackForFailingGetters() {
		OverAllState state = new OverAllState(Map.of("unsupported", new FailingBean()));
		assertThat(state.toString()).startsWith("OverAllState{data=");
	}

	public static final class FailingBean {

		public String getValue() {
			throw new IllegalStateException("Cannot read diagnostic value");
		}

	}

}
