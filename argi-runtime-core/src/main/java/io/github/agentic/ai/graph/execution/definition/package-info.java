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

/**
 * Opt-in, application-owned version-pinned run definitions and recovery capability
 * checks.
 *
 * <p>
 * A durable run records the execution target, tool catalog/schema, data codec and application
 * definition versions it was admitted with. Recovery re-resolves those recorded
 * versions at the application boundary; it must never silently fall back to the
 * currently registered latest version. Definition code stays application-owned and
 * is registered as data-neutral identifiers, not serialized closures, Suppliers or
 * Futures.
 *
 * <p>
 * Registry admission is opt-in. A run always pins a definition reference, but does not
 * need a registry until the application explicitly resolves it. Graph version-1 data
 * retains its legacy fields; version-2 data carries an engine-neutral definition.
 */
package io.github.agentic.ai.graph.execution.definition;
