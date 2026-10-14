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
package io.github.agentic.ai.graph.agent.hook.returndirect;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.Prioritized;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.agent.internal.JsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectConstants.FINISH_REASON_METADATA_KEY;
import static io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectConstants.FINISH_REASON;

/** Returns a completed direct-tool turn without another model call, retaining media. */
@HookPositions({ HookPosition.BEFORE_MODEL })
public class ReturnDirectModelHook extends MessagesModelHook {

	@Override
	public String getName() {
		return "finish_reason_check_messages_model_hook";
	}

	@Override
	public int getOrder() {
		return Prioritized.HIGHEST_PRECEDENCE;
	}

	@Override
	public List<JumpTo> canJumpTo() {
		return List.of(JumpTo.end);
	}

	@Override
	public AgentCommand beforeModel(List<ChatMessage> previousMessages, RunnableConfig config) {
		if (previousMessages.isEmpty()) {
			return new AgentCommand(previousMessages);
		}
		ChatMessage last = previousMessages.get(previousMessages.size() - 1);
		if (last.role() != ChatRole.TOOL || !FINISH_REASON.equals(last.metadata().get(FINISH_REASON_METADATA_KEY))) {
			return new AgentCommand(previousMessages);
		}
		int start = previousMessages.size() - 1;
		while (start > 0 && previousMessages.get(start - 1).role() == ChatRole.TOOL) {
			start--;
		}
		List<ChatMessage> results = previousMessages.subList(start, previousMessages.size());
		List<ContentPart> content = new ArrayList<>();
		if (results.size() == 1) {
			content.addAll(last.content());
		}
		else {
			List<Object> values = new ArrayList<>();
			JsonCodec codec = new JsonCodec();
			for (ChatMessage result : results) {
				try {
					values.add(codec.fromJson(result.text(), Object.class));
				}
				catch (IllegalArgumentException exception) {
					values.add(result.text());
				}
			}
			content.add(ContentPart.json(codec.toJson(values)));
			results.stream()
				.flatMap(result -> result.content().stream())
				.filter(part -> part.type() != ContentPart.Type.TEXT && part.type() != ContentPart.Type.JSON)
				.forEach(content::add);
		}
		List<ChatMessage> messages = new ArrayList<>(previousMessages);
		messages.add(new ChatMessage(ChatRole.ASSISTANT, content, List.of(), null, null, Map.of()));
		return new AgentCommand(JumpTo.end, messages);
	}

}
