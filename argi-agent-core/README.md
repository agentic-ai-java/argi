# ARGI Agent Core

`argi-agent-core` contains the first graph-independent ReAct execution engine. It
depends on `argi-agent-api`, `argi-api` and `argi-runtime-api`; it does not load
`StateGraph`, `CompiledGraph`, `RunnableConfig`, Graph persistence, Reactor or a
Spring application context.

## Execution stages

`ReActAgent` performs the following ordered stages for every turn:

1. Build a neutral model request and apply model interceptors.
2. Call the model synchronously or consume its normalized stream.
3. Append the assistant response and publish usage/message events.
4. Authorize and optionally validate every tool call.
5. Execute tools sequentially, map business outcomes and append tool messages.
6. Admit optional context patches at the configured validation boundary.
7. Finish, suspend for human approval, or continue until the turn limit.

Tool failures become model-visible error messages and normally continue the loop,
matching the existing agent behavior. Authorization failures either become an error
message or enter the explicitly configured `HumanInTheLoop` boundary. Cancellation,
deadline expiry and ownership loss fail the execution and cancel the in-flight model
or tool stage; no state is published after control loss.

## Deliberate boundaries

This slice supports sequential tool calls, in-process human suspension and opaque
resume validation. It does not provide Graph checkpoint recovery, parallel tool
aggregation, a scheduler, durable HITL storage, child-agent orchestration or a
remote protocol. Those semantics require explicit contracts in the corresponding
architecture tasks and are not silently emulated with an `OverAllState` replacement.

Structured output uses the shared `StructuredOutputFinalizer`; invalid output fails
the execution through `AgentExecutionException` instead of being reported as a
successful `AgentOutcome`.

The existing Graph-backed `ReactAgent` remains unchanged and continues to own its
Graph state, checkpoints, hooks and legacy public API. Applications opt into this
engine by depending on `argi-agent-core` and constructing `ReActAgent` directly.
