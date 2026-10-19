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
package io.github.agentic.ai.graph.agent.hook.contentsafety;

/** Metadata keys for structured content-safety decisions. Values never include matched text. */
public final class ContentSafetyKeys {

	public static final String STAGE = "argi.contentSafety.stage";

	public static final String ACTION = "argi.contentSafety.action";

	public static final String REASON = "argi.contentSafety.reason";

	public static final String DETECTOR = "argi.contentSafety.detector";

	/** Set when the decision is {@link ContentSafetyAction#REQUIRE_REVIEW}. */
	public static final String REVIEW = "argi.contentSafety.review";

	/** Tool response metadata marking a cached receipt that must use {@link ContentSafetyStage#RECEIPT}. */
	public static final String CACHED_RECEIPT = "argi.contentSafety.cachedReceipt";

	/** Message metadata marking child output that must use {@link ContentSafetyStage#CHILD_RESULT}. */
	public static final String CHILD_RESULT = "argi.contentSafety.childResult";

	public static final String REASON_MATCHED = "matched";

	public static final String REASON_TIMEOUT = "timeout";

	public static final String REASON_DETECTOR_FAILURE = "detector_failure";

	public static final String REASON_TRANSFORM_REJECTED = "transform_rejected";

	private ContentSafetyKeys() {
	}

}
