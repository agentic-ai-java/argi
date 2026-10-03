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
package io.github.agentic.ai.graph.store.stores;

import io.github.agentic.ai.graph.store.StoreItem;
import io.github.agentic.ai.graph.store.StoreSearchRequest;
import io.github.agentic.ai.graph.store.StoreSearchResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@ResourceLock(Resources.LOCALE)
class StoreSearchLocaleTest {

	@ParameterizedTest
	@CsvSource({ "TITLE,title,false", "title,TITLE,false", "TITLE,title,true", "title,TITLE,true" })
	void searchShouldIgnoreDefaultLocale(String text, String query, boolean searchValue) {
		Locale originalLocale = Locale.getDefault();
		Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
		Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			MemoryStore store = new MemoryStore();
			StoreItem matchingItem = StoreItem.of(List.of("memories"), searchValue ? "entry" : text,
					Map.of("content", searchValue ? text : "unrelated"));
			store.putItem(matchingItem);
			store.putItem(StoreItem.of(List.of("memories"), "other", Map.of("content", "unrelated")));

			StoreSearchResult result = store.searchItems(StoreSearchRequest.builder().query(query).build());

			assertThat(result.getItems()).containsExactly(matchingItem);
			assertThat(result.getTotalCount()).isEqualTo(1);
		}
		finally {
			Locale.setDefault(originalLocale);
			Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale);
			Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
		}
	}

}
