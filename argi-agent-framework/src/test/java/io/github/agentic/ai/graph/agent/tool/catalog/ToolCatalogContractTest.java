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

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.agent.interceptor.authorization.AuthorizationDecision;
import io.github.agentic.ai.graph.agent.interceptor.authorization.ToolAuthorizationPolicy;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogTestSupport.FlakyProvider;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogTestSupport.RecordingTool;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.definition.DefinitionKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogTestSupport.scopePolicy;
import static io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogTestSupport.tool;
import static io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogTestSupport.toolContext;
import static io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogTestSupport.trusted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Model-independent contract tests for {@link ToolCatalog}: authorized retrieval,
 * on-demand schema release, qualified identity, offered snapshots and bounded failure
 * behavior.
 */
class ToolCatalogContractTest {

	private static final ToolId REFUND = ToolId.of("payments", "refund");

	private static final ToolId CHARGE = ToolId.of("payments", "charge");

	private static final ToolId ADMIN = ToolId.of("payments", "admin_reset");

	private static InMemoryToolCatalogProvider payments() {
		return InMemoryToolCatalogProvider.builder("payments")
			.tool(tool("refund", "Refund a captured payment"), "1", "Refund a captured payment", "payments", "refund")
			.tool(tool("charge", "Charge a card"), "1", "Charge a card for an order", "payments", "charge")
			.tool(tool("admin_reset", "Reset merchant settlement"), "1", "Reset settlement payment ledger",
					"payments", "admin")
			.build();
	}

	// --- Authorized retrieval -------------------------------------------------------

	@Test
	void unauthorizedCandidatesAreNeverRecalledEvenAsSummaries() {
		AtomicReference<List<ToolSummary>> seenByStrategy = new AtomicReference<>();
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy(scopePolicy())
			.searchStrategy((query, candidates) -> {
				seenByStrategy.set(candidates);
				return candidates;
			})
			.build();

		ToolCatalogSearchResult result = catalog.search(ToolCatalogQuery.text("payment", 10),
				trusted("tool:payments__refund", "tool:payments__charge"));

		assertThat(result.status()).isEqualTo(ToolCatalogSearchStatus.MATCHED);
		assertThat(result.tools()).extracting(ToolSummary::id).containsExactly(REFUND, CHARGE);
		// The strategy itself never saw the unauthorized summary.
		assertThat(seenByStrategy.get()).extracting(ToolSummary::id).doesNotContain(ADMIN);
	}

	@Test
	void strategyCannotInjectUnauthorizedOrForeignSummaries() {
		ToolSummary forged = ToolSummary.of(ADMIN, "1", "Reset settlement");
		ToolSummary foreign = ToolSummary.of(ToolId.of("other", "refund"), "1", "Refund");
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy(scopePolicy())
			.searchStrategy((query, candidates) -> {
				List<ToolSummary> ranked = new ArrayList<>(List.of(forged, foreign));
				ranked.addAll(candidates);
				return ranked;
			})
			.build();

		ToolCatalogSearchResult result = catalog.search(ToolCatalogQuery.text("refund", 10),
				trusted("tool:payments__refund"));

		assertThat(result.tools()).extracting(ToolSummary::id).containsExactly(REFUND);
	}

	@Test
	void missingTrustedContextRecallsNothingAndReleasesNothing() {
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		ToolCatalogSearchResult result = catalog.search(ToolCatalogQuery.text("refund", 5), null);
		assertThat(result.status()).isEqualTo(ToolCatalogSearchStatus.UNAUTHENTICATED);
		assertThat(result.tools()).isEmpty();

		ToolCatalogOffer offer = catalog.offer(List.of(REFUND), null);
		assertThat(offer.isEmpty()).isTrue();
		assertThat(offer.rejected()).containsExactly(REFUND);
	}

	@Test
	void nonStrictCatalogLetsThePolicyDecideWithoutContext() {
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy((access, summary, context) -> context == null && summary.id().equals(REFUND)
					? AuthorizationDecision.allow() : AuthorizationDecision.deny("no"))
			.requireTrustedContext(false)
			.build();

		assertThat(catalog.search(ToolCatalogQuery.text("payment", 5), null).tools()).extracting(ToolSummary::id)
			.containsExactly(REFUND);
	}

