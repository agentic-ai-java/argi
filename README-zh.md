<div align="center">
  <a href="https://agentic-ai-java.github.io/argi-website/">
    <img src="asset/images/logo.svg" alt="ARGI logo" width="180">
  </a>
  <h1>ARGI</h1>
  <p><strong>面向 Java 应用的有状态智能体运行时。</strong></p>
  <p>图工作流 · ReAct Agent · 上下文工程 · 人工介入 · 多智能体编排</p>
  <p>
    <a href="https://agentic-ai-java.github.io/argi-website/docs/overview">文档</a> ·
    <a href="https://agentic-ai-java.github.io/argi-website/docs/quick-start">快速开始</a> ·
    <a href="https://github.com/agentic-ai-java/argi-examples/tree/main/examples">示例</a> ·
    <a href="README.md">English</a>
  </p>
  <p>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202-4EB1BA.svg" alt="License"></a>
    <a href="https://github.com/agentic-ai-java/argi"><img src="https://img.shields.io/badge/version-2.1.0--RC1-blue" alt="Version"></a>
    <img src="https://img.shields.io/badge/Java-17%2B-f59e0b" alt="Java 17+">
  </p>
</div>

---

ARGI 是 **Agent Runtime and Graph Intelligence** 的缩写，读作 **“AR-jee”**（`/ˈɑːr.dʒiː/`）。它是面向 Java 开发者的智能体应用框架，用于构建 Agent、工作流和多智能体应用。项目 Fork 自 Spring AI Alibaba，提供上下文工程、人工介入、图工作流和分布式 Agent-to-Agent（A2A）协作能力。

## 核心能力

- **智能体编排**：提供 `ReactAgent`、`SequentialAgent`、`ParallelAgent`、`RoutingAgent` 和 `LoopAgent`。
- **上下文工程**：支持上下文压缩与编辑、调用限制、工具重试、规划和动态工具选择。
- **图工作流**：支持条件路由、并行执行、嵌套图、状态持久化和中断恢复。
- **智能体运行时基础**：提供模型无关的智能体编排、工作流状态和可嵌入调试支持。
- **开放集成**：通过中立 ChatClient 接入 Spring AI、LangChain4j、Google ADK 和 AgentScope，并提供工具调用、MCP、A2A 和 Nacos。
- **可视化调试**：提供可嵌入 Spring Boot 应用的 Agent Chat UI。

## 快速开始

环境要求：JDK 17 或更高版本、Maven 3.9.1 或更高版本。框架构建使用 Maven Wrapper，独立示例使用本机 Maven。

```shell
git clone --depth=1 https://github.com/agentic-ai-java/argi.git
cd argi

# Install the local development modules.
./mvnw -DskipTests install

# 配置任一受支持的模型提供商后运行 chatbot 示例。
cd ..
git clone --depth=1 https://github.com/agentic-ai-java/argi-examples.git
cd argi-examples
mvn -f examples/chatbot/pom.xml spring-boot:run
```

示例还需要对应版本的 Extensions BOM 和模型 Starter，依赖准备方式见[示例说明](https://github.com/agentic-ai-java/argi-examples/tree/main/examples#环境与依赖)。仍使用旧 Spring AI Agent Builder 的示例需要按 [API 与 Adapter](argi-api/README.md) 中的直接替换说明迁移；本次修改不包含外部示例仓库的迁移。

启动后访问 [http://localhost:8080/chatui/index.html](http://localhost:8080/chatui/index.html)。其他模型的配置方式请参阅[快速开始](https://agentic-ai-java.github.io/argi-website/docs/quick-start)。

## 项目模块

| 模块 | 说明 |
| --- | --- |
| [API 与 Adapter](argi-api) | 中立 ChatClient 契约及 Spring AI、LangChain4j、Google ADK、AgentScope 接入 |
| [Agent Framework](argi-agent-framework) | 智能体开发与多智能体编排 |
| [Graph Core](argi-graph-core) | 状态管理、持久化和工作流运行时 |
| [Studio](argi-studio) | Agent 可视化调试界面 |
| [Sandbox](https://github.com/agentic-ai-java/argi-extensions/tree/main/sandbox/argi-sandbox) | 工具调用的可选隔离执行环境，由 Extensions 维护 |
| [Spring Boot Starters](argi-spring-boot-starter) | 内置图节点、图可观测性与四个 Adapter 的自动配置 |
| [Extensions](https://github.com/agentic-ai-java/argi-extensions) | 模型与文档契约、A2A、Nacos、AgentScope、存储等可选扩展 |
| [Examples](https://github.com/agentic-ai-java/argi-examples/tree/main/examples) | Chatbot、多智能体、图工程和文档示例 |
| [Benchmark](benchmark) | [ARGI 基准测试项目](https://github.com/agentic-ai-java/argi-benchmark) |

## 文档

- [项目概览](https://agentic-ai-java.github.io/argi-website/docs/overview)
- [快速开始](https://agentic-ai-java.github.io/argi-website/docs/quick-start)
- [Agent Framework 教程](https://agentic-ai-java.github.io/argi-website/docs/frameworks/agent-framework/tutorials/agents)
- [Graph Core 快速开始](https://agentic-ai-java.github.io/argi-website/docs/frameworks/graph-core/quick-start)
- [示例项目](https://github.com/agentic-ai-java/argi-examples/tree/main/examples)
- [模型厂商相关示例](https://github.com/agentic-ai-java/argi-extensions/tree/main/examples)

## 参与贡献

提交代码前请阅读[贡献指南](CONTRIBUTING-zh.md)。问题和建议可通过 [GitHub Issues](https://github.com/agentic-ai-java/argi/issues) 反馈。

<a href="https://github.com/agentic-ai-java/argi/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=agentic-ai-java/argi&max=500&columns=18&anon=1" alt="contributors"/>
</a>

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。
