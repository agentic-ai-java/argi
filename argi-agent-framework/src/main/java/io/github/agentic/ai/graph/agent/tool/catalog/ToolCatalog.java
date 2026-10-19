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

package io.github.agentic.ai.graph.agent.tool.catalog;

import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.graph.agent.interceptor.authorization.AuthorizationDecision;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pluggable tool catalog: authorized summary retrieval plus on-demand schema release.
 *
 * <p>
 * The catalog composes one or more {@link ToolCatalogProvider providers}, a
 * {@link ToolCatalogAccessPolicy trusted access policy} and a
 * {@link ToolCatalogSearchStrategy retrieval strategy}. Its contract is:
 * </p>
 * <ul>
 * <li><b>Retrieval is filtered before ranking.</b> A summary that fails the
 * {@link ToolCatalogAccess#DISCOVER} check is never shown to the strategy and never
 * returned.</li>
 * <li><b>Schema release is checked again.</b> {@link #offer} re-reads the current summary
 * and re-checks {@link ToolCatalogAccess#LOAD_SCHEMA} for every id, so a guessed or stale
 * id is rejected even if it was never searched for.</li>
 * <li><b>Retrieval is not execution.</b> Released bindings are still authorized at the
 * tool execution boundary.</li>
 * <li><b>Failures never widen disclosure.</b> Empty results, provider failures and
 * strategy failures return no tools; results and offers are bounded by
 * {@link Builder#maxResults(int)}.</li>
 * <li><b>Only summaries are cached</b>, per provider revision. Authorization decisions
 * and schema resolutions are never cached.</li>
 * </ul>
 */
public final class ToolCatalog {

	private static final Logger log = LoggerFactory.getLogger(ToolCatalog.class);

	/** Default upper bound on search results and on released schemas per offer. */
	public static final int DEFAULT_MAX_RESULTS = 8;

	private final Map<String, ToolCatalogProvider> providers;

	private final List<String> providerOrder;

	private final ToolCatalogAccessPolicy accessPolicy;

	private final ToolCatalogSearchStrategy searchStrategy;

	private final int maxResults;

	private final boolean requireTrustedContext;

	private final boolean cacheSummaries;

	private final Map<String, CachedSummaries> summaryCache = new ConcurrentHashMap<>();

	private ToolCatalog(Builder builder) {
		this.providers = Map.copyOf(builder.providers);
		this.accessPolicy = builder.accessPolicy;
		this.searchStrategy = builder.searchStrategy;
		this.maxResults = builder.maxResults;
		this.requireTrustedContext = builder.requireTrustedContext;
		this.cacheSummaries = builder.cacheSummaries;
		this.providerOrder = List.copyOf(builder.providers.keySet());
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Upper bound applied to search results and to released schemas per offer. */
	public int maxResults() {
		return this.maxResults;
	}

	/**
	 * Searches authorized summaries.
	 * @param query the retrieval request
	 * @param context the trusted execution context of the caller
	 * @return a bounded result; never contains a tool the caller may not discover
	 */
	public ToolCatalogSearchResult search(ToolCatalogQuery query, TrustedExecutionContext context) {
		Objects.requireNonNull(query, "query must not be null");
		if (this.requireTrustedContext && context == null) {
			log.warn("Tool catalog search rejected: no trusted execution context");
			return ToolCatalogSearchResult.of(ToolCatalogSearchStatus.UNAUTHENTICATED, List.of());
		}
		List<String> failedProviders = new ArrayList<>();
		List<ToolSummary> candidates = new ArrayList<>();
		for (String providerId : this.providerOrder) {
			Optional<List<ToolSummary>> summaries = summaries(this.providers.get(providerId));
			if (summaries.isEmpty()) {
				failedProviders.add(providerId);
				continue;
			}
			for (ToolSummary summary : summaries.get()) {
				if (allowed(ToolCatalogAccess.DISCOVER, summary, context)) {
					candidates.add(summary);
				}
			}
		}
		if (!failedProviders.isEmpty() && failedProviders.size() == this.providerOrder.size()) {
			return ToolCatalogSearchResult.of(ToolCatalogSearchStatus.FAILED, failedProviders);
		}
		if (candidates.isEmpty()) {
			return ToolCatalogSearchResult.of(ToolCatalogSearchStatus.EMPTY, failedProviders);
		}
		List<ToolSummary> ranked;
		try {
			ranked = Objects.requireNonNull(this.searchStrategy.search(query, List.copyOf(candidates)),
					"search strategy returned null");
		}
		catch (RuntimeException ex) {
			log.warn("Tool catalog search strategy failed, returning no tools: {}", ex.toString());
			return ToolCatalogSearchResult.of(ToolCatalogSearchStatus.FAILED, failedProviders);
		}
		// A strategy may only reorder and drop authorized candidates.
		Set<ToolSummary> authorized = new HashSet<>(candidates);
		int limit = Math.min(query.topK(), this.maxResults);
		Set<ToolSummary> bounded = new LinkedHashSet<>();
		for (ToolSummary summary : ranked) {
			if (bounded.size() >= limit) {
				break;
			}
			if (summary != null && authorized.contains(summary)) {
				bounded.add(summary);
			}
		}
		if (bounded.isEmpty()) {
			return ToolCatalogSearchResult.of(ToolCatalogSearchStatus.EMPTY, failedProviders);
		}
		return new ToolCatalogSearchResult(ToolCatalogSearchStatus.MATCHED, List.copyOf(bounded), failedProviders);
	}

	/**
	 * Releases full schemas and executable bindings for the requested ids.
	 *
	 * <p>
	 * Every id is checked against the provider's current summary and the
	 * {@link ToolCatalogAccess#LOAD_SCHEMA} policy; the current version is then resolved
	 * from the provider and recorded in the returned snapshot. Ids beyond
	 * {@link #maxResults()}, duplicates, unknown, unauthorized or unresolvable ids are
	 * rejected without distinguishing the reason.
	 * </p>
	 * @param ids requested tool ids, in disclosure order
	 * @param context the trusted execution context of the caller
	 * @return the offer, never {@code null}
	 */
	public ToolCatalogOffer offer(Collection<ToolId> ids, TrustedExecutionContext context) {
		Objects.requireNonNull(ids, "ids must not be null");
		List<ToolId> rejected = new ArrayList<>();
		if (this.requireTrustedContext && context == null) {
			log.warn("Tool catalog schema request rejected: no trusted execution context");
			rejected.addAll(ids);
			return ToolCatalogOffer.empty(rejected);
		}
		List<OfferedTool> offered = new ArrayList<>();
		List<Tool> tools = new ArrayList<>();
		Set<ToolId> seen = new HashSet<>();
		Map<String, Optional<List<ToolSummary>>> current = new LinkedHashMap<>();
		for (ToolId id : ids) {
			if (id == null) {
				continue;
			}
			if (!seen.add(id) || offered.size() >= this.maxResults) {
				rejected.add(id);
				continue;
			}
			Optional<ToolSummary> summary = currentSummary(id, current);
			if (summary.isEmpty() || !allowed(ToolCatalogAccess.LOAD_SCHEMA, summary.get(), context)) {
				rejected.add(id);
				continue;
			}
			Optional<CatalogBoundTool> bound = bind(summary.get().id(), summary.get().version());
			if (bound.isEmpty()) {
				rejected.add(id);
				continue;
			}
			offered.add(OfferedTool.of(id, summary.get().version(), bound.get().definition()));
			tools.add(bound.get());
		}
		if (!rejected.isEmpty()) {
			log.debug("Tool catalog rejected {} schema request(s)", rejected.size());
		}
		return new ToolCatalogOffer(offered, tools, rejected);
	}

	/**
	 * Releases the full schema of a single tool.
	 * @param id the tool id
	 * @param context the trusted execution context of the caller
	 * @return the offered definition snapshot
	 * @throws ToolCatalogAccessException when the id is unknown, unauthorized or
	 * unresolvable
	 */
	public OfferedTool loadSchema(ToolId id, TrustedExecutionContext context) {
		Objects.requireNonNull(id, "id must not be null");
		ToolCatalogOffer offer = offer(List.of(id), context);
		if (offer.isEmpty()) {
			throw new ToolCatalogAccessException(id);
		}
		return offer.offered().get(0);
	}

	/**
	 * Resolves exactly the version recorded in an earlier offer, for example after a
	 * restart. Access is checked again against that pinned version, and the resolved
	 * definition must still match the recorded digest; the catalog never substitutes a
	 * newer version.
	 * @param offered the recorded offer
	 * @param context the trusted execution context of the caller
	 * @return the binding for the pinned version
	 * @throws ToolCatalogAccessException when the pinned version is unavailable, changed
	 * or no longer authorized
	 */
	public Tool resolveOffered(OfferedTool offered, TrustedExecutionContext context) {
		Objects.requireNonNull(offered, "offered must not be null");
		if (this.requireTrustedContext && context == null) {
			throw new ToolCatalogAccessException(offered.id());
		}
		ToolCatalogProvider provider = this.providers.get(offered.id().provider());
		if (provider == null) {
			throw new ToolCatalogAccessException(offered.id());
		}
		Optional<ToolCatalogEntry> entry = resolveEntry(provider, offered.id(), offered.version());
		if (entry.isEmpty() || !allowed(ToolCatalogAccess.LOAD_SCHEMA, entry.get().summary(), context)) {
			throw new ToolCatalogAccessException(offered.id());
		}
		CatalogBoundTool bound = new CatalogBoundTool(offered.id(), entry.get().tool());
		if (!offered.schemaDigest().equals(OfferedTool.digest(bound.definition()))) {
			log.warn("Tool catalog version {}@{} changed in place; refusing to resolve it", offered.id(),
					offered.version());
			throw new ToolCatalogAccessException(offered.id());
		}
		return bound;
	}

	/** Drops cached summaries, for example after an out-of-band catalog change. */
	public void invalidate() {
		this.summaryCache.clear();
	}

	private Optional<ToolSummary> currentSummary(ToolId id, Map<String, Optional<List<ToolSummary>>> current) {
		ToolCatalogProvider provider = this.providers.get(id.provider());
		if (provider == null) {
			return Optional.empty();
		}
		return current.computeIfAbsent(provider.providerId(), key -> summaries(provider))
			.flatMap(summaries -> summaries.stream().filter(summary -> summary.id().equals(id)).findFirst());
	}

	private Optional<CatalogBoundTool> bind(ToolId id, String version) {
		return resolveEntry(this.providers.get(id.provider()), id, version)
			.map(entry -> new CatalogBoundTool(id, entry.tool()));
	}

	private Optional<ToolCatalogEntry> resolveEntry(ToolCatalogProvider provider, ToolId id, String version) {
		try {
			Optional<ToolCatalogEntry> entry = provider.resolve(id, version);
			return entry == null ? Optional.empty()
					: entry.filter(value -> value.summary().id().equals(id) && value.summary().version().equals(version));
		}
		catch (RuntimeException ex) {
			log.warn("Tool catalog provider {} failed to resolve {}@{}: {}", provider.providerId(), id, version,
					ex.toString());
			return Optional.empty();
		}
	}

	/**
	 * Reads a provider's summaries, using the revision-keyed cache when allowed. Returns
	 * empty when the provider fails or violates the provider contract.
	 */
	private Optional<List<ToolSummary>> summaries(ToolCatalogProvider provider) {
		try {
			Optional<String> revision = this.cacheSummaries ? provider.revision() : Optional.empty();
			if (revision != null && revision.isPresent()) {
				CachedSummaries cached = this.summaryCache.get(provider.providerId());
				if (cached != null && cached.revision().equals(revision.get())) {
					return Optional.of(cached.summaries());
				}
			}
			List<ToolSummary> summaries = validate(provider, provider.summaries());
			if (revision != null && revision.isPresent()) {
				this.summaryCache.put(provider.providerId(), new CachedSummaries(revision.get(), summaries));
			}
			return Optional.of(summaries);
		}
		catch (RuntimeException ex) {
			log.warn("Tool catalog provider {} failed to list summaries: {}", provider.providerId(), ex.toString());
			this.summaryCache.remove(provider.providerId());
			return Optional.empty();
		}
	}

	private static List<ToolSummary> validate(ToolCatalogProvider provider, List<ToolSummary> summaries) {
		Objects.requireNonNull(summaries, "provider returned null summaries");
		Set<ToolId> ids = new HashSet<>();
		List<ToolSummary> valid = new ArrayList<>();
		for (ToolSummary summary : summaries) {
			if (summary == null) {
				continue;
			}
			if (!provider.providerId().equals(summary.id().provider())) {
				log.warn("Tool catalog provider {} returned foreign tool {}; discarding it", provider.providerId(),
						summary.id());
				continue;
			}
			if (!ids.add(summary.id())) {
				throw new IllegalStateException("Duplicate tool id " + summary.id());
			}
			valid.add(summary);
		}
		return List.copyOf(valid);
	}

	private boolean allowed(ToolCatalogAccess access, ToolSummary summary, TrustedExecutionContext context) {
		try {
			AuthorizationDecision decision = this.accessPolicy.authorize(access, summary, context);
			return decision != null && decision.allowed();
		}
		catch (RuntimeException ex) {
			log.warn("Tool catalog access policy failed for {} ({}); denying: {}", summary.id(), access,
					ex.toString());
			return false;
		}
	}

	private record CachedSummaries(String revision, List<ToolSummary> summaries) {
	}

	public static final class Builder {

		private final Map<String, ToolCatalogProvider> providers = new LinkedHashMap<>();

		private ToolCatalogAccessPolicy accessPolicy;

		private ToolCatalogSearchStrategy searchStrategy = new KeywordToolCatalogSearchStrategy();

		private int maxResults = DEFAULT_MAX_RESULTS;

		private boolean requireTrustedContext = true;

		private boolean cacheSummaries = true;

		private Builder() {
		}

		/** Adds a provider; provider ids must be unique within a catalog. */
		public Builder provider(ToolCatalogProvider provider) {
			Objects.requireNonNull(provider, "provider must not be null");
			String providerId = ToolId.requireProvider(provider.providerId());
			if (this.providers.putIfAbsent(providerId, provider) != null) {
				throw new IllegalArgumentException("Duplicate tool catalog provider: " + providerId);
			}
			return this;
		}

		public Builder providers(Collection<? extends ToolCatalogProvider> providers) {
			providers.forEach(this::provider);
			return this;
		}

		/** Required trusted access policy for discovery and schema release. */
		public Builder accessPolicy(ToolCatalogAccessPolicy accessPolicy) {
			this.accessPolicy = accessPolicy;
			return this;
		}

		/** Retrieval strategy; defaults to {@link KeywordToolCatalogSearchStrategy}. */
		public Builder searchStrategy(ToolCatalogSearchStrategy searchStrategy) {
			this.searchStrategy = Objects.requireNonNull(searchStrategy, "searchStrategy must not be null");
			return this;
		}

		/** Upper bound for search results and released schemas per offer. */
		public Builder maxResults(int maxResults) {
			if (maxResults <= 0) {
				throw new IllegalArgumentException("maxResults must be > 0");
			}
			this.maxResults = maxResults;
			return this;
		}

		/**
		 * Whether a trusted execution context is mandatory. Defaults to {@code true}; when
		 * disabled the access policy receives a {@code null} context and decides itself.
		 */
		public Builder requireTrustedContext(boolean requireTrustedContext) {
			this.requireTrustedContext = requireTrustedContext;
			return this;
		}

		/** Whether summaries may be cached per provider revision. Defaults to {@code true}. */
		public Builder cacheSummaries(boolean cacheSummaries) {
			this.cacheSummaries = cacheSummaries;
			return this;
		}

		public ToolCatalog build() {
			if (this.accessPolicy == null) {
				throw new IllegalStateException("Tool catalog accessPolicy is required");
			}
			if (this.providers.isEmpty()) {
				throw new IllegalStateException("Tool catalog requires at least one provider");
			}
			return new ToolCatalog(this);
		}

	}

}
