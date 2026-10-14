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
package io.github.agentic.ai.graph.agent.hooks.hip;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import io.github.agentic.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline approval validation independent of any remote model provider. */
class HumanInTheLoopHookTest {

	private final HumanInTheLoopHook hook = HumanInTheLoopHook.builder().approvalOn("write", "Changes data").build();

	@Test
	void interruptionPublishesNeutralAutomaticApprovalsAndPendingFeedback() {
		var interruption = hook.interrupt("approval", state(), RunnableConfig.builder().build()).orElseThrow();
		assertEquals(List.of(new ToolCall("read_1", "read", "{}")), interruption.getToolsAutomaticallyApproved());
		assertEquals(1, interruption.toolFeedbacks().size());
		assertEquals("write_1", interruption.toolFeedbacks().get(0).getId());
		assertNull(interruption.toolFeedbacks().get(0).getResult());
	}

	@Test
	void approvalPreservesCallsAndBindingSnapshot() {
		var config = config(List.of(feedback("write_1", "write", FeedbackResult.APPROVED, null)));
		assertTrue(hook.interrupt("approval", state(), config).isEmpty());
		var messages = updates(config);
		var approved = (ChatMessage) messages.get(0);
		assertEquals(2, approved.toolCalls().size());
		assertEquals("snapshot", approved.metadata().get("argi.toolBindingSnapshot"));
		assertFalse(messages.stream()
			.anyMatch(message -> message instanceof ChatMessage chat && chat.role() == ChatRole.TOOL));
	}

	@Test
	void editedArgumentsKeepCallIdentityAndUnreviewedAutomaticCall() {
		var messages = updates(config(List.of(feedback("write_1", "write", FeedbackResult.EDITED, "{\"value\":42}"))));
		var edited = (ChatMessage) messages.get(0);
		assertEquals("{\"value\":42}", edited.toolCalls().get(0).arguments());
		assertEquals("write_1", edited.toolCalls().get(0).id());
		assertEquals("write", edited.toolCalls().get(0).name());
		assertEquals("{}", edited.toolCalls().get(1).arguments());
		assertEquals("snapshot", edited.metadata().get("argi.toolBindingSnapshot"));
	}

	@Test
	void rejectionProducesPairedResponseAfterTheAssistant() {
		var messages = updates(config(List.of(feedback("write_1", "write", FeedbackResult.REJECTED, null))));
		assertTrue(messages.get(0) instanceof ChatMessage assistant && assistant.role() == ChatRole.ASSISTANT);
		var result = (ChatMessage) messages.get(messages.size() - 1);
		assertEquals(ChatRole.TOOL, result.role());
		assertEquals("write_1", result.toolCallId());
		assertTrue(result.text().contains("rejected by human"));
	}

	static Stream<List<ToolFeedback>> invalidFeedback() {
		var approved = feedback("write_1", "write", FeedbackResult.APPROVED, null);
		return Stream.of(List.of(), List.of(feedback("write_1", "write", null, null)),
				List.of(feedback("write_1", "wrong", FeedbackResult.APPROVED, null)),
				List.of(feedback("wrong", "write", FeedbackResult.APPROVED, null)),
				List.of(feedback(null, null, FeedbackResult.APPROVED, null)), List.of(approved, approved),
				List.of(approved, feedback("read_1", "read", FeedbackResult.EDITED, "{}")),
				List.of(feedback("write_1", "write", FeedbackResult.EDITED, null)),
				List.of(feedback("write_1", "write", FeedbackResult.EDITED, " ")));
	}

	@ParameterizedTest
	@MethodSource("invalidFeedback")
	void incompleteOrMismatchedFeedbackCannotBypassApproval(List<ToolFeedback> feedbacks) {
		assertTrue(hook.interrupt("approval", state(), config(feedbacks)).isPresent());
		assertThrows(IllegalArgumentException.class, () -> updates(config(feedbacks)));
	}

	@Test
	void everyPendingCallRequiresItsOwnFeedback() {
		var assistant = ChatMessage.builder(ChatRole.ASSISTANT)
			.text("")
			.toolCalls(List.of(new ToolCall("write_1", "write", "{}"), new ToolCall("write_2", "write", "{}")))
			.build();
		var state = new OverAllState(Map.of("messages", List.of(assistant)));
		var config = config(List.of(feedback("write_1", "write", FeedbackResult.APPROVED, null)));
		assertTrue(hook.interrupt("approval", state, config).isPresent());
		assertThrows(IllegalArgumentException.class, () -> hook.afterModel(state, config));
	}

	private List<?> updates(RunnableConfig config) {
		return (List<?>) hook.afterModel(state(), config).join().get("messages");
	}

	private static OverAllState state() {
		var assistant = ChatMessage.builder(ChatRole.ASSISTANT)
			.text("")
			.metadata(Map.of("argi.toolBindingSnapshot", "snapshot"))
			.toolCalls(List.of(new ToolCall("write_1", "write", "{}"), new ToolCall("read_1", "read", "{}")))
			.build();
		return new OverAllState(Map.of("messages", List.of(assistant)));
	}

	private static RunnableConfig config(List<ToolFeedback> feedbacks) {
		return RunnableConfig.builder()
			.addHumanFeedback(InterruptionMetadata.builder().toolFeedbacks(feedbacks).build())
			.build();
	}

	private static ToolFeedback feedback(String id, String name, FeedbackResult result, String arguments) {
		return ToolFeedback.builder()
			.id(id)
			.name(name)
			.result(result)
			.arguments(arguments)
			.description("Reviewed")
			.build();
	}

}
