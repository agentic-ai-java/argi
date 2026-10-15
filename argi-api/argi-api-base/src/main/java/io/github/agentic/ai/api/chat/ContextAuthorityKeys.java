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
package io.github.agentic.ai.api.chat;

/**
 * Reserved model-context keys for runtime authorization and secret configuration.
 *
 * <p>
 * Keys under {@link #PREFIX} must be supplied through trusted runtime metadata (for
 * example {@code RunnableConfig} metadata). Ordinary state payloads cannot invent or
 * overwrite them during model-context assembly.
 */
public final class ContextAuthorityKeys {

	/** Prefix for all protected authority keys. */
	public static final String PREFIX = "argi.authority.";

	/** Runtime authorization / policy snapshot. */
	public static final String AUTHORIZATION = PREFIX + "authorization";

	/** Secret or privileged configuration that must not come from user or retrieval state. */
	public static final String SECRET_CONFIG = PREFIX + "secretConfig";

	private ContextAuthorityKeys() {
	}

	/** Returns {@code true} when {@code key} is reserved for trusted runtime metadata. */
	public static boolean isProtected(String key) {
		return key != null && key.startsWith(PREFIX);
	}

}