	@Test
	void revokedAccessTakesEffectImmediatelyBecauseDecisionsAreNeverCached() {
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicInteger policyCalls = new AtomicInteger();
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy((access, summary, context) -> {
				policyCalls.incrementAndGet();
				return allowed.get() ? AuthorizationDecision.allow() : AuthorizationDecision.deny("revoked");
			})
			.build();
		ToolCatalogQuery query = ToolCatalogQuery.text("refund", 5);

		assertThat(catalog.search(query, trusted()).matched()).isTrue();
		int afterFirstSearch = policyCalls.get();
		allowed.set(false);

		assertThat(catalog.search(query, trusted()).status()).isEqualTo(ToolCatalogSearchStatus.EMPTY);
		assertThat(policyCalls.get()).isGreaterThan(afterFirstSearch);
		assertThat(catalog.offer(List.of(REFUND), trusted()).rejected()).containsExactly(REFUND);
	}

	@Test
	void throwingPolicyIsTreatedAsDenial() {
		ToolCatalog catalog = ToolCatalog.builder().provider(payments()).accessPolicy((access, summary, context) -> {
			throw new IllegalStateException("policy store down");
		}).build();

		assertThat(catalog.search(ToolCatalogQuery.text("refund", 5), trusted()).tools()).isEmpty();
		assertThatThrownBy(() -> catalog.loadSchema(REFUND, trusted())).isInstanceOf(ToolCatalogAccessException.class);
	}

	// --- On-demand schema release ---------------------------------------------------

	@Test
	void guessedToolIdsAreRejectedWithoutRevealingWhy() {
		ToolCatalog catalog = ToolCatalog.builder().provider(payments()).accessPolicy(scopePolicy()).build();
		TrustedExecutionContext context = trusted("tool:payments__refund");

		ToolCatalogAccessException denied = org.junit.jupiter.api.Assertions
			.assertThrows(ToolCatalogAccessException.class, () -> catalog.loadSchema(ADMIN, context));
		ToolCatalogAccessException unknown = org.junit.jupiter.api.Assertions.assertThrows(
				ToolCatalogAccessException.class, () -> catalog.loadSchema(ToolId.of("payments", "nope"), context));
		ToolCatalogAccessException unknownProvider = org.junit.jupiter.api.Assertions.assertThrows(
				ToolCatalogAccessException.class, () -> catalog.loadSchema(ToolId.of("ghost", "refund"), context));

		// Unauthorized, unknown and unknown-provider ids look identical to the caller.
		assertThat(denied.getMessage().replace(ADMIN.toString(), "<id>"))
			.isEqualTo(unknown.getMessage().replace("payments__nope", "<id>"))
			.isEqualTo(unknownProvider.getMessage().replace("ghost__refund", "<id>"));
	}

	@Test
	void schemaReleaseRechecksAccessIndependentlyOfDiscovery() {
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy((access, summary, context) -> access == ToolCatalogAccess.DISCOVER
					? AuthorizationDecision.allow() : AuthorizationDecision.deny("schema withheld"))
			.build();

		ToolCatalogSearchResult result = catalog.search(ToolCatalogQuery.text("refund", 5), trusted());
		assertThat(result.tools()).extracting(ToolSummary::id).contains(REFUND);

		ToolCatalogOffer offer = catalog.offer(result.tools().stream().map(ToolSummary::id).toList(), trusted());
		assertThat(offer.isEmpty()).isTrue();
		assertThat(offer.rejected()).contains(REFUND);
	}

	@Test
	void offerReleasesFullSchemaUnderQualifiedNameAndRecordsSnapshot() {
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments")
				.tool(tool("refund", "Refund a captured payment", "{\"type\":\"object\",\"required\":[\"id\"]}"), "7")
				.build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		OfferedTool offered = catalog.loadSchema(REFUND, trusted());

		assertThat(offered.id()).isEqualTo(REFUND);
		assertThat(offered.version()).isEqualTo("7");
		assertThat(offered.definition().name()).isEqualTo("payments__refund");
		assertThat(offered.definition().inputSchema()).isEqualTo("{\"type\":\"object\",\"required\":[\"id\"]}");
		assertThat(offered.schemaDigest()).hasSize(64);
		assertThat(offered.definitionReference().kind()).isEqualTo(DefinitionKind.TOOL_CATALOG);
		assertThat(offered.definitionReference().id()).isEqualTo("payments__refund");
		assertThat(offered.definitionReference().version()).isEqualTo("7");
	}

	// --- Qualified identity -----------------------------------------------------------

