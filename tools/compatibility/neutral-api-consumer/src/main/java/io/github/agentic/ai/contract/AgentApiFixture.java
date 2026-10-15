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
package io.github.agentic.ai.contract;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.flow.agent.LlmRoutingAgent;
import io.github.agentic.ai.graph.agent.flow.agent.LoopAgent;
import io.github.agentic.ai.graph.agent.flow.agent.ParallelAgent;
import io.github.agentic.ai.graph.agent.flow.agent.SequentialAgent;
import io.github.agentic.ai.graph.agent.flow.agent.loop.LoopMode;
import io.github.agentic.ai.graph.agent.hook.AgentHook;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.tools.task.BoundedTaskRepository;
import io.github.agentic.ai.graph.agent.tools.task.TaskToolsBuilder;

import io.github.agentic.ai.api.chat.ChatClient;

final class AgentApiFixture {

	private AgentApiFixture() {
	}

	static void compileOnly(ChatClient chatClient) throws Exception {
		SampleAgentHook hook = new SampleAgentHook();
		SampleModelInterceptor modelInterceptor = new SampleModelInterceptor();
		SampleToolInterceptor toolInterceptor = new SampleToolInterceptor();
		RunnableConfig config = RunnableConfig.builder().threadId("agent-fixture").build();

		ReactAgent reactAgent = ReactAgent.builder()
			.name("react")
			.description("compile fixture")
			.instruction("respond")
			.chatClient(chatClient)
			.hooks(hook)
			.interceptors(modelInterceptor, toolInterceptor)
			.build();

		SequentialAgent sequentialAgent = SequentialAgent.builder()
			.name("sequential")
			.description("compile fixture")
			.subAgents(List.of(reactAgent))
			.hooks(hook)
			.build();
		ParallelAgent parallelAgent = ParallelAgent.builder()
			.name("parallel")
			.description("compile fixture")
			.subAgents(List.of(reactAgent))
			.hooks(hook)
			.build();
		LoopAgent loopAgent = LoopAgent.builder()
			.name("loop")
			.description("compile fixture")
			.subAgent(reactAgent)
			.loopStrategy(LoopMode.count(1))
			.hooks(hook)
			.build();
		LlmRoutingAgent routingAgent = LlmRoutingAgent.builder()
			.name("routing")
			.description("compile fixture")
			.chatClient(chatClient)
			.subAgents(List.of(reactAgent))
			.fallbackAgent("react")
			.systemPrompt("route")
			.instruction("choose")
			.hooks(hook)
			.build();

		reactAgent.call("hello", config);
		reactAgent.invoke(Map.of("messages", "hello"), config);
		reactAgent.stream("hello", config);
		reactAgent.asNode();
		sequentialAgent.invoke(Map.of("messages", "hello"), config);
		parallelAgent.stream(Map.of("messages", "hello"), config);
		loopAgent.invoke(Map.of("messages", "hello"), config);
		routingAgent.invoke(Map.of("messages", "hello"), config);
	}

	static void boundedTasks(ReactAgent agent, ExecutorService callerExecutor) {
		try (BoundedTaskRepository owned = new BoundedTaskRepository(2, 4, 8, Duration.ofMinutes(5));
				BoundedTaskRepository borrowed = new BoundedTaskRepository(callerExecutor, 2, 4, 8,
						Duration.ofMinutes(5))) {
			TaskToolsBuilder.builder().taskRepository(owned).subAgent("worker", agent).build();
			borrowed.putTask("task", () -> "result");
			borrowed.getTask("task");
			borrowed.removeTask("task");
			borrowed.clear();
		}
	}

	static final class SampleAgentHook extends AgentHook {

		@Override
		public String getName() {
			return "sample-agent-hook";
		}

		@Override
		public Map<String, io.github.agentic.ai.graph.KeyStrategy> getKeyStrategys() {
			return Map.of("messages", io.github.agentic.ai.graph.KeyStrategy.APPEND);
		}

	}

	static final class SampleModelInterceptor extends ModelInterceptor {

		@Override
		public String getName() {
			return "sample-model-interceptor";
		}

		@Override
		public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
			return handler.call(request);
		}

	}

	static final class SampleToolInterceptor extends ToolInterceptor {

		@Override
		public String getName() {
			return "sample-tool-interceptor";
		}

		@Override
		public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
			return handler.call(request);
		}

	}

	static OverAllState stateReference(ReactAgent agent, RunnableConfig config) throws Exception {
		return agent.invoke("state", config).orElseThrow();
	}

}
