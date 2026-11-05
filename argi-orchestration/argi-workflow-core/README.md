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

## Tool step outputs

A successful plain text result remains a string when it contains exactly one
`TEXT` part with a non-null text value, no MIME type or binary data, and no
part-level or result-level metadata. This includes results from `ToolResult.text`.

Other successful results use a map with `content`, `error` (false), and `metadata`.
`content` is an ordered list of maps, each containing `type` (the `ContentPart.Type`
name), `text`, `mimeType`, `data`, and `metadata`. Optional part fields retain null;
binary `data` is a copy of the original byte array. Empty content is an empty list,
so the step output itself is never null. Metadata values retain their runtime types;
they are not converted through JSON or `toString`, and this representation does not
add deep immutability guarantees for runtime data.

The same value is available as the step output and, when configured, under the
step's `outputKey` in state. Existing map/list paths can supply later tool arguments
or agent inputs, for example `new WorkflowValue.StepOutput("fetch", "content.1.text")`
or `WorkflowValue.state("fetch.metadata.receipt")`. JSON parts retain their original
JSON text in `content.0.text`, together with type `JSON` and their MIME type; they
are not automatically parsed into workflow values. Consumers that previously read
the first text from a compound or metadata-bearing result should select its explicit
`content.0.text` path instead. Binary data can be passed directly to a later agent
input; serialization into a later tool's JSON arguments follows the configured
`ObjectMapper` and does not establish end-to-end provider multimodal support.

Preparing arguments, failed invocation stages, and `ToolResult.error` retain the
tool step's retry policy. Projecting a successfully completed result occurs outside
that retry boundary. A malformed null result fails with a diagnostic
`WorkflowExecutionException` without invoking the tool again. This prevents result
handling failures from replaying a successful tool effect; it does not promise
exactly-once effects when a failed invocation has already performed external work.

## Definition data snapshots

Starting with the `2.1.0-RC2-SNAPSHOT` fix for #272, `WorkflowValue.Literal` and
definition metadata capture deeply immutable snapshots at construction, including
the `withMetadata` path. Caller mutations and collections returned by accessors
cannot alter a published version or its arguments during execution. Repeated
references within a snapshot reuse immutable copies; cyclic collections are rejected.

Supported data consists of null, strings, booleans, characters, the standard boxed
numeric types (`Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`), exact
`BigInteger` and `BigDecimal` values, lists, and maps with string keys. Nested null
values are preserved. Top-level metadata keys and values still cannot be null.
Numbers retain their Java types and precision. Collection nesting is limited to
128 levels; unsupported objects, invalid map keys, cycles and excessive nesting
fail with `IllegalArgumentException` rather than being silently serialized or retained.

Applications passing custom objects, mutable `Number` implementations, arrays,
sets or non-string map keys must explicitly convert them to supported data before
constructing a definition. Code that edited values obtained from definition
accessors must instead create new data and publish a new workflow version. Public
constructor and accessor signatures, JSON DSL structure, and version identifiers
are unchanged. Runtime input and state mutability are outside this snapshot rule.
Existing definitions must be reconstructed from their original source to adopt
the fix; reverting the implementation restores the previous shallow-copy behavior
without migrating persisted data.