	@Test
	void sameNameAcrossProvidersUsesStableQualifiedIdentity() throws Exception {
		RecordingTool primaryRefund = tool("refund", "Refund via primary acquirer");
		RecordingTool backupRefund = tool("refund", "Refund via backup acquirer");
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("acquirer-a").tool(primaryRefund, "1").build())
			.provider(InMemoryToolCatalogProvider.builder("acquirer-b").tool(backupRefund, "3").build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		ToolCatalogSearchResult result = catalog.search(ToolCatalogQuery.text("refund", 5), trusted());
		assertThat(result.tools()).extracting(summary -> summary.id().qualifiedName())
			.containsExactly("acquirer-a__refund", "acquirer-b__refund");

		ToolCatalogOffer offer = catalog.offer(result.tools().stream().map(ToolSummary::id).toList(), trusted());
		assertThat(offer.offered()).extracting(OfferedTool::version).containsExactly("1", "3");
		// Both bindings coexist in one registry because their model-facing names differ.
		ToolRegistry registry = offer.registry();
		assertThat(registry.definitions()).hasSize(2);

		registry.execute(new ToolCall("c1", "acquirer-b__refund", "{}"), toolContext()).toCompletableFuture().get();
		// The provider binding receives its provider-local name.
		assertThat(backupRefund.lastCall.get().name()).isEqualTo("refund");
		assertThat(primaryRefund.lastCall.get()).isNull();
	}

	@Test
	void offeredVersionStaysResolvableAfterANewerVersionIsPublished() {
		RecordingTool v1 = tool("refund", "Refund v1", "{\"v\":1}");
		RecordingTool v2 = tool("refund", "Refund v2", "{\"v\":2}");
		ToolCatalog before = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments").tool(v1, "1").build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();
		OfferedTool offered = before.loadSchema(REFUND, trusted());

		ToolCatalog after = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments").tool(v1, "1").tool(v2, "2").build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		// New searches see the newer version...
		assertThat(after.loadSchema(REFUND, trusted()).version()).isEqualTo("2");
		// ...but the recorded offer still resolves exactly what the model was shown.
		Tool resolved = after.resolveOffered(offered, trusted());
		assertThat(resolved.definition()).isEqualTo(offered.definition());
		assertThat(resolved.definition().inputSchema()).isEqualTo("{\"v\":1}");
	}

	@Test
	void resolveOfferedNeverSubstitutesAnotherVersionOrAChangedDefinition() {
		OfferedTool offered = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments").tool(tool("refund", "Refund", "{\"v\":1}"), "1")
				.build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build()
			.loadSchema(REFUND, trusted());

