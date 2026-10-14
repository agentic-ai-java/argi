# ARGI Spring Boot Starters

This directory groups all ARGI Spring Boot starters. It is not a catch-all starter
artifact: applications select the modules they need. The root `pom.xml`
aggregates each child module directly.

| Directory and Maven Artifact | Responsibility |
| --- | --- |
| `argi-starter-builtin-nodes` | Built-in graph workflow nodes |
| `argi-starter-graph-observation` | Graph observation auto-configuration |
| `argi-starter-adapter-spring-ai` | Spring AI ChatClient adapter auto-configuration |
| `argi-starter-adapter-langchain4j` | LangChain4j ChatClient adapter auto-configuration |
| `argi-starter-adapter-google-adk` | Google ADK ChatClient adapter auto-configuration |
| `argi-starter-adapter-agentscope` | AgentScope ChatClient adapter auto-configuration |

All artifacts use the group ID `io.github.agentic-ai-java`. The four adapter
starters depend on the corresponding implementations in [`argi-api`](../argi-api).
They wrap application-provided native beans; they do not manage provider
credentials. A custom ARGI ChatClient bean overrides auto-configuration. When
multiple adapter starters are present, select one using `argi.chat.adapter`:

```yaml
argi:
  chat:
    adapter: spring-ai # spring-ai | langchain4j | google-adk | agentscope
```

From the repository root:

```shell
./mvnw -B -pl :argi-starter-adapter-spring-ai -am test
./mvnw -B -pl argi-spring-boot-starter/argi-starter-builtin-nodes -am package -DskipTests
```

Replace source paths starting with `spring-boot-starters/` with
`argi-spring-boot-starter/`. Published starter coordinates, Java packages,
configuration properties, and `AutoConfiguration.imports` entries are unchanged.
