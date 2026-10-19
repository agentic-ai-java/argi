/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.hook.messages;

import java.util.List;
import java.util.Map;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyAction;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyGate;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyKeys;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyModelHook;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyStage;
import io.github.agentic.ai.graph.agent.hook.contentsafety.LiteralContentDetector;
import io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectConstants;
import io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectModelHook;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.contentsafety.ContentSafetyToolInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSafetyModelHookTest {

	private static final String SECRET = "SECRET-TOKEN";

	@Test
	void finalAssistantAndChildResultAreChecked() {
		ContentSafetyModelHook hook = hook(ContentSafetyAction.BLOCK, ContentSafetyStage.FINAL_OUTPUT,
				ContentSafetyStage.CHILD_RESULT, ContentSafetyStage.REQUEST_INGRESS);
		RunnableConfig config = RunnableConfig.builder().build();

		AgentCommand ingress = hook.beforeModel(List.of(ChatMessage.user("hello " + SECRET)), config);
		assertFalse(ingress.getMessages().get(0).text().contains(SECRET));

		AgentCommand child = hook.beforeModel(
				List.of(ChatMessage.assistant("child " + SECRET).withMetadata(Map.of(ContentSafetyKeys.CHILD_RESULT, true))),
				config);
		assertEquals(ContentSafetyStage.CHILD_RESULT.name(),
				child.getMessages().get(0).metadata().get(ContentSafetyKeys.STAGE));
		assertFalse(child.getMessages().get(0).text().contains(SECRET));

		AgentCommand fin = hook.afterModel(
				List.of(ChatMessage.assistant("answer " + SECRET, List.of())), config);
		assertFalse(fin.getMessages().get(0).text().contains(SECRET));
	}

	@Test
	void assistantToolArgumentsAreRemovedBeforeDispatch() {
		ContentSafetyModelHook hook = hook(ContentSafetyAction.BLOCK, ContentSafetyStage.TOOL_ARGS,
				ContentSafetyStage.FINAL_OUTPUT);
		ChatMessage assistant = ChatMessage.assistant("clean")
			.withToolCalls(List.of(new ToolCall("call-1", "lookup", "{\"q\":\"" + SECRET + "\"}")));

		AgentCommand command = hook.afterModel(List.of(assistant), RunnableConfig.builder().build());

		assertTrue(command.getMessages().get(0).toolCalls().isEmpty());
		assertFalse(command.getMessages().get(0).text().contains(SECRET));
	}

	@Test
	void returnDirectCopiesTheCheckedToolResult() {
		ContentSafetyToolInterceptor interceptor = new ContentSafetyToolInterceptor(ContentSafetyGate.builder()
			.stage(ContentSafetyStage.RETURN_DIRECT)
			.onMatch(ContentSafetyAction.REDACT)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.build());
		ToolCallResponse response = interceptor.interceptToolCall(
				io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest.builder()
					.toolName("lookup")
					.toolCallId("call-1")
					.arguments("{}")
					.build(),
				request -> new ToolCallResponse("see " + SECRET, request.getToolName(), request.getToolCallId(),
						ToolCallResponse.SUCCESS_STATUS,
						Map.of(ReturnDirectConstants.FINISH_REASON_METADATA_KEY, ReturnDirectConstants.FINISH_REASON)));
		AgentCommand command = new ReturnDirectModelHook().beforeModel(List.of(ChatMessage.tool("call-1", "lookup", response.getResult())
			.withMetadata(Map.of(ReturnDirectConstants.FINISH_REASON_METADATA_KEY, ReturnDirectConstants.FINISH_REASON))),
				RunnableConfig.builder().build());

		String rendered = command.getMessages().get(command.getMessages().size() - 1).text();
		assertFalse(rendered.contains(SECRET));
		assertTrue(rendered.contains("[REDACTED]"));
	}

	private static ContentSafetyModelHook hook(ContentSafetyAction action, ContentSafetyStage... stages) {
		return new ContentSafetyModelHook(ContentSafetyGate.builder()
			.stages(stages)
			.onMatch(action)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.build());
	}

}
