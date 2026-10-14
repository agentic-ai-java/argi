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

package io.github.agentic.ai;

import io.github.agentic.ai.agentic.AgentStaticLoader;
import io.github.agentic.ai.graph.GraphTestConfig;
import io.github.agentic.ai.graph.SimpleGraphConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpRequest;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Unified Studio application that supports both Graph and Agent APIs.
 * <ul>
 * <li><b>Graph</b>: Loads {@link SimpleGraphConfig} (simple_workflow) via
 * {@link GraphTestConfig}.</li>
 * <li><b>Agent</b>: Loads {@link AgentStaticLoader} (single_agent, research_agent).</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "io.github.agentic.ai")
@Import({ GraphTestConfig.class, SimpleGraphConfig.class })
public class StudioApplication {

	public static void main(String[] args) {
		SpringApplication.run(StudioApplication.class, args);
	}

	@Bean
	public McpSyncHttpClientRequestCustomizer mcpSyncHttpClientRequestCustomizer() {
		return new McpSyncHttpClientRequestCustomizer() {
			@Override
			public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
					McpTransportContext context) {
				String apiKey = System.getenv("ARGI_JINA_API_KEY");
				if (apiKey != null && !apiKey.isBlank()) {
					builder.header("Authorization", "Bearer " + apiKey);
				}
				builder.timeout(java.time.Duration.ofSeconds(120));
			}
		};
	}

}
