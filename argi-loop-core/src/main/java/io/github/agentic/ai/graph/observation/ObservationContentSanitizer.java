/*
 * Copyright 2024-2026 the original author or authors.
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

package io.github.agentic.ai.graph.observation;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes optional raw observation content before it is attached to high-cardinality
 * observation attributes.
 */
public final class ObservationContentSanitizer {

	public static final int DEFAULT_MAX_CONTENT_LENGTH = 1000;

	private static final String REDACTED = "<redacted>";

	private static final String SENSITIVE_FIELD_NAMES =
			"password|passwd|token|secret|api[-_]?key|access[-_]?key|authorization|credential";

	private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
			"(?i)(\"(?:" + SENSITIVE_FIELD_NAMES + ")\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");

	private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
			"(?i)(\\b(?:" + SENSITIVE_FIELD_NAMES + ")\\b\\s*[:=]\\s*)([^,;}&\\r\\n]+)");

	private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");

	private ObservationContentSanitizer() {
	}

	public static String sanitizeText(Object value, int maxContentLength) {
		if (value == null) {
			return "";
		}
		String sanitized = redactSensitiveAssignments(String.valueOf(value));
		sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer " + REDACTED);
		return truncate(sanitized, maxContentLength);
	}

	public static String sanitizeState(Map<String, Object> state, int maxContentLength) {
		if (state == null || state.isEmpty()) {
			return "empty state";
		}
		int maxStateLength = Math.max(0, maxContentLength) * 4;
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> entry : state.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.startsWith("_") || "logs".equals(key)) {
				continue;
			}
			String value = isSensitiveKey(key) ? REDACTED : sanitizeText(entry.getValue(), maxContentLength);
			appendStateEntry(sb, key, value, maxStateLength);
			if (sb.length() >= maxStateLength) {
				break;
			}
		}
		return sb.length() > 0 ? sb.toString() : "empty visible state";
	}

	static boolean isSensitiveKey(String key) {
		String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
		return normalized.contains("password") || normalized.contains("passwd") || normalized.contains("token")
				|| normalized.contains("secret") || normalized.contains("apikey") || normalized.contains("accesskey")
				|| normalized.contains("authorization") || normalized.contains("credential");
	}

	private static void appendStateEntry(StringBuilder sb, String key, String value, int maxStateLength) {
		if (maxStateLength == 0) {
			return;
		}
		String entry = key + "=" + value + "; ";
		int remaining = maxStateLength - sb.length();
		if (remaining <= 0) {
			return;
		}
		if (entry.length() <= remaining) {
			sb.append(entry);
		}
		else {
			sb.append(entry, 0, remaining);
		}
	}

	private static String redactSensitiveAssignments(String value) {
		String jsonRedacted = SENSITIVE_JSON_FIELD.matcher(value).replaceAll("$1\"" + REDACTED + "\"");
		Matcher matcher = SENSITIVE_ASSIGNMENT.matcher(jsonRedacted);
		StringBuffer buffer = new StringBuffer();
		while (matcher.find()) {
			matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + REDACTED));
		}
		matcher.appendTail(buffer);
		return buffer.toString();
	}

	private static String truncate(String value, int maxContentLength) {
		int limit = Math.max(0, maxContentLength);
		if (value.length() <= limit) {
			return value;
		}
		return value.substring(0, limit) + "... (truncated)";
	}

}
