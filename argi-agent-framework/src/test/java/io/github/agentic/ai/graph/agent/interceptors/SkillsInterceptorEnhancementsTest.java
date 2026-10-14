/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.agent.hook.skills.ReadSkillTool;
import io.github.agentic.ai.graph.agent.hook.skills.SkillsAgentHook;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.skills.SkillsInterceptor;
import io.github.agentic.ai.graph.agent.node.AgentLlmNode;
import io.github.agentic.ai.graph.agent.tools.PoetTool;
import io.github.agentic.ai.graph.skills.registry.SkillRegistry;
import io.github.agentic.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillsInterceptorEnhancementsTest {

	static class MockChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(of(new Generation(new AssistantMessage("Mock response"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(new ChatResponse(of(new Generation(new AssistantMessage("Mock stream response")))));
		}

	}

	@TempDir
	Path tempDir;

	private SkillRegistry registry;

	private Path skillDir;

	@BeforeEach
	void setUp() throws Exception {
		Path skillsDir = tempDir.resolve("skills");
		skillDir = skillsDir.resolve("allowed-tools-test");
		Files.createDirectories(skillDir);
		Files.writeString(skillDir.resolve("SKILL.md"), """
				---
				name: allowed-tools-test
				description: Skill fixture for interceptor enhancement tests.
				allowed_tools:
				  - record_result
				---

				# Allowed Tools Test

				Interceptor test fixture.
				""");
		registry = FileSystemSkillRegistry.builder().projectSkillsDirectory(skillsDir.toString()).build();
	}

	@Test
	void readingSkillByPathActivatesAllowedToolsAndDeduplicatesDynamicBindings() {
		Tool recordResultTool = tool("record_result", "recorded");

		SkillsInterceptor interceptor = SkillsInterceptor.builder()
			.skillRegistry(registry)
			.groupedTools(Map.of("allowed-tools-test", new ToolRegistry(List.of(recordResultTool))))
			.toolRegistry(new ToolRegistry(List.of(recordResultTool)))
			.build();

		ToolCall readSkillCall = new ToolCall("call-1", ReadSkillTool.READ_SKILL,
				"{\"skill_path\":\"%s\"}".formatted(skillDir.toString().replace("\\", "\\\\")));
		ChatMessage assistantMessage = ChatMessage.assistant("", List.of(readSkillCall));
		ModelRequest request = ModelRequest.builder()
			.messages(List.of(assistantMessage))
			.dynamicToolRegistry(new ToolRegistry(List.of(recordResultTool)))
			.context(Map.of())
			.build();

		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, modified -> {
			captured.set(modified);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("ok"));
		});

		assertNotNull(captured.get());
		assertEquals(List.of("record_result"),
				captured.get()
					.getDynamicToolRegistry()
					.definitions()
					.stream()
					.map(ToolDefinition::name)
					.distinct()
					.toList());
		assertEquals(1, captured.get().getDynamicToolRegistry().definitions().size());
		assertThat(captured.get().getDynamicToolRegistry().find("record_result").orElseThrow())
			.isSameAs(recordResultTool);
	}

	@Test
	void reactAgentBuilderDeduplicatesStaticToolsByName() throws Exception {
		ToolCallback tool1 = PoetTool.createPoetToolCallback("duplicate_tool", new PoetTool());
		ToolCallback tool2 = PoetTool.createPoetToolCallback("duplicate_tool", new PoetTool());

		ReactAgent agent = ReactAgent.builder()
			.name("dedup-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new MockChatModel()))
			.hooks(new io.github.agentic.ai.graph.agent.hook.AgentHook() {
				@Override
				public String getName() {
					return "duplicate-provider";
				}

				@Override
				public List<Tool> getTools() {
					return List.of(new io.github.agentic.ai.adapter.springai.SpringAiToolAdapter(tool1));
				}
			})
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(tool2))
			.build();

		Field llmNodeField = ReactAgent.class.getDeclaredField("llmNode");
		llmNodeField.setAccessible(true);
		AgentLlmNode llmNode = (AgentLlmNode) llmNodeField.get(agent);

		Field toolCallbacksField = AgentLlmNode.class.getDeclaredField("toolRegistry");
		toolCallbacksField.setAccessible(true);
		@SuppressWarnings("unchecked")
		io.github.agentic.ai.api.tool.ToolRegistry registry = (io.github.agentic.ai.api.tool.ToolRegistry) toolCallbacksField
			.get(llmNode);

		assertEquals(1, registry.definitions().size());
		assertEquals("duplicate_tool", registry.definitions().get(0).name());
	}

	@Test
	void hookRetainsInitiallyEmptyGroupedToolsForLaterUpdates() {
		Map<String, ToolRegistry> groupedTools = new ConcurrentHashMap<>();
		SkillsAgentHook hook = SkillsAgentHook.builder().skillRegistry(registry).groupedTools(groupedTools).build();
		SkillsInterceptor interceptor = (SkillsInterceptor) hook.getModelInterceptors().get(0);
		assertTrue(interceptor.getGroupedTools().isEmpty());

		Tool tool = tool("late_tool", "ok");
		groupedTools.put("allowed-tools-test", new ToolRegistry(List.of(tool)));
		assertThat(interceptor.getGroupedTools().get("allowed-tools-test").find("late_tool").orElseThrow())
			.isSameAs(tool);
		Map<String, ToolRegistry> snapshot = interceptor.getGroupedTools();
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
		groupedTools.clear();
		assertThat(snapshot).containsKey("allowed-tools-test");
		assertThat(interceptor.getGroupedTools()).isEmpty();
	}

	@Test
	void allowedToolsActivateWithoutAGroupAndKeepExistingBindingsFirst() {
		Tool existing = tool("record_result", "existing");
		Tool grouped = tool("record_result", "grouped");
		Tool allowed = tool("record_result", "allowed");
		SkillsInterceptor interceptor = SkillsInterceptor.builder()
			.skillRegistry(registry)
			.toolRegistry(new ToolRegistry(List.of(allowed)))
			.build();
		ModelRequest request = readRequest("{\"skill_name\":\"allowed-tools-test\"}");
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, modified -> {
			captured.set(modified);
			return ModelResponse.of(ChatMessage.assistant("ok"));
		});
		assertThat(captured.get().getDynamicToolRegistry().find("record_result").orElseThrow()).isSameAs(allowed);
		assertThat(request.getDynamicToolRegistry().definitions()).isEmpty();

		interceptor = SkillsInterceptor.builder()
			.skillRegistry(registry)
			.groupedTools(Map.of("allowed-tools-test", new ToolRegistry(List.of(grouped))))
			.toolRegistry(new ToolRegistry(List.of(allowed)))
			.build();
		interceptor.interceptModel(request, modified -> {
			assertThat(modified.getDynamicToolRegistry().find("record_result").orElseThrow()).isSameAs(grouped);
			return ModelResponse.of(ChatMessage.assistant("ok"));
		});
		interceptor.interceptModel(
				ModelRequest.builder(request).dynamicToolRegistry(new ToolRegistry(List.of(existing))).build(),
				modified -> {
					assertThat(modified.getDynamicToolRegistry().find("record_result").orElseThrow())
						.isSameAs(existing);
					return ModelResponse.of(ChatMessage.assistant("ok"));
				});
	}

	@Test
	void invalidOrUnreadSkillsDoNotDiscloseToolsAndUnknownAllowedBindingsAreSkipped() {
		SkillsInterceptor interceptor = SkillsInterceptor.builder()
			.skillRegistry(registry)
			.toolRegistry(new ToolRegistry(List.of(tool("record_result", "ok"))))
			.build();
		for (String arguments : List.of("{}", "not-json", "{\"skill_name\":\"missing\"}",
				"{\"skill_name\":\"allowed-tools-test\",\"skill_path\":\"missing\"}")) {
			interceptor.interceptModel(readRequest(arguments), modified -> {
				assertThat(modified.getDynamicToolRegistry().definitions()).isEmpty();
				return ModelResponse.of(ChatMessage.assistant("ok"));
			});
		}
		interceptor.interceptModel(
				ModelRequest.builder().messages(List.of(ChatMessage.user("allowed-tools-test"))).build(), modified -> {
					assertThat(modified.getDynamicToolRegistry().definitions()).isEmpty();
					return ModelResponse.of(ChatMessage.assistant("ok"));
				});
		SkillsInterceptor.builder()
			.skillRegistry(registry)
			.build()
			.interceptModel(readRequest("{\"skill_name\":\"allowed-tools-test\"}"), modified -> {
				assertThat(modified.getDynamicToolRegistry().definitions()).isEmpty();
				return ModelResponse.of(ChatMessage.assistant("ok"));
			});
		assertThatThrownBy(() -> SkillsInterceptor.builder().toolRegistry(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> SkillsAgentHook.builder().toolRegistry(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void skillActivationExecutesNeutralDynamicToolInACompleteReactLoop() throws Exception {
		var turns = new AtomicInteger();
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			int turn = turns.getAndIncrement();
			if (turn == 0) {
				assertThat(request.tools()).extracting(ToolDefinition::name).doesNotContain("record_result");
				return response("", List
					.of(new ToolCall("read", ReadSkillTool.READ_SKILL, "{\"skill_name\":\"allowed-tools-test\"}")));
			}
			assertThat(request.tools()).extracting(ToolDefinition::name).contains("record_result");
			if (turn == 1) {
				return response("", List.of(new ToolCall("record", "record_result", "{}")));
			}
			assertThat(request.messages()).filteredOn(message -> message.role() == ChatRole.TOOL)
				.anySatisfy(message -> assertThat(message.text()).isEqualTo("neutral-result"));
			return response("done", List.of());
		});
		SkillsAgentHook hook = SkillsAgentHook.builder()
			.skillRegistry(registry)
			.toolRegistry(new ToolRegistry(List.of(tool("record_result", "neutral-result"))))
			.build();
		ReactAgent agent = ReactAgent.builder().name("skills-neutral-loop").chatClient(client).hooks(hook).build();
		assertThat(agent.call("Use the skill").text()).isEqualTo("done");
		assertThat(turns).hasValue(3);
	}

	@Test
	void skillToolConfigurationAndInterceptorSignaturesDoNotExposeModelFrameworkTypes() {
		for (Class<?> type : List.of(SkillsInterceptor.class, SkillsInterceptor.Builder.class,
				SkillsAgentHook.Builder.class)) {
			for (var method : type.getDeclaredMethods()) {
				if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
					assertThat(method.toGenericString()).doesNotContain("org.springframework.ai.");
				}
			}
		}
	}

	private static ModelRequest readRequest(String arguments) {
		return ModelRequest.builder()
			.messages(List
				.of(ChatMessage.assistant("", List.of(new ToolCall("read", ReadSkillTool.READ_SKILL, arguments)))))
			.build();
	}

	private static CompletableFuture<io.github.agentic.ai.api.chat.ChatResponse> response(String text,
			List<ToolCall> calls) {
		return CompletableFuture.completedFuture(new io.github.agentic.ai.api.chat.ChatResponse(null, null,
				List.of(ChatMessage.assistant(text)), calls, null, null, Map.of()));
	}

	private static Tool tool(String name, String result) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, "Skill tool fixture", "{}");
			}

			@Override
			public CompletableFuture<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text(result));
			}
		};
	}

}
