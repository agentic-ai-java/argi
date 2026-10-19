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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordToolCatalogSearchStrategyTest {

	private final KeywordToolCatalogSearchStrategy strategy = new KeywordToolCatalogSearchStrategy();

	private static final ToolSummary REFUND = new ToolSummary(ToolId.of("pay", "refund_order"), "1",
			"Refund a captured order payment", "payments", Set.of("refund", "after-sales"));

	private static final ToolSummary COUPON = new ToolSummary(ToolId.of("wallet", "issue_coupon"), "1",
			"发放钱包优惠券给指定用户", "wallet", Set.of("coupon"));

	private static final ToolSummary WEATHER = new ToolSummary(ToolId.of("misc", "weather"), "1",
			"Get the weather forecast", null, Set.of());

	private static final List<ToolSummary> ALL = List.of(REFUND, COUPON, WEATHER);

	@Test
	void ranksNameMatchesAboveSummaryMatches() {
		ToolSummary mentionsRefund = new ToolSummary(ToolId.of("crm", "ticket"), "1", "Open a ticket about a refund",
				null, Set.of());

		List<ToolSummary> result = this.strategy.search(ToolCatalogQuery.text("refund", 10),
				List.of(mentionsRefund, REFUND));

		assertThat(result).containsExactly(REFUND, mentionsRefund);
	}

	@Test
	void dropsCandidatesWithoutAnyMatch() {
		assertThat(this.strategy.search(ToolCatalogQuery.text("weather", 10), ALL)).containsExactly(WEATHER);
		assertThat(this.strategy.search(ToolCatalogQuery.text("translate text", 10), ALL)).isEmpty();
	}

	@Test
	void matchesChineseQueriesThroughHanBigrams() {
		assertThat(this.strategy.search(ToolCatalogQuery.text("帮我给用户发一张优惠券", 10), ALL)).containsExactly(COUPON);
	}

	@Test
	void appliesDomainAndTagRulesBeforeRanking() {
		ToolCatalogQuery byDomain = new ToolCatalogQuery("", Set.of("PAYMENTS"), Set.of(), 10);
		assertThat(this.strategy.search(byDomain, ALL)).containsExactly(REFUND);

		ToolCatalogQuery byTag = new ToolCatalogQuery("refund", Set.of(), Set.of("coupon"), 10);
		assertThat(this.strategy.search(byTag, ALL)).isEmpty();
	}

	@Test
	void unconstrainedQueryCannotEnumerateTheCatalog() {
		assertThat(this.strategy.search(ToolCatalogQuery.text("", 10), ALL)).isEmpty();
		assertThat(this.strategy.search(ToolCatalogQuery.text("?? !!", 10), ALL)).isEmpty();
	}

	@Test
	void singleCharacterTokensDoNotMatchSummaries() {
		assertThat(this.strategy.search(ToolCatalogQuery.text("a", 10), ALL)).isEmpty();
	}

}
