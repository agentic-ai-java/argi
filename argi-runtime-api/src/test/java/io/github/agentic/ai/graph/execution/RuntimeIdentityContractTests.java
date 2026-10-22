/*
 * Copyright 2026 the original author or authors.
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

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests run without Graph, Reactor, Spring or any model SDK on the classpath. */
class RuntimeIdentityContractTests {

	private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t" })
	void identityReferencesRejectMissingAuthorityAndIdentifiers(String invalid) {
		assertThrows(IllegalArgumentException.class, () -> new TenantReference(invalid, "tenant"));
		assertThrows(IllegalArgumentException.class, () -> new TenantReference("iam", invalid));
		assertThrows(IllegalArgumentException.class, () -> new PrincipalReference(invalid, "principal"));
		assertThrows(IllegalArgumentException.class, () -> new PrincipalReference("iam", invalid));
	}

	@Test
	void referencesKeepAuthorityAsPartOfIdentity() {
		TenantReference tenant = new TenantReference("iam", "tenant");
		PrincipalReference principal = new PrincipalReference("iam", "principal");
		assertEquals("iam", tenant.authority());
		assertEquals("tenant", tenant.id());
		assertEquals("iam", principal.authority());
		assertEquals("principal", principal.id());
		assertNotEquals(tenant, new TenantReference("another-iam", "tenant"));
		assertNotEquals(principal, new PrincipalReference("another-iam", "principal"));
	}

	@Test
	void limitsAndPermissionsAreDefensiveImmutableCopies() {
		Map<String, Long> limits = new HashMap<>(Map.of("model_calls", 10L));
		Set<String> permissions = new HashSet<>(Set.of("tools:read"));
		ExecutionBudget budget = ExecutionBudget.of(limits);
		DelegationScope scope = DelegationScope.of(permissions);
		limits.put("model_calls", 100L);
		permissions.add("tools:write");
		assertEquals(Map.of("model_calls", 10L), budget.limits());
		assertEquals(Set.of("tools:read"), scope.permissions());
		assertThrows(UnsupportedOperationException.class, () -> budget.limits().put("tool_calls", 1L));
		assertThrows(UnsupportedOperationException.class, () -> scope.permissions().add("tools:write"));
		assertTrue(scope.allows("tools:read"));
		assertFalse(scope.allows("tools:write"));
		assertFalse(scope.allows(null));
	}

