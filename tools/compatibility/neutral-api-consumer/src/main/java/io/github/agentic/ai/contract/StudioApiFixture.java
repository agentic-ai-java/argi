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

import java.util.Map;

import io.github.agentic.ai.agent.studio.dto.AgentResumeRequest;
import io.github.agentic.ai.agent.studio.dto.AgentRunRequest;
import io.github.agentic.ai.agent.studio.dto.GraphRunRequest;
import io.github.agentic.ai.agent.studio.dto.Thread;
import io.github.agentic.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.ai.agent.studio.loader.GraphLoader;

final class StudioApiFixture {

	private StudioApiFixture() {
	}

	static void compileOnly(AgentLoader agentLoader, GraphLoader graphLoader) {
		AgentRunRequest agentRunRequest = new AgentRunRequest();
		AgentResumeRequest agentResumeRequest = new AgentResumeRequest();
		GraphRunRequest graphRunRequest = new GraphRunRequest();
		Thread thread = Thread.builder("fixture").appName("agent").userId("user").values(Map.of()).build();

		agentLoader.listAgents();
		graphLoader.listGraphs();
		agentRunRequest.appName = "agent";
		agentRunRequest.threadId = thread.threadId();
		agentResumeRequest.threadId = thread.threadId();
		graphRunRequest.graphName = "graph";
		graphRunRequest.getGraphName();
	}

}
