/*
 * Copyright 2025-2026 the original author or authors.
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

package io.github.agentic.ai.graph.agent.interceptor.toolcatalog;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.tool.catalog.OfferedTool;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalog;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogOffer;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogQuery;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogSearchResult;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogSearchStatus;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolSummary;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in model interceptor that discloses a bounded, authorized subset of a
 * {@link ToolCatalog} to each model call.
 *
 * <p>
 * For every model call the interceptor derives a query (by default from the last user
 * message), searches the catalog with the run's
 * {@link ModelRequest#getTrustedExecutionContext() trusted execution context}, releases
 * full schemas only for the matched tools and adds them to the request's dynamic tool
 * registry under their qualified names. The released definitions are recorded under
 * {@link #OFFERED_TOOLS_CONTEXT_KEY} so later steps know exactly which versions were
 * offered; the search outcome is recorded under {@link #SEARCH_STATUS_CONTEXT_KEY}.
 * </p>
 * <p>
 * Catalog tools are never contributed through {@link #getTools()}, so the static tool set
 * of the agent does not change. An empty, unauthenticated or failed search discloses no
 * catalog tool and leaves the request's existing tools untouched; it never falls back to
 * the whole catalog. Disclosure is bounded by {@link Builder#maxTools(int)} and by the
 * catalog's own bound. A non-empty explicit tool selection made by an outer interceptor
 * is extended with the disclosed tools; an explicit empty selection disables tools for
 * the call and is respected. Tool execution is still authorized independently by the tool
 * execution boundary, for example
 * {@link io.github.agentic.ai.graph.agent.interceptor.authorization.ToolAuthorizationInterceptor}.
 * </p>
 */
