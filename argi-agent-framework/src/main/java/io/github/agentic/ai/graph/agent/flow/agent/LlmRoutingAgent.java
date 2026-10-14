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
package io.github.agentic.ai.graph.agent.flow.agent;

import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.agent.flow.builder.FlowAgentBuilder;
import io.github.agentic.ai.graph.agent.flow.builder.FlowGraphBuilder;
import io.github.agentic.ai.graph.agent.flow.enums.FlowAgentEnum;
import io.github.agentic.ai.graph.exception.GraphStateException;

import io.github.agentic.ai.api.chat.ChatClient;

public class LlmRoutingAgent extends FlowAgent {

	private final ChatClient chatClient;

	private final String fallbackAgent;

	private final String systemPrompt;

	private final String instruction;

	protected LlmRoutingAgent(LlmRoutingAgentBuilder builder) {
		super(builder.name, builder.description, builder.compileConfig, builder.subAgents, builder.stateSerializer,
				builder.executor, builder.hooks);
		this.chatClient = builder.chatClient;
		this.fallbackAgent = builder.fallbackAgent;
		this.systemPrompt = builder.systemPrompt;
		this.instruction = builder.instruction;
	}

	public static LlmRoutingAgentBuilder builder() {
		return new LlmRoutingAgentBuilder();
	}

	public String getFallbackAgent() {
		return fallbackAgent;
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public String getInstruction() {
		return instruction;
	}

	@Override
	protected StateGraph buildSpecificGraph(FlowGraphBuilder.FlowGraphConfig config) throws GraphStateException {
		config.setChatClient(this.chatClient);
		return FlowGraphBuilder.buildGraph(FlowAgentEnum.ROUTING.getType(), config);
	}

	/**
	 * Builder for creating LlmRoutingAgent instances. Extends the common FlowAgentBuilder
	 * and adds LLM-specific configuration.
	 */
	public static class LlmRoutingAgentBuilder extends FlowAgentBuilder<LlmRoutingAgent, LlmRoutingAgentBuilder> {

		private ChatClient chatClient;

		private String fallbackAgent;

		private String systemPrompt;

		private String instruction;

		/**
		 * Sets the provider-neutral client for routing and multi-source synthesis.
		 * @param chatClient the client to use for routing
		 * @return this builder instance for method chaining
		 */
		public LlmRoutingAgentBuilder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public LlmRoutingAgentBuilder fallbackAgent(String fallbackAgent) {
			this.fallbackAgent = fallbackAgent;
			return this;
		}

		public LlmRoutingAgentBuilder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public LlmRoutingAgentBuilder instruction(String instruction) {
			this.instruction = instruction;
			return this;
		}

		@Override
		protected LlmRoutingAgentBuilder self() {
			return this;
		}

		@Override
		protected void validate() {
			super.validate();
			if (chatClient == null) {
				throw new IllegalArgumentException("ChatClient must be provided for LLM routing agent");
			}
		}

		@Override
		public LlmRoutingAgent doBuild() {
			validate();
			return new LlmRoutingAgent(this);
		}

	}

}
