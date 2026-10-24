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
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实文件和两个独立 JVM，复现 #231：rg 搜索绕过单文件大小限制。 */
public class LocalFilesystemBackendSizeLimitReproductionTest {

	private static final String MARKER = "SIZE_LIMIT_MARKER";

	@TempDir
	Path root;

	/** 验证目录与显式文件输入在大小低于、等于、超过 1 MiB 时的行为。 */
	@ParameterizedTest(name = "explicitFile={0}")
	@ValueSource(booleans = { false, true })
	void oversizedFileMustBeSkippedWithAndWithoutRipgrep(boolean explicitFile) throws Exception {
		final String marker = "\n" + MARKER + "\n";
		final String padding = "x\n".repeat(524_289);
		final String[] names = { "below.txt", "at.txt", "above.txt" };
		for (int i = 0; i < names.length; i++) {
			final int size = 1024 * 1024 + i - 1;
			final Path file = this.root.resolve(names[i]);
			Files.writeString(file, padding.substring(0, size - marker.length()) + marker, StandardCharsets.UTF_8);
			assertEquals(size, Files.size(file));
		}
		final String fallback = probe(false, explicitFile);
		final String ripgrep = probe(true, explicitFile);
		assertTrue(ripgrep.contains("rg=true;"), "rg is required on the test JVM PATH: " + ripgrep);
		assertTrue(fallback.contains("rg=false;below.txt=1;at.txt=1;above.txt=0"), fallback);
		assertTrue(ripgrep.contains("rg=true;below.txt=1;at.txt=1;above.txt=0"),
				"Issue #231: both backends must enforce the same size limit.\nJava: " + fallback + "\nrg: " + ripgrep);
	}

	/** 在独立 JVM 中运行搜索，通过 PATH 控制是否使用 rg，并返回搜索结果。 */
	private String probe(boolean useRipgrep, boolean explicitFile) throws Exception {
		final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		final Path log = this.root.resolve(useRipgrep ? "rg.log" : "java.log");
		final ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
				LocalFilesystemBackendSizeLimitReproductionTest.class.getName(), this.root.toString(), Boolean.toString(explicitFile));
		if (!useRipgrep) {
			builder.environment().put("PATH", Files.createDirectory(this.root.resolve("empty-path")).toString());
		}
		builder.redirectErrorStream(true).redirectOutput(log.toFile());
		final Process process = builder.start();
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe JVM timed out");
			final String output = Files.readString(log);
			assertEquals(0, process.exitValue(), output);
			return output;
		}
		finally {
			process.destroyForcibly();
		}
	}

	/** 子 JVM 入口：创建文件后端，搜索大小文件并输出匹配数量。 */
	public static void main(String[] args) throws Exception {
		boolean hasRipgrep = false;
		try {
			final Process version = new ProcessBuilder("rg", "--version")
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			try {
				hasRipgrep = version.waitFor(5, TimeUnit.SECONDS) && version.exitValue() == 0;
			}
			finally {
				version.destroyForcibly();
			}
		}
		catch (java.io.IOException ignored) {
		}
		final LocalFilesystemBackend backend = new LocalFilesystemBackend(args[0], true, 1);
		final boolean explicitFile = Boolean.parseBoolean(args[1]);
		final StringBuilder result = new StringBuilder("rg=" + hasRipgrep);
		for (String name : List.of("below.txt", "at.txt", "above.txt")) {
			final Object matches = backend.grepRaw(MARKER, explicitFile ? "/" + name : "/", name);
			if (!(matches instanceof List<?> matchList)) {
				throw new IllegalStateException("Expected grepRaw to return a match list");
			}
			result.append(';').append(name).append('=').append(matchList.size());
		}
		System.out.println(result);
	}

}
