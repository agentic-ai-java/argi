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
package io.github.agentic.ai.agent.retry;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Classifies model call failures from the failure type and its cause chain.
 *
 * <p>
 * The classifier never inspects the text of an exception message. A provider that
 * reports its failures through its own exception types can supply a dedicated
 * {@link ModelCallFailureClassifier} instead; this implementation covers the failures
 * that are recognisable without provider specific types.
 */
public final class TypeBasedModelCallFailureClassifier implements ModelCallFailureClassifier {

	/** Guards against a self referencing cause chain. */
	private static final int MAX_CAUSE_DEPTH = 32;

	@Override
	public ModelCallFailure classify(Throwable failure) {
		if (failure == null) {
			return new ModelCallFailure(ModelCallFailureCategory.UNKNOWN, null);
		}
		if (matches(failure, TypeBasedModelCallFailureClassifier::isCancellation)) {
			return new ModelCallFailure(ModelCallFailureCategory.CANCELLED, failure);
		}
		if (matches(failure, TypeBasedModelCallFailureClassifier::isTransient)) {
			return new ModelCallFailure(ModelCallFailureCategory.TRANSIENT, failure);
		}
		return new ModelCallFailure(ModelCallFailureCategory.UNKNOWN, failure);
	}

	private boolean matches(Throwable failure, Predicate<Throwable> predicate) {
		Throwable cause = failure;
		for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (predicate.test(cause)) {
				return true;
			}
			Throwable next = cause.getCause();
			if (next == cause) {
				break;
			}
			cause = next;
		}
		return false;
	}

	private static boolean isCancellation(Throwable cause) {
		return cause instanceof CancellationException || cause instanceof InterruptedException;
	}

	private static boolean isTransient(Throwable cause) {
		return cause instanceof SocketTimeoutException || cause instanceof TimeoutException || cause instanceof IOException;
	}

}
