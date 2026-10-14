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
package io.github.agentic.ai.graph.agent.hook;

import io.github.agentic.ai.api.chat.ChatMessage;

import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesAgentHook;
import io.github.agentic.ai.graph.serializer.AgentInstructionMessage;
import io.github.agentic.ai.graph.RunnableConfig;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MessagesAgentHook that injects the ReactAgent's instruction into messages before each
 * agent run.
 * <p>
 * When this hook is active, it runs at {@link HookPosition#BEFORE_AGENT} and reads
 * {@link ReactAgent#instruction()} from {@link #getAgent()}. If the instruction is
 * non-empty, it appends an {@link AgentInstructionMessage} to the given messages and
 * returns an {@link AgentCommand} with
 * {@link io.github.agentic.ai.graph.agent.hook.messages.UpdatePolicy#REPLACE}. This
 * allows ReactAgent to avoid adding instruction again in the subgraph adapter when used
 * as a subgraph node.
 * <p>
 * The hook is idempotent: a copy of this agent's own instruction is never duplicated. A
 * copy is considered stale when it carries this agent's {@link #AGENT_NAME_METADATA_KEY}
 * metadata (tagged by this hook; the tag survives template rendering and checkpoint
 * serialization), or when it is an untagged copy with the exact same text (e.g. injected
 * manually by a caller). Copies belonging to other agents are left untouched, so exactly
 * one copy of the current instruction stays in the model context (issue #77).
 * <p>
 * This hook is added by default in ReactAgent when no other hook is an
 * InstructionAgentHook. It runs first among beforeAgent hooks (lowest order).
 */
@HookPositions(HookPosition.BEFORE_AGENT)
public class InstructionAgentHook extends MessagesAgentHook {

	/**
	 * Metadata key that marks an {@link AgentInstructionMessage} with the name of the
	 * agent that injected it, so this hook can recognize its own copies even after
	 * template rendering has changed their text.
	 */
	public static final String AGENT_NAME_METADATA_KEY = "instructionAgentName";

	private ReactAgent reactAgent;

	@Override
	public AgentCommand beforeAgent(List<ChatMessage> previousMessages, RunnableConfig config) {
		if (reactAgent == null) {
			return new AgentCommand(previousMessages);
		}
		String instruction = reactAgent.instruction();
		if (!StringUtils.hasLength(instruction)) {
			return new AgentCommand(previousMessages);
		}
		// Idempotent injection: keep exactly one copy of the current instruction even
		// when
		// a caller already placed it into the messages (see issue #77).
		List<ChatMessage> newMessages = new ArrayList<>();
		for (ChatMessage message : previousMessages) {
			if ((AgentInstructionMessage.isTemplate(message) || AgentInstructionMessage.isRendered(message))
					&& isOwnStaleCopy(message, instruction)) {
				continue;
			}
			newMessages.add(message);
		}
		newMessages.add(AgentInstructionMessage.builder()
			.text(instruction)
			.metadata(Map.of(AGENT_NAME_METADATA_KEY, reactAgent.name()))
			.build());
		return new AgentCommand(newMessages);
	}

	private boolean isOwnStaleCopy(ChatMessage existing, String instruction) {
		Map<String, Object> metadata = existing.metadata();
		if (metadata != null && metadata.containsKey(AGENT_NAME_METADATA_KEY)) {
			// Explicit ownership: an instruction attributed to another agent is never
			// stale for this one, even when the rendered text is identical — dropping
			// it would silently erase the other agent's instruction from a shared
			// message history.
			return reactAgent.name().equals(metadata.get(AGENT_NAME_METADATA_KEY));
		}
		// Legacy copies carry no ownership marker; text equality is the only signal.
		return instruction.equals(existing.text());
	}

	@Override
	public String getName() {
		return "InstructionAgentHook";
	}

	@Override
	public int getOrder() {
		return -100;
	}

	@Override
	public ReactAgent getAgent() {
		return reactAgent;
	}

	@Override
	public void setAgent(ReactAgent agent) {
		this.reactAgent = agent;
	}

	/**
	 * Create the default InstructionAgentHook instance (used when no other hook handles
	 * instruction).
	 * @return a new InstructionAgentHook
	 */
	public static InstructionAgentHook create() {
		return new InstructionAgentHook();
	}

}
