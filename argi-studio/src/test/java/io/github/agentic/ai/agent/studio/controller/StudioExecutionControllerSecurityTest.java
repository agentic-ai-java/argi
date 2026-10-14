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

import io.github.agentic.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.ai.agent.studio.loader.GraphLoader;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.agent.Agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StudioExecutionControllerSecurityTest {

	@Test
	void runSseRejectsAnonymousRequestsByDefaultBeforeLoadingAgent() throws Exception {
		AgentLoader agentLoader = mock(AgentLoader.class);
		Agent agent = mock(Agent.class);
		when(agentLoader.loadAgent("assistant")).thenReturn(agent);
		when(agent.stream(any(io.github.agentic.ai.api.chat.ChatMessage.class), any())).thenReturn(Flux.empty());

		standaloneSetup(new ExecutionController(agentLoader)).build()
			.perform(post("/run_sse").contentType(APPLICATION_JSON).content("""
					{
					  "appName": "assistant",
					  "userId": "alice",
					  "threadId": "thread-1",
					  "newMessage": { "messageType": "user", "content": "hi" }
					}
					"""))
			.andExpect(status().isForbidden());

		verify(agentLoader, never()).loadAgent("assistant");
	}

	@Test
	void runSseRejectsAnonymousRequestsWhenConfiguredTokenIsEmptyBeforeLoadingAgent() throws Exception {
		AgentLoader agentLoader = mock(AgentLoader.class);
		Agent agent = mock(Agent.class);
		when(agentLoader.loadAgent("assistant")).thenReturn(agent);
		when(agent.stream(any(io.github.agentic.ai.api.chat.ChatMessage.class), any())).thenReturn(Flux.empty());

		standaloneSetup(new ExecutionController(agentLoader, new StudioExecutionAccess(""))).build()
			.perform(post("/run_sse").header(StudioExecutionAccess.TOKEN_HEADER, "secret")
				.contentType(APPLICATION_JSON)
				.content("""
						{
						  "appName": "assistant",
						  "userId": "alice",
						  "threadId": "thread-1",
						  "newMessage": { "messageType": "user", "content": "hi" }
						}
						"""))
			.andExpect(status().isForbidden());

		verify(agentLoader, never()).loadAgent("assistant");
	}

	@Test
	void runSseAllowsRequestsWhenConfiguredTokenMatches() throws Exception {
		AgentLoader agentLoader = mock(AgentLoader.class);
		Agent agent = mock(Agent.class);
		when(agentLoader.loadAgent("assistant")).thenReturn(agent);
		when(agent.stream(any(io.github.agentic.ai.api.chat.ChatMessage.class), any())).thenReturn(Flux.empty());

		standaloneSetup(new ExecutionController(agentLoader, new StudioExecutionAccess("secret"))).build()
			.perform(post("/run_sse").header(StudioExecutionAccess.TOKEN_HEADER, "secret")
				.contentType(APPLICATION_JSON)
				.content("""
						{
						  "appName": "assistant",
						  "userId": "alice",
						  "threadId": "thread-1",
						  "newMessage": { "messageType": "user", "content": "hi" }
						}
						"""))
			.andExpect(status().isOk());

		verify(agentLoader).loadAgent("assistant");
	}

	@Test
	void resumeSseRejectsAnonymousRequestsByDefaultBeforeLoadingAgent() throws Exception {
		AgentLoader agentLoader = mock(AgentLoader.class);

		standaloneSetup(new ExecutionController(agentLoader)).build()
			.perform(post("/resume_sse").contentType(APPLICATION_JSON).content("""
					{
					  "appName": "assistant",
					  "userId": "alice",
					  "threadId": "thread-1"
					}
					"""))
			.andExpect(status().isForbidden());

		verify(agentLoader, never()).loadAgent("assistant");
	}

	@Test
	void graphRunSseRejectsAnonymousRequestsByDefaultBeforeLoadingGraph() throws Exception {
		GraphLoader graphLoader = mock(GraphLoader.class);
		CompiledGraph graph = mock(CompiledGraph.class);
		when(graphLoader.loadGraph("research")).thenReturn(graph);
		when(graph.stream(any(Map.class), any())).thenReturn(Flux.empty());

		standaloneSetup(new GraphExecutionController(graphLoader)).build()
			.perform(post("/graph_run_sse").contentType(APPLICATION_JSON).content("""
					{
					  "graphName": "research",
					  "userId": "alice",
					  "threadId": "thread-1",
					  "newMessage": { "messageType": "user", "content": "hi" }
					}
					"""))
			.andExpect(status().isForbidden());

		verify(graphLoader, never()).loadGraph(eq("research"));
	}

	@Test
	void graphRunSseAllowsRequestsWhenConfiguredTokenMatches() throws Exception {
		GraphLoader graphLoader = mock(GraphLoader.class);
		CompiledGraph graph = mock(CompiledGraph.class);
		when(graphLoader.loadGraph("research")).thenReturn(graph);
		when(graph.stream(any(Map.class), any())).thenReturn(Flux.empty());

		standaloneSetup(new GraphExecutionController(graphLoader, new StudioExecutionAccess("secret"))).build()
			.perform(post("/graph_run_sse").header(StudioExecutionAccess.TOKEN_HEADER, "secret")
				.contentType(APPLICATION_JSON)
				.content("""
						{
						  "graphName": "research",
						  "userId": "alice",
						  "threadId": "thread-1",
						  "newMessage": { "messageType": "user", "content": "hi" }
						}
						"""))
			.andExpect(status().isOk());

		verify(graphLoader).loadGraph(eq("research"));
	}

}
