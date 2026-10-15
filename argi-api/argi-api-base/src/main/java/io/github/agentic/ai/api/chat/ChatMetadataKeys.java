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

/** Standard message-level metadata shared by all ChatClient adapters. */
public final class ChatMetadataKeys {

	/** Reasoning text for an assistant turn; never included in visible response text. */
	public static final String REASONING = "reasoning";

	/**
	 * Optional {@link ChatSourceKind} name describing where the message content came
	 * from.
	 */
	public static final String SOURCE_KIND = "argi.sourceKind";

	/**
	 * Optional {@link ChatTrustLevel} name describing the authority of the message
	 * content.
	 */
	public static final String TRUST_LEVEL = "argi.trustLevel";

	/**
	 * Optional list of attribution maps retained across summarization, compaction, and
	 * recovery.
	 */
	public static final String PROVENANCE = "argi.provenance";

	/**
	 * Framework construction seal for trusted application instructions. Only
	 * {@link MessageProvenance#appInstruction(ChatMessage)} sets this marker; forged
	 * {@code APP_INSTRUCTION} tags deserialized from state without the seal are not
	 * honored.
	 */
	public static final String FRAMEWORK_SEALED = "argi.frameworkSealed";

	private ChatMetadataKeys() {
	}

}
