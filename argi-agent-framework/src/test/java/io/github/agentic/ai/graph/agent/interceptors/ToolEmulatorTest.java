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

import io.github.agentic.ai.graph.agent.support.OpenAiCompatibleTestModels;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.interceptor.toolemulator.ToolEmulatorInterceptor;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.graph.agent.tools.WeatherTool;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledForRealModel
class ToolEmulatorTest {

	private ChatModel chatModel;

	private ChatClient emulatorClient;

	private static CompileConfig getCompileConfig() {
		SaverConfig saverConfig = SaverConfig.builder().register(new MemorySaver()).build();
		CompileConfig compileConfig = CompileConfig.builder().saverConfig(saverConfig).build();
		return compileConfig;
	}

	@BeforeEach
	void setUp() {
		this.chatModel = OpenAiCompatibleTestModels.chatModel();
		this.emulatorClient = OpenAiCompatibleTestModels.chatClient();
	}

	@Test
	public void testToolEmulatorWithWeatherTool() throws Exception {

		// Create tool emulator interceptor that emulates the weather tool
		ToolEmulatorInterceptor toolEmulatorInterceptor = ToolEmulatorInterceptor.builder()
			.chatClient(emulatorClient)
			.addTool("weather_tool")
			.build();

		WeatherTool weatherTool = new WeatherTool();
		ToolCallback weatherToolCallback = WeatherTool.createWeatherTool("weather_tool", weatherTool);

		ReactAgent agent = ReactAgent.builder()
			.name("emulator_test_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherToolCallback))
			.interceptors(toolEmulatorInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("今天杭州的天气怎么样？");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			result.ifPresent(overAllState -> {
				List<Message> messages = overAllState.value("messages", new ArrayList<>());

				// Assert that messages contain at least one ToolResponseMessage with name
				// "weather_tool"
				boolean hasWeatherToolResponse = messages.stream()
					.filter(msg -> msg instanceof ToolResponseMessage)
					.map(msg -> (ToolResponseMessage) msg)
					.flatMap(toolMsg -> toolMsg.getResponses().stream())
					.anyMatch(response -> "weather_tool".equals(response.name()));

				assertTrue(hasWeatherToolResponse,
						"Messages should contain at least one ToolResponseMessage with ToolResponse name 'weather_tool'");

				// Verify the weather tool was NOT actually called (count should be 0
				// because it was emulated)
				assertEquals(0, weatherTool.counter, "Weather tool should not be actually called when emulated");
			});

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

	@Test
	public void testToolEmulatorEmulateAllTools() throws Exception {

		// Create tool emulator that emulates all tools by default
		ToolEmulatorInterceptor toolEmulatorInterceptor = ToolEmulatorInterceptor.builder()
			.chatClient(emulatorClient)
			.emulateAllTools(true)
			.build();

		WeatherTool weatherTool = new WeatherTool();
		ToolCallback weatherToolCallback = WeatherTool.createWeatherTool("weather_tool", weatherTool);

		ReactAgent agent = ReactAgent.builder()
			.name("emulator_all_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherToolCallback))
			.interceptors(toolEmulatorInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("上海的天气如何？");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			// Verify the weather tool was NOT actually called (emulated instead)
			assertEquals(0, weatherTool.counter, "Weather tool should not be called when emulate all is enabled");

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

	@Test
	public void testToolEmulatorSelectiveEmulation() throws Exception {

		// Create tool emulator that only emulates specific tools (not weather_tool)
		ToolEmulatorInterceptor toolEmulatorInterceptor = ToolEmulatorInterceptor.builder()
			.chatClient(emulatorClient)
			.emulateAllTools(false)
			.build();

		WeatherTool weatherTool = new WeatherTool();
		ToolCallback weatherToolCallback = WeatherTool.createWeatherTool("weather_tool", weatherTool);

		ReactAgent agent = ReactAgent.builder()
			.name("selective_emulator_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherToolCallback))
			.interceptors(toolEmulatorInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("深圳今天天气怎样？");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			// Verify the weather tool WAS actually called (not in emulation list)
			assertTrue(weatherTool.counter > 0, "Weather tool should be called when not in emulation list");

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

}
