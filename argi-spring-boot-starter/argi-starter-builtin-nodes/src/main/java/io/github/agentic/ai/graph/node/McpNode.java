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

package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.NodeAction;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Node: Node for calling MCP Server
 */
public class McpNode implements NodeAction {

	private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

	private static final Logger log = LoggerFactory.getLogger(McpNode.class);

	private static final int MAX_LOG_VALUE_LENGTH = 128;

	private final String url;

	private final String tool;

	private final Map<String, String> headers;

	private final Map<String, Object> params;

	private final String outputKey;

	private final List<String> inputParamKeys;

	private HttpClientSseClientTransport transport;

	private McpSyncClient client;

	private McpNode(Builder builder) {
		this.url = builder.url;
		this.tool = builder.tool;
		this.headers = builder.headers;
		this.params = builder.params;
		this.outputKey = builder.outputKey;
		this.inputParamKeys = builder.inputParamKeys;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		log.info(
				"[McpNode] Start executing apply, original configuration: url={}, tool={}, headers={}, inputParamKeys={}",
				redactUrlForLogging(url), tool, redactForLogging(headers), inputParamKeys);

		// Build transport and client
		String baseUrl = this.url;
		String sseEndpoint = "/sse";
		if (this.url.contains("/sse?")) {
			int idx = this.url.indexOf("/sse?");
			baseUrl = this.url.substring(0, idx);
			sseEndpoint = this.url.substring(idx); // e.g. /sse?key=xxx
		}
		HttpClientSseClientTransport.Builder transportBuilder = HttpClientSseClientTransport.builder(baseUrl)
			.sseEndpoint(sseEndpoint);
		if (this.headers != null && !this.headers.isEmpty()) {
			transportBuilder.httpRequestCustomizer(
					(requestBuilder, method, uri, body, context) -> this.headers.forEach(requestBuilder::header));
		}
		this.transport = transportBuilder.build();
		this.client = McpClient.sync(this.transport).build();
		InitializeResult initializeResult = this.client.initialize();
		log.info("[McpNode] MCP Client initialized: protocolVersion={}, capabilities={}",
				initializeResult.protocolVersion(), initializeResult.capabilities());
		// Variable replacement
		String finalTool = replaceVariables(tool, state);
		Map<String, Object> finalParams = new HashMap<>();
		// 1. First read from inputParamKeys
		if (inputParamKeys != null) {
			for (String key : inputParamKeys) {
				Object value = state.value(key).orElse(null);
				if (value != null) {
					finalParams.put(key, value);
				}
			}
		}
		// 2. Then use params (after variable replacement) to overwrite
		Map<String, Object> replacedParams = replaceVariablesObj(params, state);
		if (replacedParams != null) {
			finalParams.putAll(replacedParams);
		}
		log.info("[McpNode] after replace params: url={}, tool={}, headers={}, params={}", redactUrlForLogging(url),
				finalTool, redactForLogging(headers), redactForLogging(finalParams));

		// Directly use the already initialized client
		CallToolResult result;
		try {
			McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(finalTool, finalParams);
			log.info("[McpNode] CallToolRequest: tool={}, arguments={}", request.name(),
					redactForLogging(request.arguments()));
			result = client.callTool(request);
			log.info("[McpNode] tool call result: contentCount={}, isError={}",
					result.content() != null ? result.content().size() : 0, result.isError());
		}
		catch (Exception e) {
			log.error("[McpNode] MCP call fail:", e);
			throw new McpNodeException("MCP call fail: " + e.getMessage(), e);
		}

		// Result handling
		Map<String, Object> updatedState = new HashMap<>();
		// updatedState.put("mcp_result", result.content());
		updatedState.put("messages", result.content());
		if (StringUtils.hasLength(this.outputKey)) {
			Object content = result.content();
			if (content instanceof List<?> list && !CollectionUtils.isEmpty(list)) {
				Object first = list.get(0);
				// Compatible with the text field of TextContent
				if (first instanceof TextContent textContent) {
					updatedState.put(this.outputKey, textContent.text());
				}
				else if (first instanceof Map<?, ?> map && map.containsKey("text")) {
					updatedState.put(this.outputKey, map.get("text"));
				}
				else {
					updatedState.put(this.outputKey, first);
				}
			}
			else {
				updatedState.put(this.outputKey, content);
			}
		}
		log.info("[McpNode] update state keys: {}", updatedState.keySet());
		return updatedState;
	}

	private String replaceVariables(String template, OverAllState state) {
		if (template == null)
			return null;
		Matcher matcher = VARIABLE_PATTERN.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String key = matcher.group(1);
			Object value = state.value(key).orElse("");
			log.debug("[McpNode] replace param: {} -> {}", key, redactForLogging(value));
			matcher.appendReplacement(result, value.toString());
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private Map<String, Object> replaceVariablesObj(Map<String, Object> map, OverAllState state) {
		if (map == null)
			return null;
		Map<String, Object> result = new HashMap<>();
		map.forEach((k, v) -> {
			if (v instanceof String) {
				result.put(k, replaceVariables((String) v, state));
			}
			else {
				result.put(k, v);
			}
		});
		return result;
	}

	static Object redactForLogging(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> redacted = new HashMap<>();
			map.forEach((key, entryValue) -> redacted.put(key,
					isSensitiveKey(key) ? "<redacted>" : redactForLogging(entryValue)));
			return redacted;
		}
		if (value instanceof List<?> list) {
			return "List(size=" + list.size() + ")";
		}
		if (value instanceof String text) {
			if (text.length() <= MAX_LOG_VALUE_LENGTH) {
				return text;
			}
			return text.substring(0, MAX_LOG_VALUE_LENGTH) + "...<truncated>";
		}
		return value;
	}

	static String redactUrlForLogging(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		try {
			URI uri = URI.create(value);
			if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
				return "<invalid-url>";
			}
			String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
			StringBuilder safe = new StringBuilder(uri.getScheme()).append("://").append(host);
			if (uri.getPort() >= 0) {
				safe.append(':').append(uri.getPort());
			}
			if (uri.getRawPath() != null) {
				safe.append(uri.getRawPath());
			}
			return safe.toString();
		}
		catch (IllegalArgumentException ex) {
			return "<invalid-url>";
		}
	}

	private static boolean isSensitiveKey(Object key) {
		if (key == null) {
			return false;
		}
		String normalized = key.toString().toLowerCase(Locale.ROOT);
		return normalized.contains("authorization") || normalized.contains("token") || normalized.contains("secret")
				|| normalized.contains("password") || normalized.contains("api-key") || normalized.contains("apikey")
				|| normalized.endsWith("key");
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String url;

		private String tool;

		private Map<String, String> headers = new HashMap<>();

		private Map<String, Object> params = new HashMap<>();

		private String outputKey;

		private List<String> inputParamKeys;

		public Builder url(String url) {
			this.url = url;
			return this;
		}

		public Builder tool(String tool) {
			this.tool = tool;
			return this;
		}

		public Builder header(String name, String value) {
			this.headers.put(name, value);
			return this;
		}

		public Builder param(String name, Object value) {
			this.params.put(name, value);
			return this;
		}

		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		public Builder inputParamKeys(List<String> inputParamKeys) {
			this.inputParamKeys = inputParamKeys;
			return this;
		}

		public McpNode build() {
			return new McpNode(this);
		}

	}

	public static class McpNodeException extends RuntimeException {

		public McpNodeException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
