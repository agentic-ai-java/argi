/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.agent.orchestration.a2a;

/**
 * The remote A2A task terminated abnormally: it reported a {@code failed} or
 * {@code canceled} status-update. This is a definitive remote outcome, not a local
 * transport or parsing problem; the optional status message text reported by the remote
 * peer is carried in the exception message.
 */
public class A2aRemoteTaskException extends A2aException {

	/** The A2A task state reported by the remote peer, {@code failed} or {@code canceled}. */
	private final String taskState;

	/**
	 * Constructs a new remote task failure.
	 * @param taskState the A2A task state reported by the remote peer
	 * @param message the detail message, including any remote reason text
	 */
	public A2aRemoteTaskException(String taskState, String message) {
		super(message);
		this.taskState = taskState;
	}

	/**
	 * Return the A2A task state reported by the remote peer.
	 * @return the task state, {@code failed} or {@code canceled}
	 */
	public String taskState() {
		return this.taskState;
	}

}
