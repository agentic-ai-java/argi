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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dependency-free keyword and rule retrieval strategy.
 *
 * <p>
 * Rule filters from the query ({@code domains}, {@code tags}) are applied first. Free
 * text is then split into lowercase tokens; runs of Han characters additionally
 * contribute character bigrams so Chinese queries can match without a segmenter. Each
 * token scores {@value #NAME_WEIGHT} when it equals a token of the tool name,
 * {@value #LABEL_WEIGHT} when it equals the domain or a tag, and {@value #SUMMARY_WEIGHT}
 * when a token of at least two characters occurs in the summary. Candidates with a zero score are dropped; ties keep
 * catalog order. A query without text returns the rule-filtered candidates in catalog
 * order, and a query without text or filters returns nothing.
 * </p>
 */
public final class KeywordToolCatalogSearchStrategy implements ToolCatalogSearchStrategy {

	static final int NAME_WEIGHT = 3;

	static final int LABEL_WEIGHT = 2;

	static final int SUMMARY_WEIGHT = 1;

	@Override
	public List<ToolSummary> search(ToolCatalogQuery query, List<ToolSummary> candidates) {
		if (query.isUnconstrained()) {
			return List.of();
		}
		Set<String> domains = lower(query.domains());
		Set<String> tags = lower(query.tags());
		List<ToolSummary> filtered = new ArrayList<>();
		for (ToolSummary candidate : candidates) {
			if (!domains.isEmpty()
					&& (candidate.domain() == null || !domains.contains(candidate.domain().toLowerCase(Locale.ROOT)))) {
				continue;
			}
			if (!tags.isEmpty() && lower(candidate.tags()).stream().noneMatch(tags::contains)) {
				continue;
			}
			filtered.add(candidate);
		}
		Set<String> tokens = tokens(query.text());
		if (tokens.isEmpty()) {
			// Text without searchable tokens only narrows; it never enumerates.
			return domains.isEmpty() && tags.isEmpty() ? List.of() : filtered;
		}
		List<Scored> scored = new ArrayList<>();
		for (int i = 0; i < filtered.size(); i++) {
			int score = score(filtered.get(i), tokens);
			if (score > 0) {
				scored.add(new Scored(filtered.get(i), score, i));
			}
		}
		scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order));
		return scored.stream().map(Scored::summary).toList();
	}

	private static int score(ToolSummary candidate, Set<String> tokens) {
		Set<String> nameTokens = tokens(candidate.id().name());
		Set<String> labels = lower(candidate.tags());
		if (candidate.domain() != null) {
			labels.add(candidate.domain().toLowerCase(Locale.ROOT));
		}
		String summary = candidate.summary().toLowerCase(Locale.ROOT);
		int score = 0;
		for (String token : tokens) {
			if (nameTokens.contains(token)) {
				score += NAME_WEIGHT;
			}
			else if (labels.contains(token)) {
				score += LABEL_WEIGHT;
			}
			else if (token.codePointCount(0, token.length()) > 1 && summary.contains(token)) {
				score += SUMMARY_WEIGHT;
			}
		}
		return score;
	}

	static Set<String> tokens(String text) {
		Set<String> tokens = new LinkedHashSet<>();
		if (text == null) {
			return tokens;
		}
		StringBuilder current = new StringBuilder();
		for (int offset = 0; offset < text.length();) {
			int codePoint = text.codePointAt(offset);
			if (Character.isLetterOrDigit(codePoint)) {
				current.appendCodePoint(Character.toLowerCase(codePoint));
			}
			else {
				flush(current, tokens);
			}
			offset += Character.charCount(codePoint);
		}
		flush(current, tokens);
		return tokens;
	}

	private static void flush(StringBuilder current, Set<String> tokens) {
		if (current.isEmpty()) {
			return;
		}
		String token = current.toString();
		tokens.add(token);
		StringBuilder han = new StringBuilder();
		token.codePoints().forEach(codePoint -> {
			if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
				han.appendCodePoint(codePoint);
			}
			else {
				bigrams(han, tokens);
				han.setLength(0);
			}
		});
		bigrams(han, tokens);
		current.setLength(0);
	}

	private static void bigrams(StringBuilder han, Set<String> tokens) {
		int[] codePoints = han.codePoints().toArray();
		for (int i = 0; i + 1 < codePoints.length; i++) {
			tokens.add(new String(codePoints, i, 2));
		}
	}

	private static Set<String> lower(Set<String> values) {
		Set<String> lower = new LinkedHashSet<>();
		for (String value : values) {
			lower.add(value.toLowerCase(Locale.ROOT));
		}
		return lower;
	}

	private record Scored(ToolSummary summary, int score, int order) {
	}

}
