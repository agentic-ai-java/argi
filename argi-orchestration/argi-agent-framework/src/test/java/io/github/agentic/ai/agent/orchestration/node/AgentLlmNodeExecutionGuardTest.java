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
package io.github.agentic.ai.agent.orchestration.node;

import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证框架节点把运行守卫传给模型请求，使重试序列能被运行本身终止。
 */
@Timeout(20)
class AgentLlmNodeExecutionGuardTest {

	/** 验证进入模型调用链的模型请求携带当前运行的执行守卫。 */
	@Test
	void theModelRequestCarriesTheGuardOfTheRun() throws Exception {
		ChatModel model = mock(ChatModel.class);
		when(model.getOptions()).thenReturn(ChatOptions.builder().build());
		when(model.call(any(Prompt.class))).thenReturn(response("done"));
		AtomicReference<ExecutionControl> captured = new AtomicReference<>();
		ModelInterceptor interceptor = new ModelInterceptor() {
			@Override
			public String getName() {
				return "capture-guard";
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
				captured.set((ExecutionControl) request.getExecutionGuard().orElse(null));
				return handler.call(request);
			}
		};
		AgentLlmNode node = AgentLlmNode.builder()
			.agentName("test")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(ChatClient.create(model)))
			.modelInterceptors(List.of(interceptor))
			.build();
		ExecutionControl control = control();

		apply(node, control);

		assertThat(captured.get()).isSameAs(control);
	}

	private static Object apply(AgentLlmNode node, ExecutionControl control) throws Exception {
		OverAllState state = new OverAllState(Map.of("messages", new ArrayList<>(List.of(ChatMessage.user("test")))));
		RunnableConfig config = RunnableConfig.builder()
			.addMetadata("_stream_", false)
			.executionGuard(control)
			.build();
		return node.apply(state, config).get("messages");
	}

	private static ExecutionControl control() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("guard-test")
			.correlationId("guard-correlation")
			.deadline(Instant.now().plusSeconds(60))
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new ExecutionControl(trusted, Clock.systemUTC());
	}

	private static ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

}
