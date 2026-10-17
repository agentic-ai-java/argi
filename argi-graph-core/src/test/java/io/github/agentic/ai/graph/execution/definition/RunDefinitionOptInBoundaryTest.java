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

import io.github.agentic.ai.graph.execution.run.MemoryRunStore;
import io.github.agentic.ai.graph.execution.run.RunPayload;
import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunSpecCodec;
import io.github.agentic.ai.graph.execution.run.RunStore;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The definition API is additive. Existing callers keep the same records, codecs,
 * defaults and store contracts, need no registry, and no recovery entry point accepts a
 * callback or a Future.
 */
class RunDefinitionOptInBoundaryTest {

	private static final Set<Class<?>> INSTANCE_HANDLE_TYPES = Set.of(Supplier.class, Future.class,
			CompletableFuture.class, Callable.class, Runnable.class);

	@Test
	void existingRunContractsCarryNoDefinitionTypes() {
		assertThat(RunSpec.CURRENT_SCHEMA_VERSION).isEqualTo(1);
		assertThat(typesOf(RunSpec.class)).noneMatch(RunDefinitionOptInBoundaryTest::isDefinitionType);
		assertThat(typesOf(RunStore.class)).noneMatch(RunDefinitionOptInBoundaryTest::isDefinitionType);
		assertThat(typesOf(MemoryRunStore.class)).noneMatch(RunDefinitionOptInBoundaryTest::isDefinitionType);
	}

	@Test
	void runAdmissionStillWorksWithoutAnyDefinitionObject() {
		RunSpec spec = RunSpec.from(DefinitionTestSupport.context("run-1"), "app", "session", "key", "checkout", "1",
				new RunPayload(1, "test.input", "{}"));
		RunSpecCodec codec = new RunSpecCodec();

		assertThat(codec.decodeRun(codec.encodeRun(spec))).isEqualTo(spec);
		assertThat(new MemoryRunStore().submit(spec, DefinitionTestSupport.context("run-1")).spec().graphId())
			.isEqualTo("checkout");
	}

	@Test
	void recoveryEntryPointTakesDataAndNeverAnInstanceHandle() {
		for (Method method : RunDefinitionAdmission.class.getDeclaredMethods()) {
			Class<?>[] parameterTypes = method.getParameterTypes();
			assertThat(parameterTypes).as("%s parameters", method.getName())
				.noneMatch(RunDefinitionOptInBoundaryTest::isInstanceHandleType);
			assertThat(method.getReturnType()).isNotIn(INSTANCE_HANDLE_TYPES);
			for (Type generic : method.getGenericParameterTypes()) {
				assertThat(genericArguments(generic))
					.as("%s generic parameters", method.getName())
					.noneMatch(RunDefinitionOptInBoundaryTest::isInstanceHandleType);
			}
		}
	}

	@Test
	void recordedDefinitionDataIsPlainData() throws Exception {
		assertThat(RunDefinitionRecord.class.getRecordComponents()).extracting(RecordComponent::getType)
			.containsExactly(int.class, DefinitionReference.class, DefinitionReference.class, DefinitionReference.class,
					DefinitionReference.class);
		assertThat(DefinitionReference.class.getRecordComponents()).extracting(RecordComponent::getType)
			.containsExactly(DefinitionKind.class, String.class, String.class);
		assertThat(DefinitionResolver.class.getMethod("resolve", DefinitionReference.class).getReturnType())
			.isEqualTo(java.util.Optional.class);
	}

	private static List<Class<?>> typesOf(Class<?> type) {
		return Arrays.stream(type.getMethods())
			.flatMap((method) -> java.util.stream.Stream.concat(
					Arrays.stream(method.getParameterTypes()), java.util.stream.Stream.of(method.getReturnType())))
			.toList();
	}

	private static List<Class<?>> genericArguments(Type type) {
		if (type instanceof ParameterizedType parameterized) {
			List<Class<?>> arguments = new ArrayList<>();
			for (Type argument : parameterized.getActualTypeArguments()) {
				if (argument instanceof Class<?> typeArgument) {
					arguments.add(typeArgument);
				}
			}
			return arguments;
		}
		return List.of();
	}

	private static boolean isDefinitionType(Class<?> type) {
		Package typePackage = type.getPackage();
		return typePackage != null && typePackage.getName().equals(RunDefinitionRecord.class.getPackage().getName());
	}

	private static boolean isInstanceHandleType(Class<?> type) {
		return INSTANCE_HANDLE_TYPES.stream().anyMatch((handle) -> handle.isAssignableFrom(type));
	}

}
