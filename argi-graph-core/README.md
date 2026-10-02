# ARGI Graph

## What's ARGI Graph

ARGI Graph is a **workflow and multi-agent framework** for Java developers to build complex applications composed of multiple AI models or steps.

ARGI Graph serves as the underlying core engine of the Agent Framework. It provides atomic components for building intelligent agents with interruptible and orchestratable capabilities, offering high flexibility but also relatively high learning costs. In contrast, the Agent Framework is built atop Graph, abstracting away the underlying complexities through concepts like ReactAgent and SequentialAgent.

Please check [the documentation](https://java2ai.com/docs/frameworks/graph-core/quick-start) on official website for more details

## Core Concepts & Classes

Graph is deeply integrated with the Spring Boot ecosystem, providing a declarative API to orchestrate workflows. This allows developers to abstract each step of an AI application as a node (Node) and connect these nodes in the form of a directed graph (Graph) to create a customizable execution flow. Compared to traditional single-agent (one-turn Q&A) solutions, ARGI Graph supports more complex multi-step task flows, helping to address the issue of a **single large model being insufficient for complex tasks**.

The core of the framework includes: **StateGraph** (the state graph for defining nodes and edges), **Node** (node, encapsulating a specific operation or model call), **Edge** (edge, representing transitions between nodes), and **OverAllState** (global state, carrying shared data throughout the flow). These designs make it convenient to manage state and control flow in the workflow.

1. StateGraph
   The main class for defining a workflow.
   Lets you add nodes (addNode) and edges (addEdge, addConditionalEdges).
   Supports conditional routing, subgraphs, and validation.
   Can be compiled into a CompiledGraph for execution.
2. Node
   Represents a single step in the workflow (e.g., a model call, a data transformation).
   Nodes can be asynchronous and can encapsulate LLM calls or custom logic.
3. Edge
   Represents transitions between nodes.
   Can be conditional, with logic to determine the next node based on the current state.
4. OverAllState
   A serializable, central state object that holds all workflow data.
   Supports key-based strategies for merging/updating state.
   Used for checkpointing, resuming, and passing data between nodes.
5. CompiledGraph
   The executable form of a StateGraph.
   Handles the actual execution, state transitions, and streaming of results.
   Supports interruption, parallel nodes, and checkpointing.
6. InterruptableAction
   Interface for actions that can interrupt graph execution.
   Provides two hook points: `interrupt()` (before execution) and `interruptAfter()` (after execution).
   Useful for human-in-the-loop scenarios, approval workflows, and multi-turn conversations.

## How It's Used (Typical Flow)
- Define StateGraph: In a Spring configuration, you define a StateGraph bean, add nodes (each encapsulating a model call or logic), and connect them with edges.
- Configure State: Use an OverAllStateFactory to define the initial state and key strategies.
- Execution: The graph is compiled and executed, with state flowing through nodes and edges, and conditional logic determining the path.
- Integration: Typically exposed via a REST controller or service in a Spring Boot app.

## Concurrent Calls and Checkpoints

Graph and ReactAgent executions using the same checkpoint saver instance and checkpoint
namespace run in FIFO subscription order within one JVM. A queued call loads the latest
checkpoint only after the preceding call finishes, fails, or completes cancellation
cleanup. Cancelling a waiting call removes it without running its nodes or changing
checkpoint state. ReactAgent cancellation also finishes its checkpoint rewind before the
next call starts.

The namespace comes from `BaseCheckpointSaver.checkpointThreadId(config)`, including
application and user metadata when present. Calls for different namespaces or different
saver instances can still run concurrently. An omitted `threadId` uses the shared
`$default` namespace; set explicit thread IDs to isolate conversations. Graphs configured
with an empty `SaverConfig` keep their previous concurrent behavior.

This is an in-process execution queue, not a distributed lock. Separate saver instances
or application processes pointing at the same database need external coordination.
Direct saver writes and explicit `CompiledGraph.updateState` calls are outside this
queue; applications must coordinate these with running executions. A queued call keeps
waiting while its predecessor remains active, so apply an application timeout or cancel
the subscription when that wait is no longer wanted.

## Interruption Support

ARGI Graph supports interrupting workflow execution at specific points, enabling human-in-the-loop scenarios.

### InterruptableAction Interface

```java
public interface InterruptableAction {
    // Called BEFORE node execution - can prevent execution
    Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config);

    // Called AFTER node execution - can inspect results and interrupt
    default Optional<InterruptionMetadata> interruptAfter(String nodeId, OverAllState state,
            Map<String, Object> actionResult, RunnableConfig config) {
        return Optional.empty();
    }
}
```

### Example Usage

```java
public class ReviewAction implements AsyncNodeActionWithConfig, InterruptableAction {

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of("result", "generated_content"));
    }

    @Override
    public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
        return Optional.empty(); // Don't interrupt before execution
    }

    @Override
    public Optional<InterruptionMetadata> interruptAfter(String nodeId, OverAllState state,
            Map<String, Object> actionResult, RunnableConfig config) {
        // Interrupt after execution for human review
        return Optional.of(InterruptionMetadata.builder(nodeId, state)
            .addMetadata("reason", "needs_review")
            .addMetadata("content", actionResult.get("result"))
            .build());
    }
}
```
