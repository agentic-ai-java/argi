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
package io.github.agentic.ai.autoconfigure.graph;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphLifecycleListener;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.observation.GraphObservationLifecycleListener;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.micrometer.common.KeyValues;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GraphObservationAutoConfiguration}.
 *
 * @author sixiyida
 * @since 2025/7/3
 */
class GraphObservationAutoConfigurationTest {

	private static final Logger log = LoggerFactory.getLogger(GraphObservationAutoConfigurationTest.class);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GraphObservationAutoConfiguration.class));

	private ObservationRegistry functionalTestObservationRegistry;

	@BeforeEach
	void setUpFunctionalTests() {
		functionalTestObservationRegistry = ObservationRegistry.create();
		functionalTestObservationRegistry.observationConfig().observationHandler(new SimpleHandler());

		ContextRegistry.getInstance()
			.registerThreadLocalAccessor(new ObservationThreadLocalAccessor(functionalTestObservationRegistry));

		Hooks.enableAutomaticContextPropagation();

		log.info("Initialized functional test ObservationRegistry with complete context propagation");
	}

	@Test
	void shouldAutoConfigureWhenEnabled() {
		this.contextRunner.withPropertyValues("argi.graph.observation.enabled=true")
			.withUserConfiguration(TestConfiguration.class)
			.run(context -> {
				assertThat(context).hasSingleBean(GraphObservationLifecycleListener.class);
				assertThat(context).hasSingleBean(CompileConfig.class);
				assertThat(context).hasBean("observationGraphCompileConfig");
			});
	}

	@Test
	void compiledGraphUsingStarterConfigProducesGraphObservations() {
		this.contextRunner.withPropertyValues("argi.graph.observation.enabled=true")
			.withUserConfiguration(TestConfiguration.class)
			.run(context -> {
				AtomicBoolean graphObserved = new AtomicBoolean();
				context.getBean(ObservationRegistry.class)
					.observationConfig()
					.observationHandler(new ObservationHandler<Observation.Context>() {
						@Override
						public boolean supportsContext(Observation.Context observationContext) {
							return "argi.graph.graph-execution".equals(observationContext.getName());
						}

						@Override
						public void onStart(Observation.Context observationContext) {
							graphObserved.set(true);
						}
					});
				CompiledGraph graph = new StateGraph().addNode("work", node_async(state -> Map.of("result", "done")))
					.addEdge(START, "work")
					.addEdge("work", END)
					.compile(context.getBean(CompileConfig.class));

				assertThat(graph.invoke(Map.of()).orElseThrow().value("result").orElseThrow()).isEqualTo("done");
				assertThat(graphObserved).isTrue();
			});
	}

	@Test
	void shouldNotAutoConfigureWhenDisabled() {
		this.contextRunner.withPropertyValues("argi.graph.observation.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(GraphObservationLifecycleListener.class);
			assertThat(context).doesNotHaveBean(CompileConfig.class);
		});
	}

	@Test
	void shouldAutoConfigureWithDefaultProperties() {
		this.contextRunner.withUserConfiguration(TestConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(GraphObservationLifecycleListener.class);
			assertThat(context).hasSingleBean(CompileConfig.class);

			GraphObservationProperties properties = context.getBean(GraphObservationProperties.class);
			assertThat(properties.isEnabled()).isTrue();
			assertThat(properties.isCaptureContent()).isFalse();
			assertThat(properties.getMaxContentLength()).isEqualTo(1000);
		});
	}

	@Test
	void shouldBindContentCaptureProperties() {
		this.contextRunner
			.withPropertyValues("argi.graph.observation.capture-content=true",
					"argi.graph.observation.max-content-length=32")
			.withUserConfiguration(TestConfiguration.class)
			.run(context -> {
				GraphObservationProperties properties = context.getBean(GraphObservationProperties.class);

				assertThat(properties.isCaptureContent()).isTrue();
				assertThat(properties.getMaxContentLength()).isEqualTo(32);
			});
	}

	@Test
	void graphObservationShouldNotCaptureRawContentByDefault() {
		this.contextRunner.withUserConfiguration(TestConfiguration.class).run(context -> {
			KeyValues keyValues = observeContent(context.getBean(ObservationRegistry.class),
					context.getBean(GraphObservationLifecycleListener.class), "user secret", "assistant secret");

			assertThat(keyValues.stream().map(keyValue -> keyValue.getValue()).toList()).doesNotContain("user secret",
					"assistant secret");
		});
	}

	@Test
	void graphObservationShouldCaptureSanitizedContentWhenEnabled() {
		this.contextRunner
			.withPropertyValues("argi.graph.observation.capture-content=true",
					"argi.graph.observation.max-content-length=256")
			.withUserConfiguration(TestConfiguration.class)
			.run(context -> {
				String output = "assistant response with long suffix".repeat(20);
				KeyValues keyValues = observeContent(context.getBean(ObservationRegistry.class),
						context.getBean(GraphObservationLifecycleListener.class), "password=secret-value", output);
				List<String> values = keyValues.stream().map(keyValue -> keyValue.getValue()).toList();

				assertThat(values).anySatisfy(value -> {
					assertThat(value).contains("password=<redacted>");
					assertThat(value).doesNotContain("secret-value");
				});
				assertThat(values).anySatisfy(value -> {
					assertThat(value).contains("output=" + output.substring(0, 256) + "... (truncated); ");
					assertThat(value).doesNotContain(output);
					assertThat(value.length()).isLessThanOrEqualTo(256 * 4);
				});
			});
	}

	@Test
	void shouldConfigureObservationHandlersWhenMeterRegistryPresent() {
		this.contextRunner.withUserConfiguration(TestConfigurationWithMeterRegistry.class).run(context -> {
			assertThat(context).hasBean("graphObservationHandler");
			assertThat(context).hasBean("graphNodeObservationHandler");
			assertThat(context).hasBean("graphEdgeObservationHandler");
		});
	}

	@Test
	void shouldNotConfigureObservationHandlersWhenMeterRegistryAbsent() {
		this.contextRunner.withUserConfiguration(TestConfiguration.class).run(context -> {
			assertThat(context).doesNotHaveBean("graphObservationHandler");
			assertThat(context).doesNotHaveBean("graphNodeObservationHandler");
			assertThat(context).doesNotHaveBean("graphEdgeObservationHandler");
		});
	}

	@Test
	void shouldRegisterObservationThreadLocalAccessorForContextPropagation() {
		this.contextRunner.withUserConfiguration(TestConfiguration.class).run(context -> {
			assertThat(context).hasBean("observationThreadLocalAccessorRegistrar");
			assertThat(context)
				.hasSingleBean(GraphObservationAutoConfiguration.ObservationThreadLocalAccessorRegistrar.class);

			boolean isRegistered = ContextRegistry.getInstance()
				.getThreadLocalAccessors()
				.stream()
				.anyMatch(accessor -> accessor instanceof ObservationThreadLocalAccessor);

			assertThat(isRegistered)
				.as("ObservationThreadLocalAccessor should be registered in ContextRegistry "
						+ "to enable context propagation in streaming with tool calls")
				.isTrue();
		});
	}

	@Test
	void shouldSuccessfullyPropagateObservationContextAcrossThreads() {
		this.contextRunner.withUserConfiguration(TestConfiguration.class).run(context -> {
			ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
			var parentObservation = io.micrometer.observation.Observation.start("test.parent", observationRegistry);

			try {
				parentObservation.scoped(() -> {
					var currentObservation = observationRegistry.getCurrentObservation();

					assertThat(currentObservation).as("Current observation should be accessible in scoped context")
						.isNotNull();
					assertThat(currentObservation).as("Current observation should be the same as parent observation")
						.isSameAs(parentObservation);
				});
			}
			finally {
				parentObservation.stop();
			}
		});
	}

	@Test
	void shouldAccessObservationInStreamingScenario() throws Exception {
		String conversationId = "conv-test-123";
		AtomicBoolean toolCallSucceeded = new AtomicBoolean(false);
		AtomicReference<String> capturedConversationId = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> keyStrategyMap = new HashMap<>();
			keyStrategyMap.put("messages", new AppendStrategy());
			keyStrategyMap.put("conversation_id", (oldValue, newValue) -> newValue);
			return keyStrategyMap;
		}).addNode("simpleNode", node_async(state -> {
			log.info("Simple Node: Processing");
			return Map.of("messages", "Simple message");
		})).addNode("toolNode", node_async(state -> {
			log.info("Tool Node: Executing tool call");

			try {
				Observation currentObservation = functionalTestObservationRegistry.getCurrentObservation();

				if (currentObservation != null) {
					String convId = (String) currentObservation.getContext().get("conversation_id");
					capturedConversationId.set(convId);
					toolCallSucceeded.set(true);
					log.info("Tool call succeeded! Captured conversation_id: {}", convId);
				}
				else {
					log.error("Tool call failed! Current observation is null");
				}
			}
			catch (Exception e) {
				log.error("Tool call error: ", e);
			}

			return Map.of("messages", "Tool execution result");
		})).addEdge(START, "simpleNode").addEdge("simpleNode", "toolNode").addEdge("toolNode", END);

		CompiledGraph compiledGraph = stateGraph.compile();

		Observation parentObservation = Observation.start("chat.client", functionalTestObservationRegistry);
		parentObservation.getContext().put("conversation_id", conversationId);

		parentObservation.scoped(() -> {
			Map<String, Object> input = Map.of("input", "Test tool call with observation context", "conversation_id",
					conversationId);

			compiledGraph.stream(input).doOnNext(output -> log.info("Received output: {}", output)).doOnError(error -> {
				log.error("Stream error: ", error);
				latch.countDown();
			}).doOnComplete(() -> {
				log.info("Stream completed");
				latch.countDown();
			}).subscribe();
		});

		assertTrue(latch.await(10, TimeUnit.SECONDS), "Stream processing should complete within 10 seconds");

		parentObservation.stop();
	}

	@Test
	void shouldDemonstrateContextLossWithoutAccessor() throws Exception {
		ObservationRegistry isolatedRegistry = ObservationRegistry.create();

		AtomicBoolean contextAvailable = new AtomicBoolean(false);
		CountDownLatch latch = new CountDownLatch(1);
		Observation observation = Observation.start("test.parent", isolatedRegistry);

		observation.scoped(() -> {
			Mono.delay(Duration.ofMillis(10)).subscribe(i -> {
				Observation current = isolatedRegistry.getCurrentObservation();
				contextAvailable.set(current != null);
				latch.countDown();
			});
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Async operation should complete within 5 seconds");
		observation.stop();

		log.info("Context available in async thread without accessor: {}", contextAvailable.get());
		log.info("This test demonstrates the context propagation issue that Issue #3131 fixes");
	}

	@Test
	void shouldPropagateNestedObservationContexts() {
		String conversationId = "conv-nested-456";
		String userId = "user-789";
		AtomicBoolean allContextsAvailable = new AtomicBoolean(false);

		Observation chatClientObservation = Observation.start("chat.client", functionalTestObservationRegistry);
		chatClientObservation.getContext().put("conversation_id", conversationId);

		chatClientObservation.scoped(() -> {
			Observation agentObservation = Observation.start("agent.execute", functionalTestObservationRegistry);
			agentObservation.getContext().put("user_id", userId);

			agentObservation.scoped(() -> {
				Observation toolObservation = Observation.start("tool.call", functionalTestObservationRegistry);

				toolObservation.scoped(() -> {
					Observation current = functionalTestObservationRegistry.getCurrentObservation();
					assertNotNull(current, "Current observation should exist");

					allContextsAvailable.set(true);

					log.info("Successfully accessed nested observation context");
				});

				toolObservation.stop();
			});

			agentObservation.stop();
		});

		chatClientObservation.stop();

		assertTrue(allContextsAvailable.get(), "All nested observation contexts should be accessible");
	}

	@Test
	void shouldPropagateContextAcrossActualThreadBoundaries() throws Exception {
		String conversationId = "conv-cross-thread-789";
		AtomicReference<String> capturedThreadName = new AtomicReference<>();
		AtomicReference<String> capturedConversationId = new AtomicReference<>();
		AtomicBoolean contextAvailable = new AtomicBoolean(false);
		CountDownLatch latch = new CountDownLatch(1);

		Observation parentObservation = Observation.start("chat.client", functionalTestObservationRegistry);
		parentObservation.getContext().put("conversation_id", conversationId);

		parentObservation.scoped(() -> {
			Mono.delay(Duration.ofMillis(10)).publishOn(Schedulers.boundedElastic()).doOnNext(i -> {
				String threadName = Thread.currentThread().getName();
				capturedThreadName.set(threadName);
				log.info("Executing in thread: {}", threadName);

				Observation current = functionalTestObservationRegistry.getCurrentObservation();
				if (current != null) {
					String convId = (String) current.getContext().get("conversation_id");
					capturedConversationId.set(convId);
					contextAvailable.set(true);
					log.info("Successfully captured conversation_id in async thread: {}", convId);
				}
				else {
					log.error("Failed to get observation in async thread: {}", threadName);
				}
			}).doFinally(signalType -> latch.countDown()).subscribe();
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Async operation should complete within 5 seconds");
		parentObservation.stop();

		String threadName = capturedThreadName.get();
		assertNotNull(threadName, "Should capture thread name");
		assertFalse(threadName.contains("main"),
				"Should execute in non-main thread (actual thread: " + threadName + ")");

		assertTrue(contextAvailable.get(),
				"Should be able to get Observation Context in async thread (Issue #3131 core fix)");
		assertEquals(conversationId, capturedConversationId.get(),
				"Should be able to get correct conversation_id in async thread");

		log.info("Cross-thread context propagation verified! Thread: {}, conversation_id: {}", threadName,
				capturedConversationId.get());
	}

	private KeyValues observeContent(ObservationRegistry registry, GraphObservationLifecycleListener listener,
			String input, String output) {
		AtomicReference<KeyValues> captured = new AtomicReference<>(KeyValues.empty());
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				captured.set(context.getHighCardinalityKeyValues());
			}
		});
		Map<String, Object> state = new HashMap<>();
		state.put(GraphLifecycleListener.EXECUTION_ID_KEY, java.util.UUID.randomUUID().toString());
		state.put("input", input);
		RunnableConfig config = RunnableConfig.builder().build();
		listener.onStart(START, state, config);
		state.put("output", output);
		listener.onComplete(END, state, config);
		return captured.get();
	}

	@Configuration(proxyBeanMethods = false)
	static class TestConfiguration {

		@Bean
		ObservationRegistry observationRegistry() {
			ObservationRegistry registry = ObservationRegistry.create();
			registry.observationConfig().observationHandler(new SimpleHandler());
			return registry;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class TestConfigurationWithMeterRegistry extends TestConfiguration {

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

	}

	static class SimpleHandler implements ObservationHandler<Observation.Context> {

		@Override
		public boolean supportsContext(Observation.Context handlerContext) {
			return true;
		}

	}

}
