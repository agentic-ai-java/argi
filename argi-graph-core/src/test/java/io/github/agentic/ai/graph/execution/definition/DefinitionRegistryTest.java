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

package io.github.agentic.ai.graph.execution.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.APP_V5;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.CODEC_V2;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.GRAPH_V1;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.GRAPH_V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionRegistryTest {

	@Test
	void resolvesExactlyTheRegisteredPinnedVersion() {
		DefinitionRegistry registry = DefinitionTestSupport.registryWithAllVersions();

		assertThat(registry.resolve(GRAPH_V1)).contains("graph-v1");
		assertThat(registry.resolve(GRAPH_V2)).contains("graph-v2");
		assertThat(registry.resolve(DefinitionReference.of(DefinitionKind.GRAPH, "checkout", "3"))).isEmpty();
		assertThat(registry.contains(GRAPH_V1)).isTrue();
		assertThat(registry.size()).isEqualTo(5);
	}

	@Test
	void rejectsDuplicateRegistrationSoARetainedVersionCannotChange() {
		DefinitionRegistry.Builder builder = DefinitionRegistry.builder().register(GRAPH_V1, "first");

		assertThatThrownBy(() -> builder.register(GRAPH_V1, "second"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("already registered");
		assertThat(builder.build().resolve(GRAPH_V1)).contains("first");
	}

	@Test
	void reportsUnregisteredDefinitionsStructurally() {
		DefinitionRegistry registry = DefinitionRegistry.builder().register(GRAPH_V1, Map.of("nodes", 2)).build();

		assertThatThrownBy(() -> registry.resolveRequired(CODEC_V2))
			.isInstanceOfSatisfying(DefinitionResolutionException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.NOT_REGISTERED);
				assertThat(ex.reference()).isEqualTo(CODEC_V2);
				assertThat(ex.details()).isEmpty();
			});
	}

	@Test
	void reportsTypeMismatchStructurally() {
		DefinitionRegistry registry = DefinitionRegistry.builder().register(GRAPH_V1, Map.of("nodes", 2)).build();

		assertThatThrownBy(() -> registry.resolveRequired(GRAPH_V1, String.class))
			.isInstanceOfSatisfying(DefinitionResolutionException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.TYPE_MISMATCH);
				assertThat(ex.expectedType()).contains(String.class.getName());
			});
		assertThat(registry.resolveRequired(GRAPH_V1, Map.class)).isEqualTo(Map.of("nodes", 2));
	}

	@Test
	void builtRegistryDoesNotFollowLaterBuilderChanges() {
		DefinitionRegistry.Builder builder = DefinitionRegistry.builder().register(GRAPH_V2, "graph-v2");
		DefinitionRegistry registry = builder.build();
		builder.register(GRAPH_V1, "graph-v1");

		assertThat(registry.resolve(GRAPH_V1)).isEmpty();
		assertThat(registry.references()).containsExactly(GRAPH_V2);
	}

	@Test
	void listsRetainedReferencesInDeterministicOrder() {
		DefinitionRegistry.Builder builder = DefinitionRegistry.builder();
		builder.register(APP_V5, "app").register(CODEC_V2, "codec").register(GRAPH_V2, "g2").register(GRAPH_V1, "g1");
		DefinitionRegistry registry = builder.build();

		assertThat(registry.references()).containsExactly(GRAPH_V1, GRAPH_V2, CODEC_V2, APP_V5);
		assertThat(DefinitionRegistry.empty().references()).isEmpty();
		assertThat(DefinitionRegistry.empty().resolve(GRAPH_V1)).isEmpty();
	}

	@Test
	void rejectsNullReferences() {
		DefinitionRegistry registry = DefinitionRegistry.empty();

		assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> registry.contains(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> DefinitionRegistry.builder().register(null, "value"))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> DefinitionRegistry.builder().register(GRAPH_V1, null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void aCustomResolverDoesNotNeedTheRegistry() {
		DefinitionResolver resolver = (reference) -> GRAPH_V1.equals(reference) ? Optional.of("graph-v1") : Optional.empty();

		assertThat(resolver.resolveRequired(GRAPH_V1)).isEqualTo("graph-v1");
		assertThat(resolver.resolve(CODEC_V2)).isEmpty();
	}

	@Test
	void recordsResolutionAttemptsForFailFastAssertions() {
		List<DefinitionReference> requested = new ArrayList<>();
		DefinitionResolver delegate = (reference) -> Optional.of("value");
		DefinitionResolver recording = (reference) -> {
			requested.add(reference);
			return delegate.resolve(reference);
		};

		assertThat(recording.resolveRequired(GRAPH_V1)).isEqualTo("value");
		assertThat(requested).containsExactly(GRAPH_V1);
	}

}
