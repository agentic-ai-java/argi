# Agent, Studio, and Serializer Boundaries

Status: adopted for the current Loop migration. Reviewed 2026-10-24.

This is the implementation decision for [#184](https://github.com/agentic-ai-java/argi/issues/184).
The boundary is defined by ownership and dependencies, not by mechanically
renaming every package containing `graph`.

## Ownership map

| Area | Public entry points | Owner | Dependency rule |
| --- | --- | --- | --- |
| Neutral Agent invocation | `io.github.agentic.ai.agent.*` in `argi-agent-api` | Agent API | Runtime API and neutral API only; no Loop, Spring, Reactor, or provider SDK. |
| Independent ReAct execution | `io.github.agentic.ai.agent.react.*` in `argi-agent-core` | Agent Core | Uses Agent API and neutral model/tool contracts. It does not construct `StateGraph` or require `argi-loop-core`. |
| Independent Workflow execution | `io.github.agentic.ai.workflow.*` in `argi-workflow-core` | Workflow Core | Uses Agent API for optional model/agent calls. It does not construct `StateGraph` or require `argi-loop-core`. |
| Graph-backed flow agents and legacy interceptors | `io.github.agentic.ai.graph.agent.*` | Agent Framework / transitional Agent API contracts | Retained because the current flow-agent implementation and public interceptor contracts use these names. This is an integration boundary, not the independent Agent API. |
| Graph execution | `io.github.agentic.ai.graph.*` | Loop Core | Owns topology, compilation, state, checkpointing, serialization, and graph recovery. |
| Graph serializer | `io.github.agentic.ai.graph.serializer.*` | Loop Core | Owns neutral state encoding and historical graph checkpoint reads. It may use neutral API chat records but not provider SDK classes in production bytecode. |
| Studio management and HTTP surface | `io.github.agentic.ai.agent.studio.*` | Studio | Owns controllers, DTOs, loaders, web resources, and graph/agent management adapters. Studio may depend on Loop for graph views and on Agent Framework for legacy agent views. |
| Neutral chat records | `io.github.agentic.ai.api.chat.*` | API | Owns framework-neutral message and tool contracts. It must not depend on Loop or Studio. |

## Decisions

### Keep graph serializer packages

The serializer reads historical `@class`, `@type`, and `@typeHint` values and
emits stable `ARGI_*` markers. Moving it to a new package would change Java
object-stream and type-marker compatibility without improving ownership: it is
still a Loop checkpoint concern. Keep the package and use the compatibility
contract in `checkpoint-compatibility.md`.

### Keep transitional graph-agent packages

`argi-agent-api` contains model interception and authorization contracts under
`io.github.agentic.ai.graph.agent.*`, and `argi-agent-core` consumes them. A
package rename would affect public FQCNs without removing the actual graph-backed
flow implementation. Keep these contracts until an independent interception
contract replaces them; do not claim that the package name alone makes ReAct
graph-dependent.

### Keep Studio's graph/agent split

Studio has separate loaders and controllers for graph and agent execution. Graph
DTOs and graph loaders remain Studio-facing management objects; they are not moved
into Loop. A future Studio API can add a neutral execution management contract,
but it must not duplicate checkpoint or serializer implementations.

## Public API migration table

| Existing API | Decision | Migration guidance |
| --- | --- | --- |
| `argi-agent-api` neutral invocation contracts | Keep | Use `Agent`, `AgentRequest`, `AgentOutcome`, and `AgentExecutionContext`. |
| `argi-agent-core` ReAct implementation | Keep | Use `ReActAgent`; do not add `asNode` or `CompiledGraph` adapters to make it appear independent. |
| `argi-workflow-core` definition and execution | Keep | Use `WorkflowDefinition`, `WorkflowRegistry`, and `WorkflowAgent`; loop steps stay Workflow-owned. |
| `argi-loop-core` graph and serializer classes | Keep FQCNs | Change only the Maven artifact from `argi-graph-core` to `argi-loop-core` in this phase. |
| `argi-studio` graph and agent endpoints | Keep | Existing HTTP paths and DTO names are management API compatibility, not Loop public execution contracts. |

## Release sequencing

1. Publish Runtime API/Core and neutral Agent API contracts.
2. Publish independent Agent Core and Workflow Core artifacts.
3. Publish Loop Core under the `argi-loop-core` coordinate.
4. Publish Agent Framework and Studio integrations that consume Loop.
5. Publish matching BOM and starter coordinates, then update Extensions and
   examples.

The sequence prevents an independent Agent or Workflow consumer from acquiring
Loop transitively while retaining a working graph-backed integration for existing
applications.
