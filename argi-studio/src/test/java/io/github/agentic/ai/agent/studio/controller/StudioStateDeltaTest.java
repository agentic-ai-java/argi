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
package io.github.agentic.ai.agent.studio.controller;

import io.github.agentic.ai.api.chat.ChatMessage;

import io.github.agentic.ai.agent.studio.dto.AgentResumeRequest;
import io.github.agentic.ai.agent.studio.dto.AgentRunRequest;
import io.github.agentic.ai.agent.studio.dto.messages.UserMessageDTO;
import io.github.agentic.ai.agent.studio.dto.messages.ToolRequestConfirmMessageDTO;
import io.github.agentic.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.KeyStrategyFactoryBuilder;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.agent.Agent;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.exception.GraphStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StudioStateDeltaTest {

	@Test
	void runUsesDeltaAndKeepsTheSubmittedUserMessage() {
		StateReadingAgent agent = new StateReadingAgent(false);
		AgentRunRequest request = runRequest(Map.of("marker", "injected", "input", "not-the-user-message"));
		controller(agent).agentRunSse(request, "secret").collectList().block(Duration.ofSeconds(2));

		assertNotNull(agent.observed);
		assertEquals("injected", agent.observed.get("marker"));
		assertEquals("hello", agent.observed.get("input"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	void runWithoutDeltaKeepsOrdinaryInput(Map<String, Object> delta) {
		StateReadingAgent agent = new StateReadingAgent(false);
		controller(agent).agentRunSse(runRequest(delta), "secret").collectList().block(Duration.ofSeconds(2));

		assertEquals("hello", agent.observed.get("input"));
		assertEquals("absent", agent.getAndCompileGraph().getState(config()).state().value("seen").orElseThrow());
	}

	@Test
	void resumeUsesDeltaBeforeTheNextNode() throws Exception {
		StateReadingAgent agent = pausedAgent();
		AgentResumeRequest request = resumeRequest(Map.of("marker", "updated", "new_config", "added"));
		controller(agent).agentResumeSse(request, "secret").collectList().block(Duration.ofSeconds(2));

		assertNotNull(agent.observed);
		assertEquals("updated", agent.observed.get("marker"));
		assertEquals("added", agent.observed.get("new_config"));
		assertEquals("updated", agent.getAndCompileGraph().getState(config()).state().value("seen").orElseThrow());
	}

	@ParameterizedTest
	@NullAndEmptySource
	void resumeWithoutDeltaKeepsStoredState(Map<String, Object> delta) throws Exception {
		StateReadingAgent agent = pausedAgent();
		controller(agent).agentResumeSse(resumeRequest(delta), "secret").collectList().block(Duration.ofSeconds(2));

		assertEquals("stored", agent.observed.get("marker"));
	}

	@Test
	void resumeWithoutCheckpointKeepsFreshStartBehaviorAndUsesDelta() {
		StateReadingAgent agent = new StateReadingAgent(false);
		controller(agent).agentResumeSse(resumeRequest(Map.of("marker", "fresh")), "secret")
			.collectList()
			.block(Duration.ofSeconds(2));
		assertEquals("fresh", agent.observed.get("marker"));
	}

	@Test
	void resumeDeltaIsAppliedOnlyWhenExecutionIsSubscribed() throws Exception {
		StateReadingAgent agent = pausedAgent();
		var execution = controller(agent).agentResumeSse(resumeRequest(Map.of("marker", "updated")), "secret");
		assertEquals("stored", agent.getAndCompileGraph().getState(config()).state().value("marker").orElseThrow());
		execution.collectList().block(Duration.ofSeconds(2));
		assertEquals("updated", agent.observed.get("marker"));
	}

	@Test
	void resumeDoesNotAutomaticallyApproveMissingFeedbackResult() {
		StateReadingAgent agent = new StateReadingAgent(false);
		var feedback = new ToolRequestConfirmMessageDTO.ToolFeedback();
		feedback.setId("pending");
		feedback.setName("write");
		var request = resumeRequest(null);
		request.toolFeedbacks = List.of(feedback);
		controller(agent).agentResumeSse(request, "secret").collectList().block(Duration.ofSeconds(2));

		assertNotNull(agent.observedFeedback);
		assertNull(agent.observedFeedback.toolFeedbacks().get(0).getResult());
	}

	private static StateReadingAgent pausedAgent() throws Exception {
		StateReadingAgent agent = new StateReadingAgent(true);
		agent.stream(Map.of("marker", "stored", "messages", List.of(ChatMessage.user("seed"))), config())
			.collectList()
			.block(Duration.ofSeconds(2));
		assertNull(agent.observed);
		return agent;
	}

	private static RunnableConfig config() {
		return RunnableConfig.builder()
			.threadId("thread-1")
			.addMetadata(RunnableConfig.APP_NAME_METADATA_KEY, "assistant")
			.addMetadata(RunnableConfig.USER_ID_METADATA_KEY, "alice")
			.build();
	}

	private static AgentRunRequest runRequest(Map<String, Object> delta) {
		AgentRunRequest request = new AgentRunRequest();
		request.appName = "assistant";
		request.userId = "alice";
		request.threadId = "thread-1";
		request.newMessage = new UserMessageDTO("hello");
		request.stateDelta = delta;
		return request;
	}

	private static AgentResumeRequest resumeRequest(Map<String, Object> delta) {
		AgentResumeRequest request = new AgentResumeRequest();
		request.appName = "assistant";
		request.userId = "alice";
		request.threadId = "thread-1";
		request.stateDelta = delta;
		return request;
	}

	private static ExecutionController controller(Agent agent) {
		AgentLoader loader = new AgentLoader() {
			@Override
			public List<String> listAgents() {
				return List.of("assistant");
			}

			@Override
			public Agent loadAgent(String name) {
				assertEquals("assistant", name);
				return agent;
			}
		};
		return new ExecutionController(loader, new StudioExecutionAccess("secret"));
	}

	private static final class StateReadingAgent extends Agent {

		private Map<String, Object> observed;

		private InterruptionMetadata observedFeedback;

		private StateReadingAgent(boolean pause) {
			super("assistant", "State delta test agent");
			CompileConfig.Builder builder = CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(new MemorySaver()).build());
			if (pause) {
				builder.interruptAfter("pause");
			}
			compileConfig = builder.build();
		}

		@Override
		protected StateGraph initGraph() throws GraphStateException {
			return new StateGraph(new KeyStrategyFactoryBuilder().defaultStrategy(KeyStrategy.REPLACE)
				.addStrategy("messages")
				.build()).addNode("pause", node_async(state -> Map.of()))
				.addNode("read",
						io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig.node_async((state, config) -> {
							observed = new HashMap<>(state.data());
							observedFeedback = (InterruptionMetadata) config
								.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY)
								.orElse(null);
							return Map.of("seen", state.value("marker").orElse("absent"));
						}))
				.addEdge(StateGraph.START, "pause")
				.addEdge("pause", "read")
				.addEdge("read", StateGraph.END);
		}

	}

}
