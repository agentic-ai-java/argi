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
package io.github.agentic.ai.graph.agent.interceptor.authorization;

/**
 * Authorization decision returned by a {@link ToolAuthorizationPolicy}.
 *
 * @param allowed whether the tool call is authorized
 * @param reason optional denial reason; typically present when allowed is false
 */
public record AuthorizationDecision(boolean allowed, String reason) {

	/**
	 * Allow the tool call.
	 * @return an allowed decision
	 */
	public static AuthorizationDecision allow() {
		return new AuthorizationDecision(true, null);
	}

	/**
	 * Deny the tool call with a reason.
	 * @param reason the reason for denial
	 * @return a denied decision
	 */
	public static AuthorizationDecision deny(String reason) {
		return new AuthorizationDecision(false, reason);
	}

	/**
	 * Whether this decision denies the tool call.
	 * @return true if denied
	 */
	public boolean isDenied() {
		return !allowed;
	}

}
