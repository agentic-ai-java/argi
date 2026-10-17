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

package io.github.agentic.ai.graph.execution.definition;

import io.github.agentic.ai.graph.execution.run.RunSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.APP_V5;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.CODEC_V2;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.TOOLS_V3;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restart persistence for definitions: a second JVM rebuilds the run from the committed
 * record file alone. The in-memory tests above are not evidence of this property.
 */
class JvmRestartRecoveryTest {

	@TempDir
	Path tempDir;

	@Test
	void freshJvmResolvesTheRetainedVersionFromCommittedData() throws Exception {
		Path file = writeRecord("checkout", "1");

		ProcessResult result = runFixture(file);

		assertThat(result.exitCode).isEqualTo(0);
		assertThat(fields(result.output)).containsEntry("graph", "graph[checkout@1]")
			.containsEntry("toolCatalog", "tool_catalog[main@3]")
			.containsEntry("codec", "codec[argi-json@2]")
			.containsEntry("application", "application[shop@5]")
			.containsEntry("graphCode", "checkout-graph-v1")
			.containsEntry("recordHandles", "none")
			.containsEntry("loadedBy", "application");
		assertThat(fields(result.output).get("jvm")).isNotEqualTo(String.valueOf(ProcessHandle.current().pid()));
	}

	@Test
	void freshJvmFailsBeforeExecutionWhenTheRecordedVersionIsGone() throws Exception {
		Path file = writeRecord("checkout", "2");

		ProcessResult result = runFixture(file);

		assertThat(result.exitCode).isEqualTo(1);
		assertThat(fields(result.output)).containsEntry("error", "NOT_REGISTERED")
			.containsEntry("reference", "graph[checkout@2]");
	}

	@Test
	void freshJvmRejectsAnEnvelopeItCannotRead() throws Exception {
		RunDefinitionRecord record = DefinitionTestSupport.record();
		Path file = this.tempDir.resolve("unsupported-envelope.json");
		String json = new RunDefinitionRecordCodec().encode(record).replace("\"schemaVersion\":1",
				"\"schemaVersion\":7");
		Files.writeString(file, json, StandardCharsets.UTF_8);

		ProcessResult result = runFixture(file);

		assertThat(result.exitCode).isEqualTo(1);
		assertThat(fields(result.output)).containsEntry("error", "ENVELOPE_VERSION_UNSUPPORTED")
			.containsEntry("recorded", "7");
	}

	private Path writeRecord(String graphId, String graphVersion) throws IOException {
		RunSpec run = DefinitionTestSupport.run("run-1", graphId, graphVersion);
		RunDefinitionRecord record = RunDefinitionRecord.forRun(run, TOOLS_V3, CODEC_V2, APP_V5);
		Path file = this.tempDir.resolve("definition-record-" + graphVersion + ".json");
		Files.writeString(file, new RunDefinitionRecordCodec().encode(record), StandardCharsets.UTF_8);
		return file;
	}

	private ProcessResult runFixture(Path recordFile) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("surefire.test.class.path",
				System.getProperty("java.class.path"));
		ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
				DefinitionRecoveryProcessFixture.class.getName(), recordFile.toString());
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new ProcessResult(process.waitFor(), output);
	}

	private static Map<String, String> fields(String output) {
		String line = output.lines().filter((candidate) -> !candidate.isBlank()).reduce((first, second) -> second)
			.orElse("");
		Map<String, String> fields = new LinkedHashMap<>();
		for (String token : line.split(" ")) {
			int separator = token.indexOf('=');
			if (separator > 0) {
				fields.put(token.substring(0, separator), token.substring(separator + 1));
			}
		}
		return fields;
	}

	private record ProcessResult(int exitCode, String output) {
	}

}
