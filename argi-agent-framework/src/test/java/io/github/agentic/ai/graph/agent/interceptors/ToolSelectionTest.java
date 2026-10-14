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
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionInterceptor;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.graph.agent.tools.HotelTool;
import io.github.agentic.ai.graph.agent.tools.TicketTool;
import io.github.agentic.ai.graph.agent.tools.WeatherTool;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.agentic.ai.graph.agent.support.EnabledForRealModel;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledForRealModel
class ToolSelectionTest {

	private ChatModel chatModel;

	private ChatClient selectionClient;

	private static CompileConfig getCompileConfig() {
		SaverConfig saverConfig = SaverConfig.builder().register(new MemorySaver()).build();
		CompileConfig compileConfig = CompileConfig.builder().saverConfig(saverConfig).build();
		return compileConfig;
	}

	@BeforeEach
	void setUp() {
		this.chatModel = OpenAiCompatibleTestModels.chatModel();
		this.selectionClient = OpenAiCompatibleTestModels.chatClient();
	}

	@Test
	public void testToolSelectionWithMaxTools() throws Exception {

		// Create tool selection interceptor that limits to 2 tools
		ToolSelectionInterceptor toolSelectionInterceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(1)
			.build();

		// Create multiple tools
		WeatherTool weatherTool = new WeatherTool();
		TicketTool ticketTool = new TicketTool();
		HotelTool hotelTool = new HotelTool();

		ToolCallback weatherToolCallback = WeatherTool.createWeatherTool("weather_tool", weatherTool);
		ToolCallback ticketToolCallback = TicketTool.createTicketToolCallback("ticket_booking_tool", ticketTool);
		ToolCallback hotelToolCallback = HotelTool.createHotelTool("hotel_booking_tool", hotelTool);

		ReactAgent agent = ReactAgent.builder()
			.name("tool_selection_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherToolCallback,
					ticketToolCallback, hotelToolCallback))
			.interceptors(toolSelectionInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("帮我查询北京天气，预订一张去北京的车票？");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			result.ifPresent(overAllState -> {
				List<Message> messages = overAllState.value("messages", new ArrayList<>());

				// Verify that the agent got a response
				boolean hasAssistantMessage = messages.stream().anyMatch(msg -> msg instanceof AssistantMessage);

				assertTrue(hasAssistantMessage, "Messages should contain at least one AssistantMessage");

			});

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

	@Test
	public void testToolSelectionWithAlwaysInclude() throws Exception {

		// Create tool selection interceptor with always include
		ToolSelectionInterceptor toolSelectionInterceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(1)
			.alwaysInclude("weather_tool")
			.build();

		WeatherTool weatherTool = new WeatherTool();
		TicketTool ticketTool = new TicketTool();
		HotelTool hotelTool = new HotelTool();

		ToolCallback weatherToolCallback = WeatherTool.createWeatherTool("weather_tool", weatherTool);
		ToolCallback ticketToolCallback = TicketTool.createTicketToolCallback("ticket_booking_tool", ticketTool);
		ToolCallback hotelToolCallback = HotelTool.createHotelTool("hotel_booking_tool", hotelTool);

		ReactAgent agent = ReactAgent.builder()
			.name("always_include_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherToolCallback,
					ticketToolCallback, hotelToolCallback))
			.interceptors(toolSelectionInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("帮我预订一张去上海的车票");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			result.ifPresent(overAllState -> {
				List<Message> messages = overAllState.value("messages", new ArrayList<>());

				// Verify assistant responded
				boolean hasAssistantMessage = messages.stream().anyMatch(msg -> msg instanceof AssistantMessage);

				assertTrue(hasAssistantMessage, "Messages should contain at least one AssistantMessage");
			});

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

	@Test
	public void testToolSelectionRelevanceFiltering() throws Exception {

		// Create tool selection interceptor
		ToolSelectionInterceptor toolSelectionInterceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(2)
			.build();

		WeatherTool weatherTool = new WeatherTool();
		TicketTool ticketTool = new TicketTool();
		HotelTool hotelTool = new HotelTool();

		ToolCallback weatherToolCallback = WeatherTool.createWeatherTool("weather_tool", weatherTool);
		ToolCallback ticketToolCallback = TicketTool.createTicketToolCallback("ticket_booking_tool", ticketTool);
		ToolCallback hotelToolCallback = HotelTool.createHotelTool("hotel_booking_tool", hotelTool);

		ReactAgent agent = ReactAgent.builder()
			.name("relevance_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherToolCallback,
					ticketToolCallback, hotelToolCallback))
			.interceptors(toolSelectionInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			// Query that requires ticket and hotel but not weather
			Optional<OverAllState> result = agent.invoke("帮我预订12月30日去北京的车票和酒店");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			result.ifPresent(overAllState -> {
				List<Message> messages = overAllState.value("messages", new ArrayList<>());

				// Verify assistant responded
				boolean hasAssistantMessage = messages.stream().anyMatch(msg -> msg instanceof AssistantMessage);

				assertTrue(hasAssistantMessage, "Messages should contain at least one AssistantMessage");

				// At least one of ticket or hotel tool should be called
				int relevantToolCalls = ticketTool.counter + hotelTool.counter;
				assertTrue(relevantToolCalls > 0, "At least one relevant tool (ticket or hotel) should be called");
			});

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

	@Test
	public void testToolSelectionWithFewTools() throws Exception {

		// Create tool selection interceptor with max 5 tools
		ToolSelectionInterceptor toolSelectionInterceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(5)
			.build();

		// Only provide 2 tools (less than maxTools)
		WeatherTool weatherTool = new WeatherTool();
		TicketTool ticketTool = new TicketTool();

		ToolCallback weatherToolCallback = WeatherTool.createWeatherTool("weather_tool", weatherTool);
		ToolCallback ticketToolCallback = TicketTool.createTicketToolCallback("ticket_booking_tool", ticketTool);

		ReactAgent agent = ReactAgent.builder()
			.name("few_tools_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(weatherToolCallback,
					ticketToolCallback))
			.interceptors(toolSelectionInterceptor)
			.saver(new MemorySaver())
			.build();

		try {
			Optional<OverAllState> result = agent.invoke("今天深圳的天气如何？");

			// Assert that the result is present
			assertTrue(result.isPresent(), "Agent result should be present");

			// When tools count <= maxTools, selection should be skipped
			// Both tools should be available to the model
			assertTrue(weatherTool.counter > 0, "Weather tool should be called when selection is skipped");

		}
		catch (java.util.concurrent.CompletionException e) {
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

}
