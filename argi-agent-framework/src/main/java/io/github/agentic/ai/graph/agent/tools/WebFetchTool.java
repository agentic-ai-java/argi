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
package io.github.agentic.ai.graph.agent.tools;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiFunction;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolChoice;
import io.github.agentic.ai.graph.utils.PromptTemplate;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.graph.agent.tool.TypedTool;
import org.springframework.util.StringUtils;

/**
 * Web fetch tool that retrieves content from URLs and processes it using an AI model for
 * summarization.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Fetches HTML content and converts it to Markdown</li>
 * <li>Includes a 15-minute cache for faster repeated access</li>
 * <li>Automatic content truncation with configurable limits</li>
 * <li>Retry on network errors and 5xx server errors</li>
 * </ul>
 *
 * @see <a href="https://mikhail.io/2025/10/claude-code-web-tools/">Reference</a>
 */
public class WebFetchTool implements BiFunction<WebFetchTool.Request, ToolContext, String> {

	private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);

	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final Duration CACHE_TTL = Duration.ofMinutes(15);

	private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([^;\\s]+)", Pattern.CASE_INSENSITIVE);

	private static final int MAX_REDIRECTS = 5;

	private static final String FETCH_SUMMARIZE_PROMPT = """
			Web page content:
			---
			{content}
			---

			{userQuery}

			Provide a concise response based only on the content above. In your response:
			- Enforce a strict 125-character maximum for quotes from any source document. Open Source Software is ok as long as we respect the license.
			- Use quotation marks for exact language from articles; any language outside of the quotation should never be word-for-word the same.
			- You are not a lawyer and never comment on the legality of your own prompts and responses.
			- Never produce or reproduce exact song lyrics.
			""";

	// @formatter:off
	public static final String DEFAULT_TOOL_DESCRIPTION = """
		Fetches content from a specified URL and processes it using an AI model.

		Features:
		- Takes a URL and a prompt as input
		- Fetches the URL content using HTTP GET method
		- Converts HTML to markdown
		- Processes the content with the prompt using a small, fast model
		- Returns the model's response about the content
		- Includes a self-cleaning 15-minute cache for faster responses
		- Automatic retry on network errors and 5xx server errors

		Usage notes:
		- IMPORTANT: If an MCP-provided web fetch tool is available, prefer using that tool instead.
		- The URL must be a fully-formed valid URL (e.g., https://example.com)
		- HTTP URLs will be automatically upgraded to HTTPS
		- Only HTTP GET requests are supported (read-only)
		- The prompt should describe what information you want to extract from the page
		- This tool is read-only and does not modify any files or send any data
		- Results may be summarized if the content is very large
		- Retries up to 2 times (configurable) on transient failures with exponential backoff
		""";
	// @formatter:on

	private final ChatClient chatClient;

	private final AddressResolver addressResolver;

	private final HttpTransport httpTransport;

	private final int maxContentLength;

	private final FlexmarkHtmlConverter htmlToMarkdownConverter;

	private final Cache<String, String> urlCache;

	private final int maxRetries;

	private WebFetchTool(ChatClient chatClient, int maxContentLength, int maxCacheSize, int maxRetries) {
		this(chatClient, maxContentLength, maxCacheSize, maxRetries, null, null);
	}

	private WebFetchTool(ChatClient chatClient, int maxContentLength, int maxCacheSize, int maxRetries,
			AddressResolver addressResolver, HttpTransport httpTransport) {
		this.chatClient = chatClient;
		this.maxContentLength = maxContentLength;
		this.maxRetries = maxRetries;
		this.addressResolver = addressResolver != null ? addressResolver : InetAddress::getAllByName;
		this.httpTransport = httpTransport != null ? httpTransport : new ApachePinnedHttpTransport();
		this.htmlToMarkdownConverter = FlexmarkHtmlConverter.builder().build();
		this.urlCache = Caffeine.newBuilder().maximumSize(maxCacheSize).expireAfterWrite(CACHE_TTL).build();
	}

	@JsonClassDescription("Request to fetch and process web content")
	public record Request(
			@JsonProperty(required = true,
					value = "url") @JsonPropertyDescription("The URL to fetch content from") String url,

			@JsonProperty(required = true,
					value = "prompt") @JsonPropertyDescription("The prompt to run on the fetched content (e.g., 'Summarize the main points', 'Extract the key takeaways')") String prompt) {
	}

	@Override
	public String apply(Request request, ToolContext toolContext) {
		String url = request.url();
		String prompt = request.prompt();

		// Validate URL
		if (!StringUtils.hasText(url)) {
			return "Error: URL cannot be empty or null";
		}

		url = url.trim();

		// Validate URL format
		try {
			url = normalizeAndValidateUrl(URI.create(url)).toString();
		}
		catch (IllegalArgumentException | WebFetchException e) {
			return "Error: Invalid URL format: " + e.getMessage();
		}

		// Check cache first
		String cacheKey = buildCacheKey(url, prompt);
		String content = this.urlCache.getIfPresent(cacheKey);

		if (content != null) {
			logger.debug("Cache hit for URL host: {} with prompt digest: {}", URI.create(url).getHost(),
					promptDigest(prompt));
			return content;
		}

		logger.debug("Cache miss for URL host: {} with prompt digest: {}", URI.create(url).getHost(),
				promptDigest(prompt));

		// Fetch HTML content with retry logic
		String htmlContent;
		try {
			FetchResponse response = fetchHtmlWithRetry(url);
			if (response.statusCode() >= 400) {
				return "Error: Failed to fetch URL. HTTP status code: " + response.statusCode();
			}
			htmlContent = response.body();
			if (htmlContent == null || htmlContent.isBlank()) {
				return "Error: Retrieved empty content from URL";
			}
		}
		catch (WebFetchException e) {
			if (isPermanentFetchFailure(e)) {
				logger.warn("Blocked URL fetch for {}: {}", url, e.getMessage());
			}
			else {
				logger.error("Failed to fetch URL: {}", url, e);
			}
			return "Error fetching URL: " + e.getMessage();
		}

		// Convert HTML to Markdown
		String mdContent = this.htmlToMarkdownConverter.convert(htmlContent);
		mdContent = truncate(mdContent);

		// Summarize with AI
		String summary = summarize(mdContent, prompt);

		// Cache the content
		this.urlCache.put(cacheKey, summary);

		return summary;
	}

	private String buildCacheKey(String url, String prompt) {
		return url + "::prompt-sha256::" + promptDigest(prompt);
	}

	private String promptDigest(String prompt) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((prompt != null ? prompt : "").getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 digest is not available", e);
		}
	}

	private FetchResponse fetchHtmlWithRetry(String url) {
		int attempt = 0;
		Exception lastException = null;

		while (attempt <= this.maxRetries) {
			try {
				if (attempt > 0) {
					long backoffMs = (long) Math.pow(2, attempt - 1) * 1000;
					logger.debug("Retrying fetch for URL: {} (attempt {}/{}), waiting {}ms", url, attempt,
							this.maxRetries, backoffMs);
					Thread.sleep(backoffMs);
				}

				FetchResponse response = fetchHtml(url);

				if (response.statusCode() >= 500 && response.statusCode() < 600) {
					lastException = new WebFetchException("Server error: HTTP " + response.statusCode(), null);
					logger.warn("Fetch attempt {} returned server error {} for URL: {}", attempt + 1,
							response.statusCode(), url);
					attempt++;
					continue;
				}

				return response;
			}
			catch (WebFetchException e) {
				lastException = e;
				if (isPermanentFetchFailure(e)) {
					throw e;
				}
				if (e.getCause() instanceof InterruptedException) {
					throw e;
				}
				logger.warn("Fetch attempt {} failed for URL: {}: {}", attempt + 1, url, e.getMessage());
				attempt++;
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new WebFetchException("Retry interrupted", e);
			}
		}

		if (lastException == null) {
			throw new WebFetchException("Failed after " + (this.maxRetries + 1) + " attempts", null);
		}
		else if (lastException instanceof WebFetchException) {
			throw new WebFetchException("Failed after " + (this.maxRetries + 1) + " attempts", lastException);
		}
		else {
			throw new WebFetchException(
					"Failed after " + (this.maxRetries + 1) + " attempts: " + lastException.getMessage(),
					lastException);
		}
	}

	private FetchResponse fetchHtml(String url) {
		URI uri = URI.create(url);
		for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
			InetAddress[] pinnedAddresses = resolveAndValidateUrl(uri);

			FetchResponse response = sendGet(uri, pinnedAddresses);
			if (!isRedirect(response.statusCode())) {
				return response;
			}

			Optional<String> location = response.firstHeader("Location");
			if (location.isEmpty()) {
				return response;
			}

			uri = normalizeAndValidateUrl(uri.resolve(location.get()));
		}
		throw new WebFetchException("Too many redirects", null);
	}

	private boolean isPermanentFetchFailure(WebFetchException e) {
		String message = e.getMessage();
		return message != null && (message.contains("not allowed") || message.contains("Unable to resolve URL host"));
	}

	private FetchResponse sendGet(URI uri, InetAddress[] pinnedAddresses) {
		try {
			return this.httpTransport.get(uri, pinnedAddresses);
		}
		catch (IOException e) {
			throw new WebFetchException("Network error while fetching URL: " + e.getMessage(), e);
		}
	}

	private URI normalizeAndValidateUrl(URI uri) {
		if (uri.getScheme() == null || uri.getHost() == null) {
			throw new WebFetchException("Please provide a fully-formed URL (e.g., https://example.com)", null);
		}

		String scheme = uri.getScheme().toLowerCase();
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new WebFetchException("Only HTTP and HTTPS URLs are supported", null);
		}

		return scheme.equals("http") ? uri.resolve("https://" + uri.getRawAuthority() + rawPathQueryFragment(uri))
				: uri;
	}

	private String rawPathQueryFragment(URI uri) {
		StringBuilder result = new StringBuilder();
		if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
			result.append(uri.getRawPath());
		}
		if (uri.getRawQuery() != null) {
			result.append('?').append(uri.getRawQuery());
		}
		if (uri.getRawFragment() != null) {
			result.append('#').append(uri.getRawFragment());
		}
		return result.toString();
	}

	private boolean isRedirect(int statusCode) {
		return List.of(301, 302, 303, 307, 308).contains(statusCode);
	}

	private InetAddress[] resolveAndValidateUrl(URI uri) {
		String host = uri.getHost();
		if (host == null) {
			throw new WebFetchException("URL host is required", null);
		}
		String normalizedHost = host.toLowerCase();
		if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")) {
			throw new WebFetchException("URL host is not allowed", null);
		}
		try {
			InetAddress[] addresses = this.addressResolver.resolve(normalizedHost);
			if (addresses.length == 0) {
				throw new WebFetchException("Unable to resolve URL host", null);
			}
			for (InetAddress address : addresses) {
				if (isPrivateAddress(address)) {
					throw new WebFetchException("URL host is not allowed", null);
				}
			}
			return addresses;
		}
		catch (UnknownHostException e) {
			throw new WebFetchException("Unable to resolve URL host", e);
		}
	}

	private boolean isPrivateAddress(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = bytes[0] & 0xff;
			int second = bytes[1] & 0xff;
			return first == 0 || first == 10 || first == 127 || (first == 100 && second >= 64 && second <= 127)
					|| (first == 169 && second == 254) || (first == 172 && second >= 16 && second <= 31)
					|| (first == 192 && second == 168) || (first == 198 && (second == 18 || second == 19))
					|| (first == 203 && second == 0 && (bytes[2] & 0xff) == 113) || first >= 224;
		}
		if (address instanceof Inet6Address) {
			int first = bytes[0] & 0xff;
			int second = bytes[1] & 0xff;
			return first == 0 || first == 0xfc || first == 0xfd || (first == 0xfe && (second & 0xc0) == 0x80);
		}
		return true;
	}

	private static Optional<Charset> extractCharset(FetchResponse response) {
		return response.firstHeader("Content-Type").flatMap(contentType -> {
			Matcher matcher = CHARSET_PATTERN.matcher(contentType);
			if (matcher.find()) {
				String charsetName = matcher.group(1);
				try {
					return Optional.of(Charset.forName(charsetName));
				}
				catch (Exception e) {
					logger.warn("Unsupported charset '{}', falling back to UTF-8", charsetName);
					return Optional.empty();
				}
			}
			return Optional.empty();
		});
	}

	private String summarize(String content, String userQuery) {
		try {
			String prompt = PromptTemplate.of(FETCH_SUMMARIZE_PROMPT)
				.render(Map.of("content", content, "userQuery", userQuery == null ? "" : userQuery));
			ChatResponse response = this.chatClient.callBlocking(new ChatRequest(List.of(ChatMessage.user(prompt)),
					List.of(), GenerationOptions.builder().toolChoice(ToolChoice.none()).build(), Map.of()));
			if (response == null || response.messages().size() != 1
					|| response.messages().get(0).role() != ChatRole.ASSISTANT || response.text().isBlank()) {
				return "Error: Received empty or invalid response from AI model";
			}
			if (!response.toolCalls().isEmpty() || !response.messages().get(0).toolCalls().isEmpty()) {
				throw new IllegalStateException("Web summary must not return tool calls");
			}
			return response.text();
		}
		catch (Exception e) {
			if (e instanceof CancellationException cancelled) {
				throw cancelled;
			}
			if (Thread.currentThread().isInterrupted()) {
				throw new CancellationException("Web summary interrupted");
			}
			logger.error("Failed to summarize content", e);
			return "Error summarizing content: " + e.getMessage();
		}
	}

	private String truncate(String content) {
		if (content == null) {
			return "";
		}
		if (content.length() > this.maxContentLength) {
			logger.warn("Content too long ({} characters). Truncating to {} characters.", content.length(),
					this.maxContentLength);
			return content.substring(0, this.maxContentLength);
		}
		return content;
	}

	interface AddressResolver {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	interface HttpTransport {

		FetchResponse get(URI uri, InetAddress[] pinnedAddresses) throws IOException;

	}

	record FetchResponse(int statusCode, URI uri, Map<String, List<String>> headers, String body) {

		static FetchResponse of(int statusCode, URI uri, Map<String, List<String>> headers, String body) {
			return new FetchResponse(statusCode, uri, headers, body);
		}

		Optional<String> firstHeader(String name) {
			for (Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
					return Optional.ofNullable(entry.getValue().get(0));
				}
			}
			return Optional.empty();
		}

	}

	private static class ApachePinnedHttpTransport implements HttpTransport {

		@Override
		public FetchResponse get(URI uri, InetAddress[] pinnedAddresses) throws IOException {
			RequestConfig requestConfig = RequestConfig.custom()
				.setRedirectsEnabled(false)
				.setConnectTimeout(Timeout.ofMilliseconds(DEFAULT_CONNECT_TIMEOUT.toMillis()))
				.setResponseTimeout(Timeout.ofMilliseconds(DEFAULT_REQUEST_TIMEOUT.toMillis()))
				.build();
			try (PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder
				.create()
				.setDnsResolver(new PinnedDnsResolver(uri.getHost(), pinnedAddresses))
				.build();
					CloseableHttpClient client = HttpClients.custom()
						.setConnectionManager(connectionManager)
						.setDefaultRequestConfig(requestConfig)
						.disableRedirectHandling()
						.build()) {
				HttpGet request = new HttpGet(uri);
				request.setHeader("User-Agent", USER_AGENT);
				request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
				request.setHeader("Accept-Language", "en-US,en;q=0.5");
				return client.execute(request, response -> toFetchResponse(uri, response));
			}
		}

		private FetchResponse toFetchResponse(URI uri, org.apache.hc.core5.http.ClassicHttpResponse response)
				throws IOException {
			Map<String, List<String>> headers = new HashMap<>();
			for (Header header : response.getHeaders()) {
				headers.computeIfAbsent(header.getName(), key -> new ArrayList<>()).add(header.getValue());
			}
			HttpEntity entity = response.getEntity();
			byte[] bodyBytes = entity != null ? EntityUtils.toByteArray(entity) : new byte[0];
			FetchResponse metadata = FetchResponse.of(response.getCode(), uri, headers, "");
			Charset charset = extractCharset(metadata).orElse(StandardCharsets.UTF_8);
			return FetchResponse.of(response.getCode(), uri, headers, new String(bodyBytes, charset));
		}

	}

	private static class PinnedDnsResolver implements DnsResolver {

		private final String host;

		private final InetAddress[] pinnedAddresses;

		PinnedDnsResolver(String host, InetAddress[] pinnedAddresses) {
			this.host = host;
			this.pinnedAddresses = pinnedAddresses.clone();
		}

		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			if (this.host.equalsIgnoreCase(host)) {
				return this.pinnedAddresses.clone();
			}
			throw new UnknownHostException(host);
		}

		@Override
		public String resolveCanonicalHostname(String host) throws UnknownHostException {
			if (this.host.equalsIgnoreCase(host)) {
				return this.host;
			}
			throw new UnknownHostException(host);
		}

	}

	/**
	 * Custom exception for web fetch errors.
	 */
	public static class WebFetchException extends RuntimeException {

		public WebFetchException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	public static Builder builder(ChatClient chatClient) {
		return new Builder(chatClient);
	}

	public static class Builder {

		private final ChatClient chatClient;

		private int maxContentLength = 100_000;

		private int maxCacheSize = 100;

		private int maxRetries = 2;

		private AddressResolver addressResolver;

		private HttpTransport httpTransport;

		private String name = "web_fetch";

		private String description = DEFAULT_TOOL_DESCRIPTION;

		private Builder(ChatClient chatClient) {
			if (chatClient == null) {
				throw new IllegalArgumentException("ChatClient must not be null");
			}
			this.chatClient = chatClient;
		}

		public Builder maxContentLength(int maxContentLength) {
			if (maxContentLength <= 0) {
				throw new IllegalArgumentException("maxContentLength must be positive");
			}
			this.maxContentLength = maxContentLength;
			return this;
		}

		public Builder maxCacheSize(int maxCacheSize) {
			if (maxCacheSize <= 0) {
				throw new IllegalArgumentException("maxCacheSize must be positive");
			}
			this.maxCacheSize = maxCacheSize;
			return this;
		}

		public Builder maxRetries(int maxRetries) {
			if (maxRetries < 0) {
				throw new IllegalArgumentException("maxRetries must be non-negative");
			}
			this.maxRetries = maxRetries;
			return this;
		}

		Builder addressResolver(AddressResolver addressResolver) {
			this.addressResolver = addressResolver;
			return this;
		}

		Builder httpTransport(HttpTransport httpTransport) {
			this.httpTransport = httpTransport;
			return this;
		}

		public Builder withName(String name) {
			this.name = name;
			return this;
		}

		public Builder withDescription(String description) {
			this.description = description;
			return this;
		}

		public Tool build() {
			return TypedTool.builder(this.name, buildWebFetchTool())
				.description(this.description)
				.inputType(Request.class)
				.build();
		}

		/**
		 * Builds the WebFetchTool instance directly (for testing).
		 */
		WebFetchTool buildWebFetchTool() {
			return new WebFetchTool(this.chatClient, this.maxContentLength, this.maxCacheSize, this.maxRetries,
					this.addressResolver, this.httpTransport);
		}

	}

}
