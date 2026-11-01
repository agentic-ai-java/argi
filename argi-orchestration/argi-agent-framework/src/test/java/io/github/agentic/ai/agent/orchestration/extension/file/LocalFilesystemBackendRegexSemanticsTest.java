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
package io.github.agentic.ai.agent.orchestration.extension.file;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class LocalFilesystemBackendRegexSemanticsTest {

	@TempDir
	Path root;

	/** 验证启用 ripgrep 后，合法的 Java 前瞻正则与 Java 回退路径返回相同结果。 */
	@Test
	void ripgrepAndJavaFallbackShouldMatchTheSameLookAheadPattern() throws Exception {
		assumeTrue(isRipgrepAvailable(), "ripgrep is required to reproduce issue #230");
		Files.writeString(this.root.resolve("sample.txt"), "foobar\n", StandardCharsets.UTF_8);

		final String javaResult = probe("foo(?=bar)", "/sample.txt", null, emptyPath());
		final String ripgrepResult = probe("foo(?=bar)", "/sample.txt", null, null);

		assertEquals("matches=1", javaResult);
		assertEquals(javaResult, ripgrepResult,
				"Issue #230: enabling ripgrep must not change results for a valid Java regex");
	}

	/** 验证 ripgrep 的退出码 0 和 1 分别表示有匹配和无匹配。 */
	@Test
	void ripgrepShouldPreserveMatchAndNoMatchExitSemantics() throws Exception {
		assumeTrue(isRipgrepAvailable(), "ripgrep is required to verify its exit codes");
		Files.writeString(this.root.resolve("sample.txt"), "foobar\n", StandardCharsets.UTF_8);

		assertEquals("matches=1", probe("foo", "/sample.txt", null, null));
		assertEquals("matches=0", probe("missing", "/sample.txt", null, null));
	}

	/** 验证 ripgrep 执行失败时返回诊断，而不是伪装成无匹配。 */
	@Test
	void ripgrepExecutionFailureShouldReturnDiagnostic() throws Exception {
		Files.writeString(this.root.resolve("sample.txt"), "foobar\n", StandardCharsets.UTF_8);
		final Path bin = Files.createDirectories(this.root.resolve("failing-rg"));
		final Path executable = bin.resolve("rg");
		Files.writeString(executable, "#!/bin/sh\nprintf 'synthetic rg failure\\n' >&2\nexit 2\n",
				StandardCharsets.UTF_8);
		assertTrue(executable.toFile().setExecutable(true), "Could not make the fake rg executable");

		final String result = probe("foo", "/sample.txt", null, bin);

		assertTrue(result.startsWith("error="), result);
		assertTrue(result.contains("exit code 2"), result);
		assertTrue(result.contains("synthetic rg failure"), result);
	}

	/** 验证 PATH 中存在但没有执行权限的 rg 会返回启动错误。 */
	@Test
	void nonExecutableRipgrepShouldReturnStartError() throws Exception {
		Files.writeString(this.root.resolve("sample.txt"), "foobar\n", StandardCharsets.UTF_8);
		final Path bin = Files.createDirectories(this.root.resolve("non-executable-rg"));
		final Path executable = bin.resolve("rg");
		Files.writeString(executable, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
		assertTrue(executable.toFile().setExecutable(false), "Could not remove executable permission from fake rg");

		final String result = probe("foo", "/sample.txt", null, bin);

		assertTrue(result.startsWith("error=Unable to start ripgrep:"), result);
	}

	/** 验证无效 glob 返回诊断，不会被当作正常空结果。 */
	@Test
	void invalidGlobShouldReturnDiagnostic() throws Exception {
		Files.writeString(this.root.resolve("sample.txt"), "foobar\n", StandardCharsets.UTF_8);

		final String result = probe("foo", "/", "[", emptyPath());

		assertTrue(result.startsWith("error="), result);
	}

	private boolean isRipgrepAvailable() throws Exception {
		final Process process = new ProcessBuilder("rg", "--version")
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
		try {
			return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		finally {
			process.destroyForcibly();
		}
	}

	private Path emptyPath() throws Exception {
		return Files.createDirectories(this.root.resolve("empty-path"));
	}

	private String probe(String pattern, String searchPath, String glob, Path commandPath) throws Exception {
		final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		final String suffix = commandPath == null ? "ripgrep" : "custom-path";
		final Path result = this.root.resolve(suffix + ".result");
		final Path log = this.root.resolve(suffix + ".log");
		final ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
				LocalFilesystemBackendRegexSemanticsTest.class.getName(), this.root.toString(), result.toString(), pattern,
				searchPath, glob == null ? "<null>" : glob);
		if (commandPath != null) {
			builder.environment().put("PATH", commandPath.toString());
		}
		builder.redirectErrorStream(true).redirectOutput(log.toFile());
		final Process process = builder.start();
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe JVM timed out");
			final String output = Files.readString(log);
			assertEquals(0, process.exitValue(), output);
			return Files.readString(result).trim();
		}
		finally {
			process.destroyForcibly();
		}
	}

	public static void main(String[] args) throws Exception {
		final LocalFilesystemBackend backend = new LocalFilesystemBackend(args[0], true, 1);
		final String glob = "<null>".equals(args[4]) ? null : args[4];
		final Object result = backend.grepRaw(args[2], args[3], glob);
		final String output = result instanceof java.util.List<?> matches ? "matches=" + matches.size()
				: "error=" + result;
		Files.writeString(Path.of(args[1]), output, StandardCharsets.UTF_8);
	}

}
