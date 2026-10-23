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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptionMetadataTest {

	@Test
	void builderCopiesPreserveFeedbackAndNeutralAutomaticApprovals() {
		var call = new ToolCall("automatic", "read", "{}");
		var feedback = ToolFeedback.builder()
			.id("pending")
			.name("write")
			.arguments("{}")
			.result(ToolFeedback.FeedbackResult.EDITED)
			.description("Reviewed")
			.build();
		var state = new OverAllState(Map.of());
		var original = InterruptionMetadata.builder("approval", state)
			.addMetadata("source", "review")
			.addToolFeedback(feedback)
			.addToolsAutomaticallyApproved(call)
			.build();
		var copy = InterruptionMetadata.builder(original).build();

		assertEquals("approval", copy.node());
		assertEquals(state, copy.state());
		assertEquals("review", copy.metadata("source").orElseThrow());
		assertEquals(List.of(call), copy.getToolsAutomaticallyApproved());
		assertEquals(List.of(feedback), copy.toolFeedbacks());
	}

	@Test
	void publishedListsCannotBeChangedByCallersOrBuilderReuse() {
		var call = new ToolCall("automatic", "read", "{}");
		var feedback = ToolFeedback.builder().id("pending").name("write").build();
		var builder = InterruptionMetadata.builder().addToolFeedback(feedback).addToolsAutomaticallyApproved(call);
		var first = builder.build();
		builder.addToolFeedback(feedback).addToolsAutomaticallyApproved(call);

		assertEquals(1, first.toolFeedbacks().size());
		assertEquals(1, first.getToolsAutomaticallyApproved().size());
		assertThrows(UnsupportedOperationException.class, () -> first.toolFeedbacks().clear());
		assertThrows(UnsupportedOperationException.class, () -> first.getToolsAutomaticallyApproved().clear());
		assertTrue(InterruptionMetadata.builder().build().toolFeedbacks().isEmpty());
		assertTrue(InterruptionMetadata.builder().build().getToolsAutomaticallyApproved().isEmpty());
	}

}
