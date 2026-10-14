<div align="center">
  <a href="https://agentic-ai-java.github.io/argi-website/en/">
    <img src="asset/images/logo.svg" alt="ARGI logo" width="180">
  </a>
  <h1>ARGI</h1>
  <p><strong>Stateful agent runtime for Java applications.</strong></p>
  <p>Graph workflows · ReAct agents · Context engineering · Human-in-the-loop · Multi-agent orchestration</p>
  <p>
    <a href="https://agentic-ai-java.github.io/argi-website/en/docs/overview">Documentation</a> ·
    <a href="https://agentic-ai-java.github.io/argi-website/en/docs/quick-start">Quick Start</a> ·
    <a href="https://github.com/agentic-ai-java/argi-examples/tree/main/examples">Examples</a> ·
    <a href="README-zh.md">简体中文</a>
  </p>
  <p>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202-4EB1BA.svg" alt="License"></a>
    <a href="https://github.com/agentic-ai-java/argi"><img src="https://img.shields.io/badge/version-2.1.0--RC1-blue" alt="Version"></a>
    <img src="https://img.shields.io/badge/Java-17%2B-f59e0b" alt="Java 17+">
  </p>
</div>

---

ARGI stands for **Agent Runtime and Graph Intelligence** and is pronounced **"AR-jee"** (`/ˈɑːr.dʒiː/`). It is a framework for Java developers building agents, workflows, and multi-agent applications. Forked from Spring AI Alibaba, it provides context engineering, human-in-the-loop, graph workflows, and distributed Agent-to-Agent (A2A) collaboration.

## Features

- **Agent orchestration**: `ReactAgent`, `SequentialAgent`, `ParallelAgent`, `RoutingAgent`, and `LoopAgent`.
- **Context engineering**: context compaction and editing, call limits, tool retries, planning, and dynamic tool selection.
- **Graph workflows**: conditional routing, parallel execution, nested graphs, state persistence, and interruption recovery.
- **Agent runtime foundation**: provider-neutral agent orchestration, workflow state, and embeddable debugging support.
- **Open integrations**: a neutral ChatClient with Spring AI, LangChain4j, Google ADK, and AgentScope adapters, plus tool calling, MCP, A2A, and Nacos.
- **Visual debugging**: an embeddable Agent Chat UI for Spring Boot applications.

## Quick Start

Requirements: JDK 17 or later and Maven 3.9.1 or later. Build the framework with its Maven Wrapper and run the standalone examples with your local Maven installation.

```shell
git clone --depth=1 https://github.com/agentic-ai-java/argi.git
cd argi

# Install the local development modules.
./mvnw -DskipTests install

# Configure any supported model provider and run the chatbot example.
cd ..
git clone --depth=1 https://github.com/agentic-ai-java/argi-examples.git
cd argi-examples
mvn -f examples/chatbot/pom.xml spring-boot:run
```

The examples also require the matching Extensions BOM and provider starters. See the [example setup](https://github.com/agentic-ai-java/argi-examples/tree/main/examples#环境与依赖). Examples using older Spring AI agent builders require the direct-replacement migration described in [API and Adapters](argi-api/README.md); that external repository is not migrated by this change.

Open [http://localhost:8080/chatui/index.html](http://localhost:8080/chatui/index.html). See the [Quick Start](https://agentic-ai-java.github.io/argi-website/en/docs/quick-start) for other model providers.

## Modules

| Module | Description |
| --- | --- |
| [API and Adapters](argi-api) | Framework-neutral ChatClient contracts and Spring AI, LangChain4j, Google ADK, and AgentScope adapters |
| [Agent Framework](argi-agent-framework) | Agent development and multi-agent orchestration |
| [Graph Core](argi-graph-core) | State management, persistence, and workflow runtime |
| [Studio](argi-studio) | Visual debugging UI for agents |
| [Sandbox](https://github.com/agentic-ai-java/argi-extensions/tree/main/sandbox/argi-sandbox) | Optional isolated execution environment for tool calls, maintained in Extensions |
| [Spring Boot Starters](argi-spring-boot-starter) | Built-in graph nodes, graph observability, and four adapter auto-configurations |
| [Extensions](https://github.com/agentic-ai-java/argi-extensions) | Model and document contracts, A2A, Nacos, AgentScope, storage, and other optional integrations |
| [Examples](https://github.com/agentic-ai-java/argi-examples/tree/main/examples) | Chatbot, multi-agent, graph engineering, and documentation examples |
| [Benchmark](benchmark) | [argi-benchmark](https://github.com/agentic-ai-java/argi-benchmark) |

## Documentation

- [Overview](https://agentic-ai-java.github.io/argi-website/en/docs/overview)
- [Quick Start](https://agentic-ai-java.github.io/argi-website/en/docs/quick-start)
- [Agent Framework tutorials](https://agentic-ai-java.github.io/argi-website/en/docs/frameworks/agent-framework/tutorials/agents)
- [Graph Core Quick Start](https://agentic-ai-java.github.io/argi-website/en/docs/frameworks/graph-core/quick-start)
- [Examples](https://github.com/agentic-ai-java/argi-examples/tree/main/examples)
- [Provider-specific examples](https://github.com/agentic-ai-java/argi-extensions/tree/main/examples)

## Contributing

Read the [contribution guide](CONTRIBUTING.md) before submitting changes. Report problems and suggestions through [GitHub Issues](https://github.com/agentic-ai-java/argi/issues).

<a href="https://github.com/agentic-ai-java/argi/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=agentic-ai-java/argi&max=500&columns=18&anon=1" alt="contributors"/>
</a>

## License

ARGI is available under the [Apache License 2.0](LICENSE).
