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
package io.github.agentic.ai.agent.orchestration.a2a;

import io.github.agentic.ai.workflow.graph.CompileConfig;
import io.github.agentic.ai.workflow.graph.CompiledGraph;
import io.github.agentic.ai.workflow.graph.KeyStrategy;
import io.github.agentic.ai.workflow.graph.KeyStrategyFactory;
import io.github.agentic.ai.workflow.graph.StateGraph;
import io.github.agentic.ai.workflow.graph.SubGraphNode;
import io.github.agentic.ai.workflow.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.agent.orchestration.BaseAgent;
import io.github.agentic.ai.workflow.graph.exception.GraphStateException;
import io.github.agentic.ai.workflow.graph.internal.node.Node;
import io.github.agentic.ai.workflow.graph.scheduling.ScheduleConfig;
import io.github.agentic.ai.workflow.graph.scheduling.ScheduledAgentTask;
import io.github.agentic.ai.workflow.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTracer;
import io.a2a.spec.AgentCard;

import java.time.Duration;
import java.util.HashMap;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class A2aRemoteAgent extends BaseAgent {
	private static final Logger logger = LoggerFactory.getLogger(A2aRemoteAgent.class);

	private final AgentCardWrapper agentCard;

	private KeyStrategyFactory keyStrategyFactory;

	private String instruction;

	private boolean streaming;

	private boolean shareState;

	private AgentTracer agentTracer;

	private String userIdMetadataKey;

	private Duration streamDemandTimeout = A2aNodeActionWithConfig.DEFAULT_STREAM_DEMAND_TIMEOUT;

	// Private constructor for Builder pattern
	private A2aRemoteAgent(Builder builder) {
		super(builder.name, builder.description, builder.includeContents, builder.returnReasoningContents, builder.outputKey, builder.outputKeyStrategy);
		this.agentCard = builder.agentCard;
		this.keyStrategyFactory = builder.keyStrategyFactory;
		this.compileConfig = builder.compileConfig;
		this.includeContents = builder.includeContents;
		this.streaming = builder.streaming;
		this.instruction = builder.instruction;
		this.shareState = builder.shareState;
		this.agentTracer = builder.agentTracer;
		this.userIdMetadataKey = builder.userIdMetadataKey;
		this.streamDemandTimeout = builder.streamDemandTimeout;
	}

	@Override
	protected StateGraph initGraph() throws GraphStateException {
		if (keyStrategyFactory == null) {
			this.keyStrategyFactory = () -> {
				HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
				keyStrategyHashMap.put("messages", new AppendStrategy());
				return keyStrategyHashMap;
			};
		}

		StateGraph graph = new StateGraph(name, this.keyStrategyFactory);
		graph.addNode("A2aNode", AsyncNodeActionWithConfig.node_async(newNodeAction(agentCard, name, includeContents,
				outputKey, instruction, streaming, this.shareState, this.compileConfig, this.agentTracer)));
		graph.addEdge(StateGraph.START, "A2aNode");
		graph.addEdge("A2aNode", StateGraph.END);
		return graph;
	}

	/**
	 * Single place that applies the agent-level node options, so the initGraph and asNode
	 * construction sites cannot drift apart on userId or demand-timeout wiring.
	 */
	private A2aNodeActionWithConfig newNodeAction(AgentCardWrapper agentCard, String agentName, boolean includeContents,
			String outputKeyToParent, String instruction, boolean streaming, boolean shareState,
			CompileConfig compileConfig, AgentTracer agentTracer) {
		return new A2aNodeActionWithConfig(agentCard, agentName, includeContents, outputKeyToParent, instruction,
				streaming, shareState, compileConfig, agentTracer).userIdMetadataKey(this.userIdMetadataKey)
			.streamDemandTimeout(this.streamDemandTimeout);
	}

	@Override
	public ScheduledAgentTask schedule(ScheduleConfig scheduleConfig) {
		throw new UnsupportedOperationException("A2aRemoteAgent has not support schedule.");
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Node asNode(boolean includeContents, boolean returnReasoningContents) {
		return new A2aRemoteAgentNode(this.name, includeContents, returnReasoningContents, this.instruction, this.agentCard, this.streaming, this.shareState, this.getAndCompileGraph());
	}

	/**
	 * Internal class that adapts an A2aRemoteAgent to be used as a Node.
	 * Similar to AgentSubGraphNode but uses A2aNodeActionWithConfig internally.
	 * Implements SubGraphNode interface to provide subgraph functionality.
	 */
	private class A2aRemoteAgentNode extends Node implements SubGraphNode {

		private final CompiledGraph subGraph;

		public A2aRemoteAgentNode(String id, boolean includeContents, boolean returnReasoningContents, String instruction, AgentCardWrapper agentCard, boolean streaming, boolean shareState, CompiledGraph subGraph) {
			super(Objects.requireNonNull(id, "id cannot be null"),
					(config) -> AsyncNodeActionWithConfig.node_async(newNodeAction(agentCard,
						subGraph.stateGraph.getName(), includeContents, A2aRemoteAgent.this.outputKey, instruction,
						streaming, shareState, config, A2aRemoteAgent.this.agentTracer)));
			this.subGraph = subGraph;
		}

		@Override
		public StateGraph subGraph() {
			return subGraph.stateGraph;
		}
	}

	public static class Builder {

		// BaseAgent properties
		private String name;

		private String description;

		private String instruction;

		private String outputKey = "output";

		private KeyStrategy outputKeyStrategy;

		private boolean returnReasoningContents = false;

		// A2aRemoteAgent specific properties
		private AgentCardWrapper agentCard;

		private AgentCardProvider agentCardProvider;

		private boolean includeContents = true;

		private KeyStrategyFactory keyStrategyFactory;

		private CompileConfig compileConfig;

		private boolean streaming = false;

		private boolean shareState = true;

		private AgentTracer agentTracer;

		private String userIdMetadataKey = A2aNodeActionWithConfig.DEFAULT_USER_ID_METADATA_KEY;

		private Duration streamDemandTimeout = A2aNodeActionWithConfig.DEFAULT_STREAM_DEMAND_TIMEOUT;

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder instruction(String instruction) {
			this.instruction = instruction;
			return this;
		}

		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		public Builder outputKeyStrategy(KeyStrategy outputKeyStrategy) {
			this.outputKeyStrategy = outputKeyStrategy;
			return this;
		}

		public Builder returnReasoningContents(boolean returnReasoningContents) {
			this.returnReasoningContents = returnReasoningContents;
			return this;
		}

		public Builder agentCard(AgentCard agentCard) {
			this.agentCard = new AgentCardWrapper(agentCard);
			return this;
		}

		public Builder agentCardProvider(AgentCardProvider agentCardProvider) {
			this.agentCardProvider = agentCardProvider;
			return this;
		}

		public Builder includeContents(boolean includeContents) {
			this.includeContents = includeContents;
			return this;
		}

		public Builder state(KeyStrategyFactory keyStrategyFactory) {
			this.keyStrategyFactory = keyStrategyFactory;
			return this;
		}

		public Builder compileConfig(CompileConfig compileConfig) {
			this.compileConfig = compileConfig;
			return this;
		}

		/**
		 * Enable cross-agent trace correlation for the remote calls with the given tracer.
		 * The tracer is an explicit opt-in; when omitted, no observation is emitted and no
		 * trace metadata is added to the A2A requests.
		 * @param agentTracer the cross-agent tracer, may be null
		 * @return this builder
		 */
		public Builder agentTracer(AgentTracer agentTracer) {
			this.agentTracer = agentTracer;
			return this;
		}

		public Builder streaming(boolean streaming) {
			this.streaming = streaming;
			return this;
		}

		public Builder shareState(boolean shareState) {
			this.shareState = shareState;
			return this;
		}

		/**
		 * Select the run metadata key whose value is sent to the remote agent as the A2A
		 * {@code userId} metadata field. Defaults to {@code userId}.
		 * @param userIdMetadataKey the metadata key
		 * @return this builder
		 */
		public Builder userIdMetadataKey(String userIdMetadataKey) {
			this.userIdMetadataKey = userIdMetadataKey;
			return this;
		}

		/**
		 * Bound how long a streaming call waits for the subscriber to request the next
		 * event before failing with {@link A2aBackpressureException}. Defaults to
		 * {@link A2aNodeActionWithConfig#DEFAULT_STREAM_DEMAND_TIMEOUT};
		 * {@link Duration#ZERO} waits indefinitely.
		 * @param streamDemandTimeout the maximum wait for downstream demand
		 * @return this builder
		 */
		public Builder streamDemandTimeout(Duration streamDemandTimeout) {
			Objects.requireNonNull(streamDemandTimeout, "streamDemandTimeout must not be null");
			if (streamDemandTimeout.isNegative()) {
				throw new IllegalArgumentException("streamDemandTimeout must not be negative");
			}
			this.streamDemandTimeout = streamDemandTimeout;
			return this;
		}

		public A2aRemoteAgent build() {
			// Validation
			if (name == null || name.trim().isEmpty()) {
				throw new IllegalArgumentException("Name must be provided");
			}
			if (description == null || description.trim().isEmpty()) {
				throw new IllegalArgumentException("Description must be provided");
			}
			if (agentCard == null) {
				if (null == agentCardProvider) {
					throw new IllegalArgumentException("AgentCard or AgentCardProvider must be provided");
				}
				if (agentCardProvider.supportGetAgentCardByName()) {
					agentCard = agentCardProvider.getAgentCard(name);
				}
				else {
					agentCard = agentCardProvider.getAgentCard();
				}
			}

			this.streaming = agentCard.capabilities().streaming();

			return new A2aRemoteAgent(this);
		}

	}
}
