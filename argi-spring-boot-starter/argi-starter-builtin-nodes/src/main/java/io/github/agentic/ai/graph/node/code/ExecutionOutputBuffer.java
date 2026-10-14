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
package io.github.agentic.ai.graph.node.code;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class ExecutionOutputBuffer {

	static final String TRUNCATION_MARKER = "\n... output truncated ...";

	private final long maxBytes;

	private long capturedBytes;

	private boolean truncated;

	private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

	ExecutionOutputBuffer(long maxBytes) {
		if (maxBytes < 1) {
			throw new IllegalArgumentException("maxOutputBytes must be greater than zero");
		}
		this.maxBytes = maxBytes;
	}

	OutputStream newStream() {
		return new LimitedStream();
	}

	private synchronized void capture(byte[] bytes, int offset, int length) {
		long remaining = maxBytes - capturedBytes;
		if (remaining <= 0) {
			truncated = true;
			return;
		}
		int accepted = (int) Math.min(remaining, length);
		captured.write(bytes, offset, accepted);
		capturedBytes += accepted;
		if (accepted < length) {
			truncated = true;
		}
	}

	synchronized String text() {
		String value = captured.toString(StandardCharsets.UTF_8);
		return truncated ? value + TRUNCATION_MARKER : value;
	}

	private final class LimitedStream extends OutputStream {

		@Override
		public void write(int value) {
			capture(new byte[] { (byte) value }, 0, 1);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			capture(bytes, offset, length);
		}

		@Override
		public String toString() {
			return text();
		}

	}

}
