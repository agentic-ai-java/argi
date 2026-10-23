# ARGI Workflow Core

`argi-workflow-core` provides a versioned declarative workflow definition and an
independent executor. Definitions are immutable after publication and are shared
by API consumers, runtime code and future UI integrations.

The closed DSL supports agent and tool steps, input/state/step-output mappings,
conditions, bounded loops, bounded parallel branches, retries and versioned
subflows. Expressions are structural values and comparisons; the parser never
evaluates Java, scripts or arbitrary expressions from configuration.

`WorkflowAgent` implements the neutral `Agent` contract directly. It does not
construct or load `StateGraph`, `CompiledGraph`, `RunnableConfig`, Loop classes,
Spring or Reactor. In-process HITL continuation tokens are authenticated against
tenant, principal and run identity. Durable checkpoint recovery, exactly-once
side-effect claims and suspension inside parallel branches remain explicit
integration boundaries rather than being silently emulated.

The existing Graph-backed Flow agents remain available in `argi-agent-framework`.
Applications that need declarative independent execution depend on
`argi-workflow-core`, publish a `WorkflowDefinition`, and construct a
`WorkflowAgent` with their agent and tool resolvers.
