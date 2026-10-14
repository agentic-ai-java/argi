/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.agent.hook.AgentHook;
import io.github.agentic.ai.graph.agent.hook.ToolInjection;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.node.AgentToolNode;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReactAgentToolRegistryTest {

	@Test
	void builtInFactoriesExposeOnlyNeutralToolBindings() {
		for (Class<?> type : List.of(io.github.agentic.ai.graph.agent.extension.tools.filesystem.ReadFileTool.class,
				io.github.agentic.ai.graph.agent.extension.tools.filesystem.WriteFileTool.class,
				io.github.agentic.ai.graph.agent.extension.tools.filesystem.EditFileTool.class,
				io.github.agentic.ai.graph.agent.extension.tools.filesystem.ListFilesTool.class,
				io.github.agentic.ai.graph.agent.extension.tools.filesystem.GlobTool.class,
				io.github.agentic.ai.graph.agent.extension.tools.filesystem.GrepTool.class,
				io.github.agentic.ai.graph.agent.hook.skills.ReadSkillTool.class,
				io.github.agentic.ai.graph.agent.hook.skills.SearchSkillsTool.class,
				io.github.agentic.ai.graph.agent.hook.skills.DisableSkillTool.class,
				io.github.agentic.ai.graph.agent.extension.tools.model.TaskTool.class,
				io.github.agentic.ai.graph.agent.tools.ShellTool.Builder.class,
				io.github.agentic.ai.graph.agent.tools.WebFetchTool.Builder.class,
				io.github.agentic.ai.graph.agent.tools.WriteTodosTool.Builder.class,
				io.github.agentic.ai.graph.agent.tools.GlobSearchTool.Builder.class,
				io.github.agentic.ai.graph.agent.tools.GrepSearchTool.Builder.class,
				io.github.agentic.ai.graph.agent.tools.task.TaskTool.Builder.class,
				io.github.agentic.ai.graph.agent.tools.task.TaskOutputTool.Builder.class)) {
			var factories = List.of(type.getDeclaredMethods())
				.stream()
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.filter(method -> method.getName().startsWith("create") || method.getName().equals("build"))
				.toList();
			assertThat(factories).isNotEmpty();
			for (var method : factories) {
				assertThat(method.getReturnType()).isEqualTo(Tool.class);
				assertThat(method.getName()).doesNotContain("ToolCallback");
			}
		}
	}

	@Test
	void builtInFileToolsExecuteThroughTheNeutralRegistry(@TempDir Path directory) {
		var write = io.github.agentic.ai.graph.agent.extension.tools.filesystem.WriteFileTool
			.createWriteFileTool("Write a file");
		var read = io.github.agentic.ai.graph.agent.extension.tools.filesystem.ReadFileTool
			.createReadFileTool("Read a file");
		var registry = new ToolRegistry(List.of(write, read));
		var path = directory.resolve("test.txt");
		var arguments = new com.fasterxml.jackson.databind.ObjectMapper()
			.valueToTree(Map.of("file_path", path.toString(), "content", "neutral tools"));
		var written = registry
			.execute(new ToolCall("write", "write_file", arguments.toString()), new ToolContext(Map.of()))
			.toCompletableFuture()
			.join();
		assertThat(written.toMessage(new ToolCall("write", "write_file", "{}")).text())
			.contains("Successfully created file");
		var result = registry
			.execute(new ToolCall("read", "read_file",
					new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("file_path", path.toString()))
						.toString()),
					new ToolContext(Map.of()))
			.toCompletableFuture()
			.join();
		assertThat(result.toMessage(new ToolCall("read", "read_file", "{}")).text()).contains("neutral tools");
	}

	@Test
	void registryOnlyRegistrationReachesTheModelWithoutANativeResolver() throws Exception {
		var request = new AtomicReference<ChatRequest>();
		ReactAgent.builder()
			.name("registry-agent")
			.chatClient(client(request))
			.toolRegistry(new ToolRegistry(List.of(new Binding("lookup"))))
			.build()
			.call("question");
		assertThat(request.get().tools()).extracting(ToolDefinition::name).containsExactly("lookup");
	}

	@Test
	void hookThenInterceptorThenApplicationBindingsWinByName() throws Exception {
		var hookBinding = new Binding("shared");
		var interceptorBinding = new Binding("interceptor");
		var hook = new InjectionHook(null, null, List.of(hookBinding));
		var interceptor = new ModelInterceptor() {
			@Override
			public String getName() {
				return "tool-provider";
			}

			@Override
			public List<Tool> getTools() {
				return List.of(new Binding("shared"), interceptorBinding);
			}

			@Override
			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				return next.call(request);
			}
		};
		var request = new AtomicReference<ChatRequest>();
		var agent = ReactAgent.builder()
			.name("precedence-agent")
			.chatClient(client(request))
			.hooks(hook)
			.interceptors(interceptor)
			.toolRegistry(new ToolRegistry(
					List.of(new Binding("shared"), new Binding("interceptor"), new Binding("application"))))
			.build();
		agent.call("question");
		assertThat(request.get().tools()).extracting(ToolDefinition::name)
			.containsExactly("shared", "interceptor", "application");
		assertThat(hook.injected).isSameAs(hookBinding);
		var builder = new DefaultBuilder();
		builder.hooks(hook)
			.interceptors(interceptor)
			.toolRegistry(new ToolRegistry(List.of(new Binding("application"))));
		builder.separateInterceptorsByType();
		assertThat(builder.gatherLocalTools()).containsExactly(hookBinding, interceptorBinding,
				builder.toolRegistry.find("application").orElseThrow());
	}

	@Test
	void neutralToolInjectionMatchesNameTypeFallbackAndMissingRequirements() {
		var first = new Binding("first");
		var second = new Binding("second");
		for (var hook : List.of(new InjectionHook("second", null, List.of()),
				new InjectionHook(null, Binding.class, List.of()), new InjectionHook(null, null, List.of()),
				new InjectionHook("missing", null, List.of()),
				new InjectionHook("missing", Binding.class, List.of()))) {
			ReactAgent.builder()
				.name("injection-agent")
				.chatClient(client(new AtomicReference<>()))
				.hooks(hook)
				.toolRegistry(new ToolRegistry(List.of(first, second)))
				.build()
				.getGraph();
			if ("second".equals(hook.requiredName)) {
				assertThat(hook.injected).isSameAs(second);
			}
			else if (hook.requiredType != null || hook.requiredName == null) {
				assertThat(hook.injected).isSameAs(first);
			}
			else {
				assertThat(hook.injected).isNull();
			}
		}
	}

	@Test
	void nodeReturnsAnImmutableCompleteNeutralSnapshot() {
		var binding = new Binding("lookup");
		var node = AgentToolNode.builder().toolRegistry(new ToolRegistry(List.of(binding))).build();
		assertThat(node.getTools()).containsExactly(binding);
		assertThatThrownBy(() -> node.getTools().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void publicRegistrationAndInjectionHaveNoSpringAiToolTypesOrLegacyAliases() {
		for (Class<?> type : List.of(Builder.class, DefaultBuilder.class, AgentToolNode.class,
				AgentToolNode.Builder.class, io.github.agentic.ai.graph.agent.node.AgentLlmNode.Builder.class,
				io.github.agentic.ai.graph.agent.hook.Hook.class, ToolInjection.class, ModelInterceptor.class)) {
			for (var method : type.getDeclaredMethods()) {
				if (Modifier.isPublic(method.getModifiers())) {
					assertThat(method.toGenericString()).doesNotContain("org.springframework.ai.tool",
							"org.springframework.ai.chat.model.ToolContext");
				}
			}
		}
		assertThat(List.of(Builder.class.getMethods())).extracting(java.lang.reflect.Method::getName)
			.doesNotContain("tools", "methodTools", "toolCallbackProviders", "toolNames", "resolver",
					"toolExecutionExceptionProcessor");
	}

	private static ChatClient client(AtomicReference<ChatRequest> request) {
		var client = mock(ChatClient.class, CALLS_REAL_METHODS);
		when(client.capabilities()).thenReturn(new ChatClientCapabilities(false, true, false, false, false, false));
		when(client.call(any())).thenAnswer(invocation -> {
			request.set(invocation.getArgument(0));
			return CompletableFuture.completedFuture(new ChatResponse(null, "mock",
					List.of(ChatMessage.assistant("ok")), List.of(), null, null, Map.of()));
		});
		return client;
	}

	private record Binding(String name) implements Tool {
		@Override
		public ToolDefinition definition() {
			return new ToolDefinition(name, "Lookup", "{\"type\":\"object\"}");
		}

		@Override
		public CompletableFuture<ToolResult> execute(ToolCall call, ToolContext context) {
			return CompletableFuture.completedFuture(ToolResult.text("ok"));
		}
	}

	private static final class InjectionHook extends AgentHook implements ToolInjection {

		private final String requiredName;

		private final Class<? extends Tool> requiredType;

		private final List<Tool> tools;

		private Tool injected;

		private InjectionHook(String requiredName, Class<? extends Tool> requiredType, List<Tool> tools) {
			this.requiredName = requiredName;
			this.requiredType = requiredType;
			this.tools = tools;
		}

		@Override
		public String getName() {
			return "injection";
		}

		@Override
		public List<Tool> getTools() {
			return tools;
		}

		@Override
		public String getRequiredToolName() {
			return requiredName;
		}

		@Override
		public Class<? extends Tool> getRequiredToolType() {
			return requiredType;
		}

		@Override
		public void injectTool(Tool tool) {
			this.injected = tool;
		}

	}

}
