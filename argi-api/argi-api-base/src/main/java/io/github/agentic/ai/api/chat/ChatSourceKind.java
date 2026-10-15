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
 * Optional context source classification for message provenance.
 *
 * <p>
 * Values describe where content came from. They do not claim the model will ignore
 * injected text; they keep assembly and summarization from elevating external data into
 * application authority.
 */
public enum ChatSourceKind {

	/** Application-owned instructions such as a configured system prompt. */
	APP_INSTRUCTION,

	/** End-user utterance. */
	USER_INPUT,

	/** Retrieved document content (RAG or similar). */
	RAG_DOCUMENT,

	/** Tool or MCP observation payload. */
	TOOL_OUTPUT,

	/** Output handed off from a child or peer agent. */
	CHILD_OUTPUT,

	/** Compacted or summarized history derived from other messages. */
	SUMMARY,

	/** Opaque reference to an artifact rather than inline trusted text. */
	ARTIFACT_REFERENCE;

}
