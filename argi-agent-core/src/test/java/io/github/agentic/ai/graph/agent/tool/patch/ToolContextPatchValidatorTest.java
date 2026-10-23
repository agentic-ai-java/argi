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
package io.github.agentic.ai.graph.agent.tool.patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.agent.tool.patch.ToolContextPatchValidator.KEY_NOT_DECLARED;
import static io.github.agentic.ai.graph.agent.tool.patch.ToolContextPatchValidator.RESERVED_KEY;
import static io.github.agentic.ai.graph.agent.tool.patch.ToolContextPatchValidator.TYPE_MISMATCH;
import static io.github.agentic.ai.graph.agent.tool.patch.ToolContextPatchValidator.isReservedKey;
import static io.github.agentic.ai.graph.agent.tool.patch.ToolContextPatchValidator.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the reserved-namespace and spec rules of the context patch validator.
 */
class ToolContextPatchValidatorTest {

	@Test
	void messagesKeyIsReserved() {
		assertThat(isReservedKey("messages")).isTrue();
	}

	@Test
	void argiNamespaceIsReserved() {
		assertThat(isReservedKey("argi.toolBindingSnapshot")).isTrue();
		assertThat(isReservedKey("argi.authority.authorization")).isTrue();
		assertThat(isReservedKey("argi.actionDecision.final")).isTrue();
		assertThat(isReservedKey("argi.lease")).isTrue();
	}

	@Test
	void businessKeysAreNotReserved() {
		assertThat(isReservedKey("todos")).isFalse();
		assertThat(isReservedKey("searchResults")).isFalse();
		assertThat(isReservedKey(null)).isFalse();
	}

	@Test
	void rejectsReservedKeyEvenWithoutSpec() {
		var failure = validate(null, "tool", Map.of("messages", List.of())).orElseThrow();
		assertThat(failure.errorCode()).isEqualTo(RESERVED_KEY);
		assertThat(failure.key()).isEqualTo("messages");
	}

	@Test
	void rejectsKeyOutsideDeclaredWritableKeys() {
		var spec = ToolContextPatchSpec.builder().writableKey("allowed").build();
		var failure = validate(spec, "tool", Map.of("other", "value")).orElseThrow();
		assertThat(failure.errorCode()).isEqualTo(KEY_NOT_DECLARED);
		assertThat(failure.key()).isEqualTo("other");
	}

	@Test
	void allowsDeclaredKeys() {
		var spec = ToolContextPatchSpec.builder().writableKey("allowed").build();
		assertThat(validate(spec, "tool", Map.of("allowed", "value"))).isEmpty();
	}

	@Test
	void allowsAnyNonReservedKeyWithoutDeclaration() {
		assertThat(validate(ToolContextPatchSpec.builder().build(), "tool", Map.of("any", "value"))).isEmpty();
		assertThat(validate(null, "tool", Map.of("any", "value"))).isEmpty();
	}

	@Test
	void rejectsValueTypeMismatch() {
		var spec = ToolContextPatchSpec.builder().keyType("count", Integer.class).build();
		var failure = validate(spec, "tool", Map.of("count", "not a number")).orElseThrow();
		assertThat(failure.errorCode()).isEqualTo(TYPE_MISMATCH);
		assertThat(failure.key()).isEqualTo("count");
	}

	@Test
	void acceptsSubtypesOfDeclaredType() {
		var spec = ToolContextPatchSpec.builder().keyType("items", List.class).build();
		assertThat(validate(spec, "tool", Map.of("items", new ArrayList<String>()))).isEmpty();
	}

	@Test
	void reportsFailuresDeterministicallyAcrossKeyOrder() {
		var spec = ToolContextPatchSpec.builder().writableKey("a").build();
		var updates = new HashMap<String, Object>();
		updates.put("undeclared", "value");
		updates.put("argi.lease", "value");
		// Sorted iteration must report the argi.* violation before the undeclared key.
		var failure = validate(spec, "tool", updates).orElseThrow();
		assertThat(failure.errorCode()).isEqualTo(RESERVED_KEY);
		assertThat(failure.key()).isEqualTo("argi.lease");
	}

	@Test
	void failureMessagesNeverContainPatchValues() {
		var spec = ToolContextPatchSpec.builder().keyType("count", Integer.class).build();
		var mismatch = validate(spec, "tool", Map.of("count", "SECRET-VALUE")).orElseThrow();
		assertThat(mismatch.message()).doesNotContain("SECRET-VALUE");

		var reserved = validate(null, "tool", Map.of("messages", "SECRET-MESSAGES")).orElseThrow();
		assertThat(reserved.message()).doesNotContain("SECRET-MESSAGES");
	}

	@Test
	void emptyPatchIsValid() {
		assertThat(validate(null, "tool", Map.of())).isEmpty();
		assertThat(validate(null, "tool", null)).isEmpty();
	}

}
