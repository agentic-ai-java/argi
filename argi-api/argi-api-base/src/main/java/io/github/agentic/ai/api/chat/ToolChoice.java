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
package io.github.agentic.ai.api.chat;

/** Controls model tool selection. */
public record ToolChoice(Mode mode, String toolName) {

	public enum Mode {

		AUTO, NONE, REQUIRED, NAMED

	}

	public ToolChoice {
		if (mode == null) {
			mode = Mode.AUTO;
		}
		if (mode == Mode.NAMED && (toolName == null || toolName.isBlank())) {
			throw new IllegalArgumentException("toolName is required for NAMED tool choice");
		}
		if (mode != Mode.NAMED && toolName != null) {
			throw new IllegalArgumentException("Only NAMED tool choice may contain a toolName");
		}
	}

	public static ToolChoice auto() {
		return new ToolChoice(Mode.AUTO, null);
	}

	public static ToolChoice none() {
		return new ToolChoice(Mode.NONE, null);
	}

	public static ToolChoice required() {
		return new ToolChoice(Mode.REQUIRED, null);
	}

	public static ToolChoice named(String toolName) {
		return new ToolChoice(Mode.NAMED, toolName);
	}
}