	@Test
	void emptyConstraintFactoriesKeepTheirDocumentedDefaults() {
		assertSame(ExecutionBudget.unlimited(), ExecutionBudget.of(null));
		assertSame(ExecutionBudget.unlimited(), ExecutionBudget.of(Map.of()));
		assertSame(DelegationScope.none(), DelegationScope.of(null));
		assertSame(DelegationScope.none(), DelegationScope.of(Set.of()));
		assertThrows(NullPointerException.class, () -> new ExecutionBudget(null));
		assertThrows(NullPointerException.class, () -> new DelegationScope(null));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t" })
	void constraintsRejectInvalidNames(String invalid) {
		Map<String, Long> limits = new HashMap<>();
		limits.put(invalid, 1L);
		Set<String> permissions = new HashSet<>();
		permissions.add(invalid);
		assertThrows(IllegalArgumentException.class, () -> new ExecutionBudget(limits));
		assertThrows(IllegalArgumentException.class, () -> new DelegationScope(permissions));
	}

	@Test
	void limitsRejectMissingAndNegativeAmountsButAllowZero() {
		Map<String, Long> limits = new HashMap<>();
		limits.put("model_calls", null);
		assertThrows(IllegalArgumentException.class, () -> new ExecutionBudget(limits));
		assertThrows(IllegalArgumentException.class, () -> ExecutionBudget.of(Map.of("model_calls", -1L)));
		assertEquals(0L, ExecutionBudget.of(Map.of("model_calls", 0L)).limits().get("model_calls"));
	}

	@Test
	void narrowingInheritsOmittedLimitsAndRejectsEscalation() {
		ExecutionBudget parent = ExecutionBudget.of(Map.of("model_calls", 10L, "tool_calls", 5L));
		assertSame(parent, parent.narrowTo(ExecutionBudget.unlimited()));
		assertEquals(Map.of("model_calls", 10L, "tool_calls", 2L, "bytes", 100L),
			parent.narrowTo(ExecutionBudget.of(Map.of("tool_calls", 2L, "bytes", 100L))).limits());
		assertThrows(IllegalArgumentException.class,
			() -> parent.narrowTo(ExecutionBudget.of(Map.of("model_calls", 11L))));
		assertThrows(NullPointerException.class, () -> parent.narrowTo(null));
		DelegationScope scope = DelegationScope.of(Set.of("tools:read", "agents:delegate"));
		assertEquals(Set.of("tools:read"), scope.narrowTo(Set.of("tools:read")).permissions());
		assertSame(DelegationScope.none(), scope.narrowTo(Set.of()));
		assertThrows(IllegalArgumentException.class, () -> scope.narrowTo(Set.of("tools:write")));
		assertThrows(NullPointerException.class, () -> scope.narrowTo(null));
	}

	@Test
	void delegationPreservesIdentityAndNarrowsConstraints() {
		TrustedExecutionContext parent = builder().build();
		TrustedExecutionContext child = parent.delegate("child", Set.of("tools:read"), DEADLINE.minusSeconds(5),
			ExecutionBudget.of(Map.of("model_calls", 2L)));
		assertEquals(parent.tenant(), child.tenant());
		assertEquals(parent.principal(), child.principal());
		assertEquals(parent.correlationId(), child.correlationId());
		assertEquals("child", child.runId());
		assertEquals(Optional.of(DEADLINE.minusSeconds(5)), child.deadline());
		assertEquals(Map.of("model_calls", 2L), child.budget().limits());
		assertEquals(Set.of("tools:read"), child.delegationScope().permissions());
		TrustedExecutionContext inherited = parent.delegate("child", Set.of(), null, null);
		assertEquals(parent.deadline(), inherited.deadline());
		assertSame(parent.budget(), inherited.budget());
		assertThrows(IllegalArgumentException.class,
			() -> parent.delegate("child", Set.of(), DEADLINE.plusSeconds(1), null));
		assertThrows(IllegalArgumentException.class,
			() -> parent.delegate("child", Set.of("tools:write"), null, null));
		assertThrows(IllegalArgumentException.class,
			() -> parent.delegate("child", Set.of(), null, ExecutionBudget.of(Map.of("model_calls", 11L))));
	}

	@Test
	void absentDeadlineCanBeNarrowedAndExpirationIsInclusive() {
		TrustedExecutionContext bounded = builder().build();
		assertFalse(bounded.isExpired(DEADLINE.minusNanos(1)));
		assertTrue(bounded.isExpired(DEADLINE));
		assertTrue(bounded.isExpired(DEADLINE.plusNanos(1)));
		assertThrows(NullPointerException.class, () -> bounded.isExpired(null));
		TrustedExecutionContext unbounded = builder().deadline(null).build();
		assertFalse(unbounded.isExpired(DEADLINE));
		assertTrue(unbounded.delegate("child", Set.of(), null, null).deadline().isEmpty());
		assertEquals(Optional.of(DEADLINE), unbounded.delegate("child", Set.of(), DEADLINE, null).deadline());
		assertEquals(null, unbounded.toReference().deadlineEpochMillis());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t" })
	void executionAndPersistenceReferencesRequireRunAndCorrelationIds(String invalid) {
		assertThrows(IllegalArgumentException.class, () -> builder().runId(invalid).build());
		assertThrows(IllegalArgumentException.class, () -> builder().correlationId(invalid).build());
		TrustedExecutionContextReference reference = builder().build().toReference();
		assertThrows(IllegalArgumentException.class, () -> new TrustedExecutionContextReference(1, reference.tenant(),
			reference.principal(), invalid, "correlation", null, Map.of(), Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new TrustedExecutionContextReference(1, reference.tenant(),
			reference.principal(), "run", invalid, null, Map.of(), Set.of()));
	}

	@Test
	void builderRejectsMissingIdentityAndConstraintObjects() {
		assertThrows(NullPointerException.class, () -> builder().tenant(null).build());
		assertThrows(NullPointerException.class, () -> builder().principal(null).build());
		assertThrows(NullPointerException.class, () -> builder().budget(null).build());
		assertThrows(NullPointerException.class, () -> builder().delegationScope(null).build());
		TrustedExecutionContext defaults = TrustedExecutionContext.builder()
			.tenant(new TenantReference("iam", "tenant")).principal(new PrincipalReference("iam", "principal"))
			.runId("run").correlationId("correlation").build();
		assertSame(ExecutionBudget.unlimited(), defaults.budget());
		assertSame(DelegationScope.none(), defaults.delegationScope());
	}

	@Test
	void referencesCopyMutableCollectionsAndKeepSchemaAndDeadline() {
		TrustedExecutionContext trusted = builder().build();
		TrustedExecutionContextReference reference = trusted.toReference();
		assertEquals(1, reference.schemaVersion());
		assertEquals(DEADLINE.toEpochMilli(), reference.deadlineEpochMillis());
		Map<String, Long> limits = new HashMap<>(reference.budgetLimits());
		Set<String> permissions = new HashSet<>(reference.delegationPermissions());
		TrustedExecutionContextReference copied = new TrustedExecutionContextReference(1, reference.tenant(),
			reference.principal(), reference.runId(), reference.correlationId(), reference.deadlineEpochMillis(),
			limits, permissions);
		limits.clear();
		permissions.clear();
		assertEquals(reference, copied);
		assertThrows(UnsupportedOperationException.class, () -> copied.budgetLimits().clear());
		assertThrows(UnsupportedOperationException.class, () -> copied.delegationPermissions().clear());
		assertThrows(IllegalArgumentException.class, () -> new TrustedExecutionContextReference(0, reference.tenant(),
			reference.principal(), "run", "correlation", null, Map.of(), Set.of()));
		assertThrows(NullPointerException.class, () -> new TrustedExecutionContextReference(1, null,
			reference.principal(), "run", "correlation", null, Map.of(), Set.of()));
		assertThrows(NullPointerException.class, () -> new TrustedExecutionContextReference(1, reference.tenant(),
			null, "run", "correlation", null, Map.of(), Set.of()));
		assertThrows(NullPointerException.class, () -> new TrustedExecutionContextReference(1, reference.tenant(),
			reference.principal(), "run", "correlation", null, null, Set.of()));
		assertThrows(NullPointerException.class, () -> new TrustedExecutionContextReference(1, reference.tenant(),
			reference.principal(), "run", "correlation", null, Map.of(), null));
	}

	@Test
	void persistedIdentityMustBeResolvedAtTheApplicationBoundary() {
		TrustedExecutionContext trusted = builder().build();
		TrustedExecutionContextResolver resolver = reference -> reference.equals(trusted.toReference())
			? Optional.of(trusted) : Optional.empty();
		assertSame(trusted, resolver.resolveRequired(trusted.toReference()));
		assertThrows(IllegalStateException.class,
			() -> resolver.resolveRequired(builder().runId("different-run").build().toReference()));
		assertThrows(NullPointerException.class, () -> resolver.resolveRequired(null));
		TrustedExecutionContextResolver invalid = reference -> null;
		assertThrows(NullPointerException.class, () -> invalid.resolveRequired(trusted.toReference()));
	}

	@Test
	void contextEqualityAndDiagnosticsRetainAllIdentityAndConstraintFields() {
		TrustedExecutionContext original = builder().build();
		assertEquals(original, original);
		assertEquals(original, builder().build());
		assertEquals(original.hashCode(), builder().build().hashCode());
		assertNotEquals(original, null);
		assertNotEquals(original, "run");
		assertTrue(original.toString().contains("runId='run'"));
		assertTrue(original.toString().contains("model_calls=10"));
	}

	@ParameterizedTest
	@MethodSource("differentContexts")
	void eachIdentityOrConstraintFieldContributesToEquality(Consumer<TrustedExecutionContext.Builder> change) {
		TrustedExecutionContext.Builder different = builder();
		change.accept(different);
		assertNotEquals(builder().build(), different.build());
	}

	private static Stream<Consumer<TrustedExecutionContext.Builder>> differentContexts() {
		return Stream.of(candidate -> candidate.tenant(new TenantReference("iam", "other")),
			candidate -> candidate.principal(new PrincipalReference("iam", "other")),
			candidate -> candidate.runId("other"), candidate -> candidate.correlationId("other"),
			candidate -> candidate.deadline(null), candidate -> candidate.budget(ExecutionBudget.unlimited()),
			candidate -> candidate.delegationScope(DelegationScope.none()));
	}

	private static TrustedExecutionContext.Builder builder() {
		return TrustedExecutionContext.builder().tenant(new TenantReference("iam", "tenant"))
			.principal(new PrincipalReference("iam", "principal")).runId("run").correlationId("correlation")
			.deadline(DEADLINE).budget(ExecutionBudget.of(Map.of("model_calls", 10L)))
			.delegationScope(DelegationScope.of(Set.of("tools:read", "agents:delegate")));
	}

}
