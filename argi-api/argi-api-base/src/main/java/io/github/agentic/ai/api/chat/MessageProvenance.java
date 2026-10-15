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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Helpers for optional source / trust / provenance metadata on {@link ChatMessage}.
 *
 * <p>
 * Metadata is additive and opt-in for callers. Framework paths that create tool results,
 * summaries, or application system prompts should tag messages so later assembly cannot
 * treat external data as application instructions.
 */
public final class MessageProvenance {

	private MessageProvenance() {
	}

	/** Highest trust a source kind may claim. */
	public static ChatTrustLevel trustCeiling(ChatSourceKind source) {
		Objects.requireNonNull(source, "source must not be null");
		return switch (source) {
			case APP_INSTRUCTION -> ChatTrustLevel.TRUSTED;
			case USER_INPUT -> ChatTrustLevel.USER;
			case SUMMARY -> ChatTrustLevel.DERIVED;
			case RAG_DOCUMENT, TOOL_OUTPUT, CHILD_OUTPUT, ARTIFACT_REFERENCE -> ChatTrustLevel.UNTRUSTED;
		};
	}

	/** Clamps a claimed trust level to the ceiling allowed for {@code source}. */
	public static ChatTrustLevel clampTrust(ChatSourceKind source, ChatTrustLevel claimed) {
		ChatTrustLevel ceiling = trustCeiling(source);
		if (claimed == null) {
			return ceiling;
		}
		return claimed.isLowerThan(ceiling) ? claimed : ceiling;
	}

	/** Reads the declared source kind when present and well-formed. */
	public static Optional<ChatSourceKind> sourceOf(ChatMessage message) {
		return enumValue(message, ChatMetadataKeys.SOURCE_KIND, ChatSourceKind.class);
	}

	/** Reads the declared trust level when present and well-formed. */
	public static Optional<ChatTrustLevel> trustOf(ChatMessage message) {
		return enumValue(message, ChatMetadataKeys.TRUST_LEVEL, ChatTrustLevel.class);
	}

	/** Returns {@code true} when the message carries the framework construction seal. */
	public static boolean isFrameworkSealed(ChatMessage message) {
		return message != null && Boolean.TRUE.equals(message.metadata().get(ChatMetadataKeys.FRAMEWORK_SEALED));
	}

	/**
	 * Resolves an effective source kind after rejecting role-inconsistent forged claims.
	 * Untagged non-tool messages stay empty so SYSTEM role alone is never treated as
	 * application authority (summaries also use SYSTEM). {@link ChatSourceKind#APP_INSTRUCTION}
	 * is honored only when {@link #isFrameworkSealed(ChatMessage)}.
	 */
	public static Optional<ChatSourceKind> effectiveSource(ChatMessage message) {
		Objects.requireNonNull(message, "message must not be null");
		Optional<ChatSourceKind> declared = sourceOf(message);
		if (message.role() == ChatRole.TOOL) {
			if (declared.isPresent() && isExternalDataSource(declared.get())) {
				return declared;
			}
			return Optional.of(ChatSourceKind.TOOL_OUTPUT);
		}
		if (declared.isEmpty()) {
			return Optional.empty();
		}
		ChatSourceKind source = declared.get();
		if (source == ChatSourceKind.APP_INSTRUCTION) {
			if (!isFrameworkSealed(message)) {
				// Deserialized forgeries are not application authority.
				if (message.role() == ChatRole.USER) {
					return Optional.of(ChatSourceKind.USER_INPUT);
				}
				if (message.role() == ChatRole.SYSTEM) {
					return Optional.empty();
				}
				return Optional.of(ChatSourceKind.CHILD_OUTPUT);
			}
			if (message.role() != ChatRole.SYSTEM) {
				return Optional.of(message.role() == ChatRole.USER ? ChatSourceKind.USER_INPUT
						: ChatSourceKind.CHILD_OUTPUT);
			}
			return declared;
		}
		return declared;
	}