public class ToolCatalogInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ToolCatalogInterceptor.class);

	/** Request context key holding the {@code List<OfferedTool>} disclosed by this call. */
	public static final String OFFERED_TOOLS_CONTEXT_KEY = "argi.toolCatalog.offeredTools";

	/** Request context key holding the {@link ToolCatalogSearchStatus} of this call. */
	public static final String SEARCH_STATUS_CONTEXT_KEY = "argi.toolCatalog.searchStatus";

	private final ToolCatalog catalog;

	private final int maxTools;

	private final Set<String> domains;

	private final Set<String> tags;

	private final Function<ModelRequest, String> queryText;

	private ToolCatalogInterceptor(Builder builder) {
		this.catalog = builder.catalog;
		this.maxTools = builder.maxTools != null ? builder.maxTools : builder.catalog.maxResults();
		this.domains = Set.copyOf(builder.domains);
		this.tags = Set.copyOf(builder.tags);
		this.queryText = builder.queryText;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		if (request.hasToolSelection() && request.getTools().isEmpty()) {
			// An outer interceptor explicitly disabled tools for this call; never override.
			return handler.call(record(request, ToolCatalogSearchStatus.EMPTY, List.of()).build());
		}
		TrustedExecutionContext context = request.getTrustedExecutionContext().orElse(null);
		ToolCatalogSearchResult result;
		ToolCatalogOffer offer;
		try {
			String text = this.queryText.apply(request);
			ToolCatalogQuery query = new ToolCatalogQuery(text, this.domains, this.tags, this.maxTools);
			result = this.catalog.search(query, context);
			offer = result.matched()
					? this.catalog.offer(result.tools().stream().map(ToolSummary::id).toList(), context) : null;
		}
		catch (RuntimeException ex) {
			log.warn("Tool catalog disclosure failed, continuing without catalog tools: {}", ex.toString());
			return handler.call(record(request, ToolCatalogSearchStatus.FAILED, List.of()).build());
		}
		if (offer == null || offer.isEmpty()) {
			ToolCatalogSearchStatus status = result.matched() ? ToolCatalogSearchStatus.EMPTY : result.status();
			return handler.call(record(request, status, List.of()).build());
		}
		return handler.call(disclose(request, offer).build());
	}

	private ModelRequest.Builder disclose(ModelRequest request, ToolCatalogOffer offer) {
		Set<String> taken = new HashSet<>();
		request.getToolRegistry().definitions().forEach(definition -> taken.add(definition.name()));
		List<Tool> dynamic = new ArrayList<>();
		for (ToolDefinition definition : request.getDynamicToolRegistry().definitions()) {
			taken.add(definition.name());
			dynamic.add(request.getDynamicToolRegistry().find(definition.name()).orElseThrow());
		}
		List<OfferedTool> disclosed = new ArrayList<>();
		Map<String, String> descriptions = new HashMap<>(request.getToolDescriptions());
		List<String> names = new ArrayList<>();
		for (int i = 0; i < offer.tools().size(); i++) {
			Tool tool = offer.tools().get(i);
			String name = tool.definition().name();
			if (!taken.add(name)) {
				// An existing binding with the same name always wins; never shadow it.
				continue;
			}
			dynamic.add(tool);
			disclosed.add(offer.offered().get(i));
			descriptions.put(name, tool.definition().description());
			names.add(name);
		}
		ModelRequest.Builder builder = record(request,
				disclosed.isEmpty() ? ToolCatalogSearchStatus.EMPTY : ToolCatalogSearchStatus.MATCHED, disclosed);
		if (disclosed.isEmpty()) {
			return builder;
		}
		builder.dynamicToolRegistry(new ToolRegistry(dynamic)).toolDescriptions(descriptions);
		if (request.hasToolSelection()) {
			// An upstream explicit selection would otherwise filter out the catalog tools.
			List<String> selection = new ArrayList<>(request.getTools());
			selection.addAll(names);
			builder.tools(selection);
		}
		log.debug("Tool catalog disclosed {} tool(s): {}", names.size(), names);
		return builder;
	}

	private static ModelRequest.Builder record(ModelRequest request, ToolCatalogSearchStatus status,
			List<OfferedTool> offered) {
		Map<String, Object> context = new HashMap<>(request.getContext());
		context.put(SEARCH_STATUS_CONTEXT_KEY, status);
		context.put(OFFERED_TOOLS_CONTEXT_KEY, List.copyOf(offered));
		return ModelRequest.builder(request).context(context);
	}

	static String lastUserMessage(ModelRequest request) {
		List<ChatMessage> messages = request.getMessages();
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage message = messages.get(i);
			if (message.role() == ChatRole.USER) {
				return message.text();
			}
		}
		return "";
	}

	@Override
	public String getName() {
		return "ToolCatalog";
	}

	public static class Builder {

		private ToolCatalog catalog;

		private Integer maxTools;

		private Set<String> domains = Set.of();

		private Set<String> tags = Set.of();

		private Function<ModelRequest, String> queryText = ToolCatalogInterceptor::lastUserMessage;

		/** Required catalog to search. */
		public Builder catalog(ToolCatalog catalog) {
			this.catalog = catalog;
			return this;
		}

		/**
		 * Maximum catalog tools disclosed per model call; the catalog's own bound still
		 * applies. Defaults to the catalog bound.
		 */
		public Builder maxTools(int maxTools) {
			if (maxTools <= 0) {
				throw new IllegalArgumentException("maxTools must be > 0");
			}
			this.maxTools = maxTools;
			return this;
		}

		/** Restricts retrieval to these domains. */
		public Builder domains(Set<String> domains) {
			this.domains = Objects.requireNonNull(domains, "domains must not be null");
			return this;
		}

		/** Restricts retrieval to tools carrying at least one of these tags. */
		public Builder tags(Set<String> tags) {
			this.tags = Objects.requireNonNull(tags, "tags must not be null");
			return this;
		}

		/** Derives the free-text query; defaults to the last user message. */
		public Builder queryText(Function<ModelRequest, String> queryText) {
			this.queryText = Objects.requireNonNull(queryText, "queryText must not be null");
			return this;
		}

		public ToolCatalogInterceptor build() {
			if (this.catalog == null) {
				throw new IllegalStateException("Tool catalog is required");
			}
			return new ToolCatalogInterceptor(this);
		}

	}

}
