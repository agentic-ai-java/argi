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
package io.github.agentic.ai.graph.agent.hook.contentsafety;

import java.util.ArrayList;
import java.util.List;

/** Detector for configured literal secrets. Useful as a reference detector in tests. */
public final class LiteralContentDetector implements ContentDetector {

	private final String name;

	private final List<String> literals;

	public LiteralContentDetector(String name, List<String> literals) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		this.name = name;
		this.literals = List.copyOf(literals);
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public List<ContentSpan> detect(String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<ContentSpan> spans = new ArrayList<>();
		for (String literal : literals) {
			if (literal == null || literal.isEmpty()) {
				continue;
			}
			int from = 0;
			while (from <= text.length() - literal.length()) {
				int found = text.indexOf(literal, from);
				if (found < 0) {
					break;
				}
				spans.add(new ContentSpan(found, found + literal.length(), name));
				from = found + literal.length();
			}
		}
		spans.sort((left, right) -> Integer.compare(left.start(), right.start()));
		return List.copyOf(spans);
	}

	@Override
	public int openSuffix(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int open = 0;
		for (String literal : literals) {
			if (literal == null || literal.length() < 2) {
				continue;
			}
			int max = Math.min(literal.length() - 1, text.length());
			for (int length = max; length > open; length--) {
				if (text.endsWith(literal.substring(0, length))) {
					open = length;
					break;
				}
			}
		}
		return open;
	}

}
