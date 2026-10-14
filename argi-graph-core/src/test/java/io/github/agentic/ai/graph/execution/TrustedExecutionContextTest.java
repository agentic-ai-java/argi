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
package io.github.agentic.ai.graph.execution;

import io.github.agentic.ai.graph.RunnableConfig;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedExecutionContextTest {

	@Test
	void ordinaryMetadataCannotReplaceTrustedIdentity() {
		TrustedExecutionContext trusted = context("tenant-a", "principal-a", "run-a");
		RunnableConfig config = RunnableConfig.builder()
			.addMetadata("tenant_id", "forged-tenant")
			.addMetadata(RunnableConfig.USER_ID_METADATA_KEY, "forged-user")
			.trustedExecutionContext(trusted)
			.build();

		assertThat(config.trustedExecutionContext()).contains(trusted);
		assertThat(config.trustedExecutionContext().orElseThrow().tenant().id()).isEqualTo("tenant-a");
		assertThat(config.trustedExecutionContext().orElseThrow().principal().id()).isEqualTo("principal-a");
		assertThat(config.metadata("tenant_id")).contains("forged-tenant");
		assertThat(config.metadata(RunnableConfig.USER_ID_METADATA_KEY)).contains("forged-user");
		assertThat(RunnableConfig.builder().addMetadata("tenant_id", "only-metadata").build()
			.trustedExecutionContext()).isEmpty();
	}

	@Test
	void copiedConfigsPropagateImmutableScopeWithoutSharingMutableRunContext() {
		TrustedExecutionContext trusted = context("tenant-a", "principal-a", "run-a");
		RunnableConfig parent = RunnableConfig.builder()
			.trustedExecutionContext(trusted)
			.build();
		parent.context().put("mutable", "parent");

		RunnableConfig child = RunnableConfig.builder(parent).build();
		child.context().put("mutable", "child");

		assertThat(child.trustedExecutionContext()).contains(trusted);
		assertThat(parent.context()).containsEntry("mutable", "parent");
		assertThat(child.context()).containsEntry("mutable", "child");
	}

	@Test
	void delegationCanOnlyNarrowPermissionsDeadlineAndBudget() {
		TrustedExecutionContext parent = context("tenant-a", "principal-a", "run-parent");
		Instant earlier = parent.deadline().orElseThrow().minusSeconds(30);
		TrustedExecutionContext child = parent.delegate("run-child", Set.of("tools:read"), earlier,
				ExecutionBudget.of(Map.of("tool_calls", 2L)));

		assertThat(child.tenant()).isEqualTo(parent.tenant());
		assertThat(child.principal()).isEqualTo(parent.principal());
		assertThat(child.correlationId()).isEqualTo(parent.correlationId());
		assertThat(child.runId()).isEqualTo("run-child");
		assertThat(child.delegationScope().permissions()).containsExactly("tools:read");
		assertThat(child.deadline()).contains(earlier);
		assertThat(child.budget().limits()).containsEntry("tool_calls", 2L).containsEntry("model_calls", 10L);

		assertThatThrownBy(() -> parent.delegate("run-child", Set.of("tools:write"), earlier,
				ExecutionBudget.unlimited())).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("permission");
		assertThatThrownBy(() -> parent.delegate("run-child", Set.of("tools:read"),
				parent.deadline().orElseThrow().plusSeconds(1), ExecutionBudget.unlimited()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deadline");
		assertThatThrownBy(() -> parent.delegate("run-child", Set.of("tools:read"), earlier,
				ExecutionBudget.of(Map.of("tool_calls", 6L)))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("budget");
	}

	@Test
	void concurrentReactiveSubscriptionsDoNotShareTenantScope() {
		List<String> tenants = Flux.fromStream(IntStream.range(0, 64).boxed())
			.flatMap(index -> Mono.fromCallable(() -> {
				RunnableConfig config = RunnableConfig.builder()
					.trustedExecutionContext(context("tenant-" + index, "principal-" + index, "run-" + index))
					.build();
				return config.trustedExecutionContext().orElseThrow().tenant().id();
			}).subscribeOn(Schedulers.parallel()), 8)
			.collectList()
			.block();

		assertThat(tenants).containsExactlyInAnyOrderElementsOf(
				IntStream.range(0, 64).mapToObj(index -> "tenant-" + index).toList());
		assertThat(RunnableConfig.builder().build().trustedExecutionContext()).isEmpty();
	}

	@Test
	void versionedReferenceRequiresApplicationResolution() {
		TrustedExecutionContext trusted = context("tenant-a", "principal-a", "run-a");
		TrustedExecutionContextCodec codec = new TrustedExecutionContextCodec();
		String encoded = codec.encode(trusted.toReference());
		TrustedExecutionContextReference decoded = codec.decode(encoded);
		TrustedExecutionContextResolver resolver = reference -> reference.tenant().id().equals("tenant-a")
				? java.util.Optional.of(trusted)
				: java.util.Optional.empty();

		assertThat(decoded).isEqualTo(trusted.toReference());
		assertThat(resolver.resolveRequired(decoded)).isEqualTo(trusted);
		assertThat(encoded).doesNotContain("token").doesNotContain("credential").doesNotContain("client");
		assertThatThrownBy(() -> codec.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema version");
		assertThatThrownBy(() -> codec.decode(encoded.substring(0, encoded.length() - 1) + ",\"unknown\":true}"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid trusted execution context");
	}

	@Test
	void liveTrustedContextIsNotSerializedWithRunnableConfig() throws Exception {
		RunnableConfig config = RunnableConfig.builder()
			.addMetadata("business", "visible")
			.trustedExecutionContext(context("tenant-secret", "principal-secret", "run-secret"))
			.build();

		ObjectMapper mapper = new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
		String encoded = mapper.writeValueAsString(config);

		assertThat(encoded).doesNotContain("tenant-secret", "principal-secret", "run-secret",
				"trustedExecutionContext");
	}

	private TrustedExecutionContext context(String tenantId, String principalId, String runId) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("example-iam", tenantId))
			.principal(new PrincipalReference("example-iam", principalId))
			.runId(runId)
			.correlationId("correlation-a")
			.deadline(Instant.parse("2030-01-01T00:00:00Z"))
			.budget(ExecutionBudget.of(Map.of("model_calls", 10L, "tool_calls", 5L)))
			.delegationScope(DelegationScope.of(Set.of("agents:delegate", "tools:read")))
			.build();
	}
}
