/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.contract;

import com.sun.net.httpserver.HttpServer;
import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.authorization.ToolAuthorizationPolicy;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real calls through one contract, with no Graph, framework, provider or Reactor jar. */
public final class AgentApiConsumer {

	private AgentApiConsumer() {
	}

	public static void main(String[] args) throws Exception {
		for (String unavailable : Set.of("io.github.agentic.ai.graph.StateGraph",
				"io.github.agentic.ai.graph.OverAllState", "io.github.agentic.ai.graph.RunnableConfig",
				"io.github.agentic.ai.graph.agent.Agent", "reactor.core.publisher.Flux",
				"org.springframework.context.ApplicationContext", "com.fasterxml.jackson.databind.ObjectMapper")) {
			try {
				Class.forName(unavailable, false, AgentApiConsumer.class.getClassLoader());
				throw new IllegalStateException("Unexpected dependency: " + unavailable);
			}
			catch (ClassNotFoundException expected) {
				// A genuine standalone consumer cannot load any of these classes.
			}
		}
		AtomicInteger remoteCalls = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/agent", exchange -> {
			remoteCalls.incrementAndGet();
			String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			byte[] output = ("remote:" + input).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, output.length);
			try (exchange) {
				exchange.getResponseBody().write(output);
			}
		});
		server.start();
		try {
			Agent local = (request, context) -> outcomePublisher(() -> {
				context.control().assertActive();
				if (request.resumeToken() != null) {
					throw new IllegalArgumentException("This consumer does not support resume");
				}
				if (ToolAuthorizationPolicy.requireMatchingTenant().authorize("echo", context.trusted()).isDenied()) {
					throw new SecurityException("Missing tenant");
				}
				ModelInterceptor interceptor = new ModelInterceptor() {
					public String getName() {
						return "fixture-prefix";
					}

					public ModelResponse interceptModel(ModelRequest modelRequest, ModelCallHandler next) {
						return next.call(modelRequest);
					}
				};
				ModelResponse response = interceptor.interceptModel(
						ModelRequest.builder()
							.messages(request.messages())
							.trustedExecutionContext(context.trusted())
							.build(),
						modelRequest -> ModelResponse
							.of(ChatMessage.assistant("local:" + modelRequest.getMessages().get(0).text())));
				return AgentOutcome.completed(List.of(response.getMessage()), Map.of(), Usage.unknown());
			});
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent");
			Agent remote = (request, context) -> outcomePublisher(() -> {
				context.control().assertActive();
				if (request.resumeToken() != null) {
					throw new IllegalArgumentException("This transport does not support resume");
				}
				HttpRequest http = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofSeconds(5))
					.POST(HttpRequest.BodyPublishers.ofString(request.messages().get(0).text()))
					.build();
				var response = client.sendAsync(http, HttpResponse.BodyHandlers.ofString());
				try (AutoCloseable cancellation = context.control().onLoss(() -> response.cancel(true))) {
					HttpResponse<String> received = response.join();
					context.control().assertActive();
					if (received.statusCode() != 200) {
						throw new IllegalStateException("Unexpected remote status: " + received.statusCode());
					}
					return AgentOutcome.completed(List.of(ChatMessage.assistant(received.body())), Map.of(),
							Usage.unknown());
				}
			});
			Agent workflow = (request, context) -> outcomePublisher(() -> {
				AgentOutcome first = local.invoke(request, context).join();
				AgentOutcome second = remote.invoke(AgentRequest.messages(first.messages()), context).join();
				return AgentOutcome.completed(second.messages(), Map.of("steps", 2), second.usage());
			});
			List<Agent> agents = List.of(local, remote, workflow);
			List<String> expected = List.of("local:hello", "remote:hello", "remote:local:hello");
			for (int index = 0; index < agents.size(); index++) {
				TrustedExecutionContext trusted = TrustedExecutionContext.builder()
					.tenant(new TenantReference("fixture", "tenant"))
					.principal(new PrincipalReference("fixture", "caller"))
					.runId("run-" + index)
					.correlationId("fixture")
					.build();
				try (AgentExecutionContext context = new AgentExecutionContext(trusted, Clock.systemUTC())) {
					AgentOutcome result = agents.get(index)
						.invoke(AgentRequest.messages(List.of(ChatMessage.user("hello"))), context)
						.join();
					if (!expected.get(index).equals(result.messages().get(0).text())) {
						throw new IllegalStateException("Invocation did not execute the selected implementation");
					}
					if (index == 2 && !Integer.valueOf(2).equals(result.outputs().get("steps"))) {
						throw new IllegalStateException("Workflow lost its business output");
					}
				}
			}
			if (remoteCalls.get() != 2) {
				throw new IllegalStateException("Remote and workflow invocations must perform real transport calls");
			}
		}
		finally {
			server.stop(0);
		}
	}

	private static Flow.Publisher<AgentEvent> outcomePublisher(Callable<AgentOutcome> work) {
		return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
			private final AtomicBoolean started = new AtomicBoolean();

			private volatile boolean cancelled;

			public void request(long demand) {
				if (cancelled || !started.compareAndSet(false, true)) {
					return;
				}
				if (demand <= 0) {
					subscriber.onError(new IllegalArgumentException("Demand must be positive"));
					return;
				}
				try {
					AgentOutcome outcome = work.call();
					if (!cancelled) {
						subscriber.onNext(new AgentEvent.Finished(outcome));
						if (!cancelled) {
							subscriber.onComplete();
						}
					}
				}
				catch (Exception exception) {
					if (!cancelled) {
						subscriber.onError(exception);
					}
				}
			}

			public void cancel() {
				cancelled = true;
			}
		});
	}

}