		ToolCatalog retired = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments").tool(tool("refund", "Refund", "{\"v\":2}"), "2")
				.build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();
		assertThatThrownBy(() -> retired.resolveOffered(offered, trusted()))
			.isInstanceOf(ToolCatalogAccessException.class);

		ToolCatalog mutatedInPlace = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments")
				.tool(tool("refund", "Refund", "{\"v\":\"changed\"}"), "1")
				.build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();
		assertThatThrownBy(() -> mutatedInPlace.resolveOffered(offered, trusted()))
			.isInstanceOf(ToolCatalogAccessException.class);

		ToolCatalog revoked = ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments").tool(tool("refund", "Refund", "{\"v\":1}"), "1")
				.build())
			.accessPolicy(ToolCatalogAccessPolicy.denyAll())
			.build();
		assertThatThrownBy(() -> revoked.resolveOffered(offered, trusted()))
			.isInstanceOf(ToolCatalogAccessException.class);
	}

	@Test
	void providerCannotClaimAnotherProvidersIdentity() {
		ToolCatalogProvider impostor = new ToolCatalogProvider() {
			@Override
			public String providerId() {
				return "impostor";
			}

			@Override
			public List<ToolSummary> summaries() {
				return List.of(ToolSummary.of(REFUND, "1", "Refund a payment"),
						ToolSummary.of(ToolId.of("impostor", "refund"), "1", "Refund a payment"));
			}

			@Override
			public java.util.Optional<ToolCatalogEntry> resolve(ToolId id, String version) {
				return java.util.Optional.empty();
			}
		};
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(impostor)
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		assertThat(catalog.search(ToolCatalogQuery.text("refund", 5), trusted()).tools())
			.extracting(summary -> summary.id().qualifiedName())
			.containsExactly("impostor__refund");
		// Its own tool is not resolvable either, so nothing is released.
		assertThat(catalog.offer(List.of(ToolId.of("impostor", "refund")), trusted()).isEmpty()).isTrue();
	}

	@Test
	void duplicateProvidersAndVersionsAreRejected() {
		assertThatThrownBy(() -> ToolCatalog.builder().provider(payments()).provider(payments()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Duplicate tool catalog provider");
		assertThatThrownBy(() -> InMemoryToolCatalogProvider.builder("payments")
			.tool(tool("refund", "Refund"), "1")
			.tool(tool("refund", "Refund"), "1")).isInstanceOf(IllegalArgumentException.class);
	}

	// --- Empty results, failures and bounds -------------------------------------------

	@Test
	void emptyAndUnconstrainedQueriesReturnNothing() {
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		assertThat(catalog.search(ToolCatalogQuery.text("weather forecast", 5), trusted()).status())
			.isEqualTo(ToolCatalogSearchStatus.EMPTY);
		ToolCatalogSearchResult unconstrained = catalog.search(ToolCatalogQuery.text("  ", 5), trusted());
		assertThat(unconstrained.status()).isEqualTo(ToolCatalogSearchStatus.EMPTY);
		assertThat(unconstrained.tools()).isEmpty();
	}

	@Test
	void failingStrategyDoesNotSilentlyExposeAllTools() {
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.searchStrategy((query, candidates) -> {
				throw new IllegalStateException("index offline");
			})
			.build();

		ToolCatalogSearchResult result = catalog.search(ToolCatalogQuery.text("refund", 5), trusted());
		assertThat(result.status()).isEqualTo(ToolCatalogSearchStatus.FAILED);
		assertThat(result.tools()).isEmpty();
	}

	@Test
	void providerFailuresAreIsolatedAndNeverWidenTheResult() {
		FlakyProvider flaky = new FlakyProvider(payments());
		flaky.failSummaries = true;
		ToolCatalog onlyFlaky = ToolCatalog.builder()
			.provider(flaky)
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();
		ToolCatalogSearchResult failed = onlyFlaky.search(ToolCatalogQuery.text("refund", 5), trusted());
		assertThat(failed.status()).isEqualTo(ToolCatalogSearchStatus.FAILED);
		assertThat(failed.failedProviders()).containsExactly("payments");

		ToolCatalog mixed = ToolCatalog.builder()
			.provider(flaky)
			.provider(InMemoryToolCatalogProvider.builder("ledger").tool(tool("refund", "Ledger refund"), "1").build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();
		ToolCatalogSearchResult partial = mixed.search(ToolCatalogQuery.text("refund", 5), trusted());
		assertThat(partial.status()).isEqualTo(ToolCatalogSearchStatus.MATCHED);
		assertThat(partial.tools()).extracting(summary -> summary.id().qualifiedName())
			.containsExactly("ledger__refund");
		assertThat(partial.failedProviders()).containsExactly("payments");

		flaky.failSummaries = false;
		flaky.failResolve = true;
		assertThat(mixed.offer(List.of(REFUND), trusted()).rejected()).containsExactly(REFUND);
	}

	@Test
	void resultsAndOffersAreBoundedByTheCatalogLimit() {
		InMemoryToolCatalogProvider.Builder provider = InMemoryToolCatalogProvider.builder("bulk");
		List<ToolId> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			provider.tool(tool("report_" + i, "Export report " + i), "1");
			ids.add(ToolId.of("bulk", "report_" + i));
		}
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(provider.build())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.maxResults(3)
			.build();

		assertThat(catalog.search(ToolCatalogQuery.text("report", 50), trusted()).tools()).hasSize(3);
		assertThat(catalog.search(ToolCatalogQuery.text("report", 2), trusted()).tools()).hasSize(2);

		ToolCatalogOffer offer = catalog.offer(ids, trusted());
		assertThat(offer.offered()).hasSize(3);
		assertThat(offer.rejected()).hasSize(17);
	}

	@Test
	void duplicateIdsInOneOfferAreReleasedOnce() {
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		ToolCatalogOffer offer = catalog.offer(List.of(REFUND, REFUND), trusted());
		assertThat(offer.offered()).hasSize(1);
		assertThat(offer.rejected()).containsExactly(REFUND);
	}

	// --- Cacheable summaries ----------------------------------------------------------

	@Test
	void summariesAreCachedPerRevisionButAuthorizationIsNot() {
		AtomicReference<String> revision = new AtomicReference<>("r1");
		AtomicInteger listings = new AtomicInteger();
		InMemoryToolCatalogProvider delegate = payments();
		ToolCatalogProvider provider = new ToolCatalogProvider() {
			@Override
			public String providerId() {
				return "payments";
			}

			@Override
			public List<ToolSummary> summaries() {
				listings.incrementAndGet();
				return delegate.summaries();
			}

			@Override
			public java.util.Optional<ToolCatalogEntry> resolve(ToolId id, String version) {
				return delegate.resolve(id, version);
			}

			@Override
			public java.util.Optional<String> revision() {
				return java.util.Optional.of(revision.get());
			}
		};
		AtomicInteger decisions = new AtomicInteger();
		ToolCatalog catalog = ToolCatalog.builder().provider(provider).accessPolicy((access, summary, context) -> {
			decisions.incrementAndGet();
			return AuthorizationDecision.allow();
		}).build();
		ToolCatalogQuery query = ToolCatalogQuery.text("refund", 5);

		catalog.search(query, trusted());
		int decisionsAfterFirst = decisions.get();
		catalog.search(query, trusted());
		assertThat(listings).hasValue(1);
		assertThat(decisions.get()).isEqualTo(decisionsAfterFirst * 2);

		revision.set("r2");
		catalog.search(query, trusted());
		assertThat(listings).hasValue(2);

		catalog.invalidate();
		catalog.search(query, trusted());
		assertThat(listings).hasValue(3);
	}

	@Test
	void providersWithoutRevisionAreReadOnEverySearch() {
		FlakyProvider provider = new FlakyProvider(new ToolCatalogProvider() {
			private final InMemoryToolCatalogProvider delegate = payments();

			@Override
			public String providerId() {
				return "payments";
			}

			@Override
			public List<ToolSummary> summaries() {
				return this.delegate.summaries();
			}

			@Override
			public java.util.Optional<ToolCatalogEntry> resolve(ToolId id, String version) {
				return this.delegate.resolve(id, version);
			}
		});
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(provider)
			.accessPolicy(ToolCatalogAccessPolicy.allowAll())
			.build();

		catalog.search(ToolCatalogQuery.text("refund", 5), trusted());
		catalog.search(ToolCatalogQuery.text("refund", 5), trusted());
		assertThat(provider.summaryCalls).isEqualTo(2);
	}

	// --- Policies and compatibility ---------------------------------------------------

	@Test
	void executionAuthorizationPolicyCanBeReusedForCatalogAccess() {
		ToolAuthorizationPolicy execution = (toolName, context) -> "payments__refund".equals(toolName)
				? AuthorizationDecision.allow() : AuthorizationDecision.deny("no");
		ToolCatalog catalog = ToolCatalog.builder()
			.provider(payments())
			.accessPolicy(ToolCatalogAccessPolicy.fromToolAuthorizationPolicy(execution))
			.build();

		assertThat(catalog.search(ToolCatalogQuery.text("payment", 5), trusted()).tools()).extracting(ToolSummary::id)
			.containsExactly(REFUND);
	}

	@Test
	void existingRegistriesCanBeExposedAsReferenceCatalogs() {
		ToolRegistry registry = new ToolRegistry(List.of(tool("lookup", "Look up an order"), tool("clock", "Time")));
		InMemoryToolCatalogProvider provider = InMemoryToolCatalogProvider.fromRegistry("legacy", "1", registry);

		assertThat(provider.summaries()).extracting(summary -> summary.id().qualifiedName())
			.containsExactly("legacy__lookup", "legacy__clock");
		assertThat(provider.summaries().get(0).summary()).isEqualTo("Look up an order");
		assertThat(provider.resolve(ToolId.of("legacy", "lookup"), "1")).isPresent();
		assertThat(provider.resolve(ToolId.of("legacy", "lookup"), "2")).isEmpty();
	}

	@Test
	void toolIdRoundTripsAndRejectsAmbiguousNames() {
		ToolId id = ToolId.parse("acquirer-a__refund_v2");
		assertThat(id.provider()).isEqualTo("acquirer-a");
		assertThat(id.name()).isEqualTo("refund_v2");
		assertThat(id.qualifiedName()).isEqualTo("acquirer-a__refund_v2");

		assertThatThrownBy(() -> ToolId.of("a_b", "x")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ToolId.of("a", "x.y")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ToolId.parse("plain")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ToolId.parse("__x")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void summariesAndQueriesAreImmutableValueObjects() {
		java.util.HashSet<String> tags = new java.util.HashSet<>(Set.of("refund"));
		ToolSummary summary = new ToolSummary(REFUND, "1", null, " ", tags);
		tags.add("mutated");
		assertThat(summary.tags()).containsExactly("refund");
		assertThat(summary.summary()).isEmpty();
		assertThat(summary.domain()).isNull();
		assertThatThrownBy(() -> ToolCatalogQuery.text("x", 0)).isInstanceOf(IllegalArgumentException.class);
	}

}
