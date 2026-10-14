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
package io.github.agentic.ai.agent.studio.dto.messages;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolRequestConfirmMessageDTOTest {

	@Test
	void pendingFeedbackRemainsPendingAfterJsonRoundTrip() throws Exception {
		var metadata = InterruptionMetadata.builder()
			.addToolFeedback(
					InterruptionMetadata.ToolFeedback.builder().id("write_1").name("write").arguments("{}").build())
			.build();
		var mapper = new ObjectMapper();
		var dto = mapper.readValue(mapper.writeValueAsString(new ToolRequestConfirmMessageDTO(metadata)),
				ToolRequestConfirmMessageDTO.class);
		var restored = dto.toInterruptionMetadata("approval", new OverAllState(Map.of()));
		assertNull(restored.toolFeedbacks().get(0).getResult());
	}

	@Test
	void approvedNeutralCallsRoundTripWithoutFrameworkSpecificType() throws Exception {
		var call = new ToolCall("read_1", "read", "{}");
		var feedback = InterruptionMetadata.ToolFeedback.builder()
			.id("write_1")
			.name("write")
			.arguments("{}")
			.description("Reviewed")
			.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
			.build();
		var metadata = InterruptionMetadata.builder()
			.addMetadata("source", "review")
			.addToolFeedback(feedback)
			.addToolsAutomaticallyApproved(call)
			.build();
		var mapper = new ObjectMapper();
		var json = mapper.writeValueAsString(new ToolRequestConfirmMessageDTO(metadata));
		assertFalse(json.contains("\"type\""));
		var dto = mapper.readValue(json, ToolRequestConfirmMessageDTO.class);
		var restored = dto.toInterruptionMetadata("approval", new OverAllState(Map.of()));
		assertEquals(List.of(call), restored.getToolsAutomaticallyApproved());
		assertEquals("review", restored.metadata("source").orElseThrow());
		assertEquals(feedback.getResult(), restored.toolFeedbacks().get(0).getResult());
		assertEquals(feedback.getDescription(), restored.toolFeedbacks().get(0).getDescription());
	}

}
