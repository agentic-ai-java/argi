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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证后端检查文件后、真实 rg 搜索前，文件增长越过大小上限的情况。 */
@EnabledOnOs({ OS.MAC, OS.LINUX })
public class LocalFilesystemBackendFileGrowthTest {

	private static final String MARKER = "SIZE_LIMIT_MARKER";

	@TempDir
	Path root;

	/** 验证显式搜索只返回目标文件，兼容隐藏文件、忽略规则和文件名中的特殊字符。 */
	@ParameterizedTest
	@ValueSource(strings = { "plain.txt", ".hidden[?]*{x}.txt", "!special.txt", "space name.txt", "back\\slash.txt" })
	void explicitSearchKeepsItsOriginalFileScope(String name) throws Exception {
		findRipgrep();
		Files.writeString(this.root.resolve(name), MARKER + "\n");
		Files.writeString(this.root.resolve("other.txt"), MARKER + "\n");
		Files.writeString(Files.createDirectory(this.root.resolve("nested")).resolve(name), MARKER + "\n");
		Files.writeString(this.root.resolve(".ignore"), "*\n");
		final LocalFilesystemBackend backend = new LocalFilesystemBackend(this.root.toString(), true, 1);
		final Object result = backend.grepRaw(MARKER, "/" + name, null);
		assertTrue(result instanceof List<?>, "Expected a match list");
		final List<?> matches = (List<?>) result;
		assertEquals(1, matches.size(), "Explicit search must not include sibling or nested files");
		assertTrue(matches.get(0) instanceof GrepMatch);
		assertEquals("/" + name.replace("\\", "/"), ((GrepMatch) matches.get(0)).getPath());
	}

	/** 用信号文件同步扩容与搜索，分别验证目录输入和显式文件输入。 */
	@ParameterizedTest(name = "explicitFile={0}")
	@ValueSource(booleans = { false, true })
	void skipsFileThatGrowsAfterBackendCheck(boolean explicitFile) throws Exception {
		final Path realRipgrep = findRipgrep();
		final Path workspace = Files.createDirectory(this.root.resolve("workspace"));
		final Path growingFile = Files.writeString(workspace.resolve("growing.txt"), "initial content\n");
		assertTrue(Files.size(growingFile) < 1024L * 1024L);
		final Path ready = this.root.resolve("rg-ready");
		final Path release = this.root.resolve("rg-release");
		final Path result = this.root.resolve("result.txt");
		final Path log = this.root.resolve("probe.log");
		final Path bin = createRipgrepGate();
		final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		final ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
				LocalFilesystemBackendFileGrowthTest.class.getName(), workspace.toString(),
				Boolean.toString(explicitFile), result.toString());
		builder.environment().put("PATH", bin.toString());
		builder.environment().put("ARGI_GROWTH_READY", ready.toString());
		builder.environment().put("ARGI_GROWTH_RELEASE", release.toString());
		builder.environment().put("ARGI_GROWTH_REAL_RG", realRipgrep.toString());
		builder.environment().remove("RIPGREP_CONFIG_PATH");
		builder.redirectErrorStream(true).redirectOutput(log.toFile());
		final Process process = builder.start();
		try {
			awaitRipgrepGate(ready, process, log);
			Files.writeString(growingFile, "padding\n".repeat(150_000) + MARKER + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.APPEND);
			assertTrue(Files.size(growingFile) > 1024L * 1024L);
			Files.writeString(release, "continue");
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Probe JVM did not finish after releasing rg");
			assertEquals(0, process.exitValue(), Files.readString(log));
			assertTrue(Files.isRegularFile(result), "Probe did not produce a search result");
			assertEquals("matches=0", Files.readString(result),
					"The file grew beyond 1 MiB after the backend check; explicitFile=" + explicitFile);
		}
		finally {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Probe JVM did not stop");
		}
	}

	/** 定位 PATH 中真实可执行的 rg，缺失时明确指出测试环境不满足要求。 */
	private static Path findRipgrep() throws Exception {
		final String path = System.getenv().getOrDefault("PATH", "");
		for (String directory : path.split(File.pathSeparator)) {
			final Path candidate = Path.of(directory, "rg");
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return candidate.toRealPath();
			}
		}
		throw new IllegalStateException("Install ripgrep and add its directory to the test JVM PATH");
	}

	/** 创建测试专用 rg 包装脚本，收到放行信号后以原参数执行真实 rg。 */
	private Path createRipgrepGate() throws Exception {
		final Path bin = Files.createDirectory(this.root.resolve("bin"));
		final Path wrapper = Files.writeString(bin.resolve("rg"), """
				#!/bin/sh
				set -eu
				: > "$ARGI_GROWTH_READY"
				attempt=0
				while [ ! -f "$ARGI_GROWTH_RELEASE" ]; do
				    if [ "$attempt" -ge 2000 ]; then
				        exit 70
				    fi
				    /bin/sleep 0.01
				    attempt=$((attempt + 1))
				done
				exec "$ARGI_GROWTH_REAL_RG" "$@"
				""", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
		return bin;
	}

	/** 等待 rg 包装脚本发出就绪信号，不通过固定延时猜测文件检查时序。 */
	private static void awaitRipgrepGate(Path ready, Process process, Path log) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!Files.exists(ready)) {
			assertTrue(process.isAlive(), "Probe exited before reaching rg: " + Files.readString(log));
			assertTrue(System.nanoTime() < deadline, "Timed out waiting for rg gate");
			Thread.sleep(10);
		}
	}

	/** 子 JVM 入口：以 1 MiB 上限调用真实后端，并将搜索匹配数量写入结果文件。 */
	public static void main(String[] args) throws Exception {
		final LocalFilesystemBackend backend = new LocalFilesystemBackend(args[0], true, 1);
		final boolean explicitFile = Boolean.parseBoolean(args[1]);
		final Object result = backend.grepRaw(MARKER, explicitFile ? "/growing.txt" : "/", "growing.txt");
		if (!(result instanceof List<?> matches)) {
			throw new IllegalStateException("Expected grepRaw to return a match list");
		}
		Files.writeString(Path.of(args[2]), "matches=" + matches.size(), StandardCharsets.UTF_8);
	}

}
