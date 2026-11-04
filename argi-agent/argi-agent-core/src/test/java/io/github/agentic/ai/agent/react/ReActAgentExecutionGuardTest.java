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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.ExecutionControl;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证中立 ReAct 把运行守卫传给模型请求，使重试序列能被运行本身终止。
 */
@Timeout(20)
class ReActAgentExecutionGuardTest {

	/** 验证进入拦截器链的模型请求携带当前运行的执行守卫。 */
	@Test
	void theModelRequestCarriesTheGuardOfTheRun() throws Exception {
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
		ReActAgent agent = ReActAgent.builder(new SingleTurnClient())
			.tools(new ToolRegistry(List.of()))
			.interceptors(List.of(interceptor))
			.build();

		try (AgentExecutionContext context = context()) {
			CountDownLatch completed = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			agent.stream(AgentRequest.messages(List.of(ChatMessage.user("hello"))), context)
				.subscribe(new Flow.Subscriber<>() {
					@Override
					public void onSubscribe(Flow.Subscription subscription) {
						subscription.request(Long.MAX_VALUE);
					}

					@Override
					public void onNext(AgentEvent event) {
					}

					@Override
					public void onError(Throwable throwable) {
						failure.set(throwable);
						completed.countDown();
					}

					@Override
					public void onComplete() {
						completed.countDown();
					}
				});

			assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();
			assertThat(captured.get()).isSameAs(context.control());
		}
	}

	private AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("guard-test")
			.correlationId("guard-correlation")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	/** 返回一次普通回答的本地模型桩。 */
	private static final class SingleTurnClient implements ChatClient {

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			return CompletableFuture
				.completedFuture(new ChatResponse("id", "model", List.of(ChatMessage.assistant("done")), List.of(), null,
						null, Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new AssertionError("Expected the non-streaming model path");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
