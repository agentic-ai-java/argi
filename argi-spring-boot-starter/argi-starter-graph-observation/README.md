# ARGI Graph Observation Starter

The starter supplies a Spring-managed `CompileConfig` containing the graph
observation registry and lifecycle listener. Pass that bean to the compiler
or agent builder to enable observations for the workflow.

Adding the dependency does not change `StateGraph.compile()` or automatically
configure agent instances created with `new` or a builder. Those APIs continue
to use their existing defaults when no compile configuration is provided.

## Graph Configuration

```java
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.exception.GraphStateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;

@Configuration(proxyBeanMethods = false)
class WorkflowConfiguration {
    @Bean
    CompiledGraph workflow(CompileConfig compileConfig) throws GraphStateException {
        return new StateGraph()
                .addNode("work", node_async(state -> Map.of("result", "done")))
                .addEdge(StateGraph.START, "work")
                .addEdge("work", StateGraph.END)
                .compile(compileConfig);
    }
}
```

## Agent Configuration

With an existing neutral `ChatClient` bean, pass the same compile configuration to the
agent builder:

```java
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.api.chat.ChatClient;
import org.springframework.context.annotation.Bean;

@Bean
ReactAgent assistant(ChatClient chatClient, CompileConfig compileConfig) {
    return ReactAgent.builder()
            .name("assistant")
            .chatClient(chatClient)
            .compileConfig(compileConfig)
            .build();
}
```

## Custom Configuration

If the application defines its own `CompileConfig` bean, the starter's default
configuration backs off. Include the `ObservationRegistry` and
`GraphObservationLifecycleListener` in the custom configuration with
`observationRegistry(...)` and `withLifecycleListener(...)`, alongside the
application's saver, interrupts and other settings.

Setting `argi.graph.observation.enabled=false` also removes the starter's
default `CompileConfig` bean. Applications using the examples above must then
provide their own configuration or use optional injection and fall back to
the ordinary compiler/builder defaults.

Observation handlers and exporters must also be configured by the application.
An absent registry falls back to `ObservationRegistry.NOOP`; the starter does
not configure an OpenTelemetry collector or a tracing exporter.