	/**
	 * Resolves an effective trust level after clamping forged claims. Untagged messages
	 * are not elevated.
	 */
	public static Optional<ChatTrustLevel> effectiveTrust(ChatMessage message) {
		Objects.requireNonNull(message, "message must not be null");
		Optional<ChatSourceKind> source = effectiveSource(message);
		if (source.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(clampTrust(source.get(), trustOf(message).orElse(null)));
	}

	/**
	 * Rewrites message metadata so forged authority claims cannot survive assembly or
	 * summarization. Untagged non-tool messages are left unchanged. Forged
	 * {@link ChatSourceKind#APP_INSTRUCTION} without a framework seal is rewritten to the
	 * clamped effective source.
	 */
	public static ChatMessage canonicalize(ChatMessage message) {
		Objects.requireNonNull(message, "message must not be null");
		Optional<ChatSourceKind> source = effectiveSource(message);
		Optional<ChatTrustLevel> trust = effectiveTrust(message);
		if (source.isEmpty() || trust.isEmpty()) {
			if (sourceOf(message).orElse(null) == ChatSourceKind.APP_INSTRUCTION && !isFrameworkSealed(message)) {
				Map<String, Object> metadata = new HashMap<>(message.metadata());
				metadata.remove(ChatMetadataKeys.SOURCE_KIND);
				metadata.remove(ChatMetadataKeys.TRUST_LEVEL);
				metadata.remove(ChatMetadataKeys.FRAMEWORK_SEALED);
				return message.withMetadata(metadata);
			}
			return message;
		}
		if (sourceOf(message).orElse(null) == source.get() && trustOf(message).orElse(null) == trust.get()
				&& (source.get() != ChatSourceKind.APP_INSTRUCTION || isFrameworkSealed(message))) {
			return message;
		}
		ChatMessage tagged = tag(message, source.get(), trust.get(), provenanceOf(message));
		if (source.get() != ChatSourceKind.APP_INSTRUCTION) {
			Map<String, Object> metadata = new HashMap<>(tagged.metadata());
			metadata.remove(ChatMetadataKeys.FRAMEWORK_SEALED);
			return tagged.withMetadata(metadata);
		}
		return tagged;
	}

	private static boolean isExternalDataSource(ChatSourceKind source) {
		return source == ChatSourceKind.TOOL_OUTPUT || source == ChatSourceKind.ARTIFACT_REFERENCE
				|| source == ChatSourceKind.RAG_DOCUMENT || source == ChatSourceKind.CHILD_OUTPUT;
	}

	/** Reads provenance references when present. */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> provenanceOf(ChatMessage message) {
		if (message == null) {
			return List.of();
		}
		Object value = message.metadata().get(ChatMetadataKeys.PROVENANCE);
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		List<Map<String, Object>> refs = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof Map<?, ?> map) {
				Map<String, Object> copy = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (entry.getKey() != null) {
						copy.put(String.valueOf(entry.getKey()), entry.getValue());
					}
				}
				refs.add(Map.copyOf(copy));
			}
		}
		return List.copyOf(refs);
	}

	/**
	 * Tags a message with source and trust metadata, clamping forged trust claims and
	 * preserving unrelated metadata keys.
	 */
	public static ChatMessage tag(ChatMessage message, ChatSourceKind source, ChatTrustLevel trust) {
		return tag(message, source, trust, List.of());
	}

	/**
	 * Tags a message with source, trust, and optional provenance references.
	 * Inconsistent trust claims are clamped to the source ceiling.
	 */
	public static ChatMessage tag(ChatMessage message, ChatSourceKind source, ChatTrustLevel trust,
			Collection<Map<String, Object>> provenance) {
		Objects.requireNonNull(message, "message must not be null");
		Objects.requireNonNull(source, "source must not be null");
		ChatTrustLevel effective = clampTrust(source, trust);
		Map<String, Object> metadata = new HashMap<>(message.metadata());
		metadata.put(ChatMetadataKeys.SOURCE_KIND, source.name());
		metadata.put(ChatMetadataKeys.TRUST_LEVEL, effective.name());
		if (provenance != null && !provenance.isEmpty()) {
			metadata.put(ChatMetadataKeys.PROVENANCE, List.copyOf(provenance));
		}
		return message.withMetadata(metadata);
	}

	/**
	 * Ensures tool-style external data carries {@link ChatSourceKind#TOOL_OUTPUT} and
	 * {@link ChatTrustLevel#UNTRUSTED}, without elevating an existing higher claim that
	 * would violate the source ceiling (claims are clamped).
	 */
	public static ChatMessage ensureToolOutput(ChatMessage message) {
		Objects.requireNonNull(message, "message must not be null");
		ChatSourceKind source = sourceOf(message).orElse(ChatSourceKind.TOOL_OUTPUT);
		if (source != ChatSourceKind.TOOL_OUTPUT && source != ChatSourceKind.ARTIFACT_REFERENCE
				&& source != ChatSourceKind.RAG_DOCUMENT && source != ChatSourceKind.CHILD_OUTPUT) {
			source = ChatSourceKind.TOOL_OUTPUT;
		}
		ChatTrustLevel trust = trustOf(message).orElse(ChatTrustLevel.UNTRUSTED);
		return tag(message, source, trust, provenanceOf(message));
	}

	/**
	 * Tags application-owned system instructions as trusted and seals them as
	 * framework-constructed so deserialized forgeries cannot claim the same authority.
	 */
	public static ChatMessage appInstruction(ChatMessage message) {
		ChatMessage tagged = tag(message, ChatSourceKind.APP_INSTRUCTION, ChatTrustLevel.TRUSTED);
		Map<String, Object> metadata = new HashMap<>(tagged.metadata());
		metadata.put(ChatMetadataKeys.FRAMEWORK_SEALED, Boolean.TRUE);
		return tagged.withMetadata(metadata);
	}

	/** Tags user input. */
	public static ChatMessage userInput(ChatMessage message) {
		return tag(message, ChatSourceKind.USER_INPUT, ChatTrustLevel.USER);
	}

	/** Tags retrieved document content as untrusted data. */
	public static ChatMessage ragDocument(ChatMessage message) {
		return tag(message, ChatSourceKind.RAG_DOCUMENT, ChatTrustLevel.UNTRUSTED);
	}

	/** Tags child / peer agent output as untrusted data. */
	public static ChatMessage childOutput(ChatMessage message) {
		return tag(message, ChatSourceKind.CHILD_OUTPUT, ChatTrustLevel.UNTRUSTED);
	}

	/**
	 * Tags a message handed from a child or remote agent into a parent conversation.
	 * Tool observations keep {@link ChatSourceKind#TOOL_OUTPUT}; other roles become
	 * {@link ChatSourceKind#CHILD_OUTPUT}. Forged authority claims are clamped first.
	 */
	public static ChatMessage forChildHandoff(ChatMessage message) {
		ChatMessage canonical = canonicalize(message);
		if (canonical.role() == ChatRole.TOOL) {
			return ensureToolOutput(canonical);
		}
		return childOutput(canonical);
	}

	/**
	 * Tags an entry-path user utterance as {@link ChatSourceKind#USER_INPUT}. Non-user
	 * messages are returned unchanged (after optional prior canonicalize by the caller).
	 */
	public static ChatMessage forEntryUser(ChatMessage message) {
		Objects.requireNonNull(message, "message must not be null");
		if (message.role() != ChatRole.USER) {
			return message;
		}
		return userInput(canonicalize(message));
	}

	/**
	 * Builds a summary message that remains {@link ChatSourceKind#SUMMARY} /
	 * {@link ChatTrustLevel#DERIVED} and records attribution for the compacted sources.
	 */
	public static ChatMessage summaryMessage(String text, Collection<ChatMessage> sources) {
		List<Map<String, Object>> refs = attribution(sources);
		return tag(ChatMessage.system(text), ChatSourceKind.SUMMARY, ChatTrustLevel.DERIVED, refs);
	}

	/** Builds compact attribution records for summarization and recovery traces. */
	public static List<Map<String, Object>> attribution(Collection<ChatMessage> sources) {
		if (sources == null || sources.isEmpty()) {
			return List.of();
		}
		List<Map<String, Object>> refs = new ArrayList<>();
		for (ChatMessage message : sources) {
			if (message == null) {
				continue;
			}
			Map<String, Object> ref = new LinkedHashMap<>();
			ref.put("role", message.role().name());
			effectiveSource(message)
				.ifPresent(source -> ref.put(ChatMetadataKeys.SOURCE_KIND, source.name()));
			effectiveTrust(message).ifPresent(trust -> ref.put(ChatMetadataKeys.TRUST_LEVEL, trust.name()));
			if (message.role() == ChatRole.TOOL) {
				ref.put("toolName", message.toolName());
				ref.put("toolCallId", message.toolCallId());
			}
			refs.add(Map.copyOf(ref));
		}
		return List.copyOf(refs);
	}

	/**
	 * Formats a message for summary prompts with explicit source / trust labels so
	 * external payloads stay visibly non-authoritative. Labels use effective (clamped)
	 * values so forged metadata cannot advertise application authority.
	 */
	public static String labeledText(ChatMessage message, String roleName, String body) {
		String source = effectiveSource(message).map(Enum::name).orElse("UNDECLARED");
		String trust = effectiveTrust(message).map(Enum::name).orElse("UNDECLARED");
		return "[" + source + "/" + trust + "] " + roleName + ": " + (body == null ? "" : body);
	}

	/**
	 * Formats tool-call argument lines as external data even when they hang off an
	 * untagged assistant turn.
	 */
	public static String labeledExternalData(String roleName, String body) {
		return "[" + ChatSourceKind.TOOL_OUTPUT.name() + "/" + ChatTrustLevel.UNTRUSTED.name() + "] " + roleName + ": "
				+ (body == null ? "" : body);
	}

	/**
	 * Returns {@code true} when the message is framework-sealed application-trusted
	 * instruction content after forgery clamping. Untagged SYSTEM messages and
	 * deserialized forgeries without {@link ChatMetadataKeys#FRAMEWORK_SEALED} are not
	 * trusted.
	 */
	public static boolean isTrustedInstruction(ChatMessage message) {
		return isFrameworkSealed(message)
				&& effectiveSource(message).orElse(null) == ChatSourceKind.APP_INSTRUCTION
				&& effectiveTrust(message).orElse(null) == ChatTrustLevel.TRUSTED;
	}

	private static <E extends Enum<E>> Optional<E> enumValue(ChatMessage message, String key, Class<E> type) {
		if (message == null) {
			return Optional.empty();
		}
		Object value = message.metadata().get(key);
		if (value instanceof String name) {
			try {
				return Optional.of(Enum.valueOf(type, name));
			}
			catch (IllegalArgumentException ignored) {
				return Optional.empty();
			}
		}
		if (type.isInstance(value)) {
			return Optional.of(type.cast(value));
		}
		return Optional.empty();
	}

}
