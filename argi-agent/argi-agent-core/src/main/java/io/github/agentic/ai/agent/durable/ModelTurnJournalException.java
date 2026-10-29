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
package io.github.agentic.ai.agent.durable;

/**
 * A model turn could not be journaled, or its recorded data does not describe the request
 * being issued.
 *
 * <p>
 * This is not a model failure. An engine that converts caught model exceptions into
 * user-visible error messages must rethrow this type instead: continuing would let tool
 * dispatch proceed on a turn whose plan was never recorded.
 */
public class ModelTurnJournalException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public ModelTurnJournalException(String message) {
		super(message);
	}

	public ModelTurnJournalException(String message, Throwable cause) {
		super(message, cause);
	}

}
