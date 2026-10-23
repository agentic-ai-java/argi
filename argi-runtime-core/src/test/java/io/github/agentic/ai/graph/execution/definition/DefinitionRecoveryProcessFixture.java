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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * A complete, independent-JVM recovery run.
 *
 * <p>
 * This process receives only a definition record JSON file. It registers the definition
 * versions its own build retained, resolves them and prints one result line. It shares no
 * object, callback or Future with the JVM that wrote the file, so a successful run is
 * evidence that the run can be rebuilt from committed data alone.
 */
public final class DefinitionRecoveryProcessFixture {

	/**
	 * The versions this worker build retained. A record that pins anything else must fail.
	 */
	private static final DefinitionReference RETAINED_GRAPH = DefinitionReference.of(DefinitionKind.GRAPH, "checkout",
			"1");

	private static final DefinitionReference RETAINED_TOOLS = DefinitionReference.of(DefinitionKind.TOOL_CATALOG, "main",
			"3");

	private static final DefinitionReference RETAINED_CODEC = DefinitionReference.of(DefinitionKind.CODEC, "argi-json",
			"2");

	private static final DefinitionReference RETAINED_APPLICATION = DefinitionReference.of(DefinitionKind.APPLICATION,
			"shop", "5");

	private static final Set<Class<?>> INSTANCE_HANDLES = Set.of(Supplier.class, Future.class, CompletableFuture.class,
			Callable.class, Runnable.class, Thread.class);

	private DefinitionRecoveryProcessFixture() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			throw new IllegalArgumentException("usage: DefinitionRecoveryProcessFixture <record-json-file>");
		}
		String json = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
		try {
			RunDefinitionRecord record = new RunDefinitionRecordCodec().decode(json);
			DefinitionRegistry registry = DefinitionRegistry.builder()
				.register(RETAINED_GRAPH, "checkout-graph-v1")
				.register(RETAINED_TOOLS, "main-tools-v3")
				.register(RETAINED_CODEC, "argi-json-codec-v2")
				.register(RETAINED_APPLICATION, "shop-v5")
				.build();
			ResolvedRunDefinitions resolved = RunDefinitionAdmission.resolve(record, registry,
					new WorkerCapabilities(1, Set.of(DefinitionKind.values()), Set.of(RETAINED_CODEC)));
			System.out.println("recovered graph=" + resolved.graphReference().display() + " toolCatalog="
					+ resolved.record().toolCatalog().display() + " codec=" + resolved.codecReference().display()
					+ " application=" + resolved.record().application().display() + " graphCode=" + resolved.graph()
					+ " recordHandles=" + (containsInstanceHandle(record) ? "found" : "none") + " jvm="
					+ ProcessHandle.current().pid() + " loadedBy="
					+ (DefinitionRecoveryProcessFixture.class.getClassLoader() == ClassLoader.getSystemClassLoader()
							? "application" : "other"));
		}
		catch (DefinitionResolutionException ex) {
			System.out.println("error=" + ex.code().name() + " reference=" + ex.reference().display() + " jvm="
					+ ProcessHandle.current().pid());
			System.exit(1);
		}
		catch (CodecIncompatibilityException ex) {
			System.out.println("error=" + ex.code().name() + " subject=" + ex.subject() + " recorded="
					+ ex.recorded() + " jvm=" + ProcessHandle.current().pid());
			System.exit(1);
		}
	}

	/**
	 * Walk the decoded record looking for a live handle to the admitting JVM. Committed
	 * recovery data must contain none.
	 */
	private static boolean containsInstanceHandle(Object root) {
		return containsInstanceHandle(root, new IdentityHashMap<>());
	}

	private static boolean containsInstanceHandle(Object value, Map<Object, Boolean> visited) {
		if (value == null || isScalar(value)) {
			return false;
		}
		if (INSTANCE_HANDLES.stream().anyMatch((handle) -> handle.isInstance(value))) {
			return true;
		}
		if (value.getClass().isArray()) {
			for (int i = 0; i < Array.getLength(value); i++) {
				if (containsInstanceHandle(Array.get(value, i), visited)) {
					return true;
				}
			}
			return false;
		}
		if (visited.put(value, Boolean.TRUE) != null) {
			return false;
		}
		for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				try {
					if (containsInstanceHandle(field.get(value), visited)) {
						return true;
					}
				}
				catch (ReflectiveOperationException | RuntimeException ex) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isScalar(Object value) {
		return value instanceof String || value instanceof Number || value instanceof Boolean
				|| value instanceof Character || value instanceof Enum<?> || value.getClass().isPrimitive();
	}

}
