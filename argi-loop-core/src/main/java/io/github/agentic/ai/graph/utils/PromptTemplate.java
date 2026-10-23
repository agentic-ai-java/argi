/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.utils;

import java.util.Map;
import java.util.Objects;

import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.compiler.STException;
import org.stringtemplate.v4.misc.ErrorBuffer;

/**
 * Renders prompt text without depending on a model framework. Custom renderers can
 * implement this interface; shared implementations must be safe for concurrent use.
 */
@FunctionalInterface
public interface PromptTemplate {

	String render(Map<String, Object> variables);

	/**
	 * Creates a StringTemplate4 renderer with {@code {name}} placeholders. Each render
	 * has isolated attributes and error handling. Missing variables and invalid templates
	 * fail explicitly instead of silently producing a partial prompt.
	 * @param text the template text
	 * @return a thread-safe renderer
	 */
	static PromptTemplate of(String text) {
		return of(text, '{', '}');
	}

	/**
	 * Creates a renderer with custom single-character StringTemplate delimiters. For
	 * other template engines, implement {@link PromptTemplate} directly.
	 * @param text the template text
	 * @param startDelimiter the opening delimiter
	 * @param endDelimiter the closing delimiter
	 * @return a thread-safe renderer
	 */
	static PromptTemplate of(String text, char startDelimiter, char endDelimiter) {
		Objects.requireNonNull(text, "template must not be null");
		return variables -> {
			Objects.requireNonNull(variables, "variables must not be null");
			ErrorBuffer errors = new ErrorBuffer();
			STGroup group = new STGroup(startDelimiter, endDelimiter);
			group.setListener(errors);
			ST template;
			try {
				template = new ST(group, text);
			}
			catch (STException ex) {
				throw new IllegalArgumentException("Invalid prompt template: " + errors, ex);
			}
			variables.forEach(template::add);
			String rendered = template.render();
			if (!errors.errors.isEmpty()) {
				throw new IllegalArgumentException("Invalid prompt template: " + errors);
			}
			return rendered;
		};
	}

}
