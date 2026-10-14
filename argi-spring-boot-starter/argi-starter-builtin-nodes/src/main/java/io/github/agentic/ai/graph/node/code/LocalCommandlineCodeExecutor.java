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

import io.github.agentic.ai.graph.node.code.entity.CodeBlock;
import io.github.agentic.ai.graph.node.code.entity.CodeExecutionConfig;
import io.github.agentic.ai.graph.node.code.entity.CodeExecutionResult;
import io.github.agentic.ai.graph.utils.CodeUtils;
import io.github.agentic.ai.graph.utils.FileUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author HeYQ
 * @since 2024-12-02 17:23
 */
public class LocalCommandlineCodeExecutor implements CodeExecutor {

	private static final Logger logger = LoggerFactory.getLogger(LocalCommandlineCodeExecutor.class);

	@Override
	public CodeExecutionResult executeCodeBlocks(List<CodeBlock> codeBlockList, CodeExecutionConfig codeExecutionConfig)
			throws Exception {
		ExecutionOutputBuffer outputBuffer = new ExecutionOutputBuffer(codeExecutionConfig.getMaxOutputBytes());
		Path executionWorkDir = FileUtils.createExecutionDirectory(codeExecutionConfig.getWorkDir());
		try {
			CodeExecutionResult result;
			for (int i = 0; i < codeBlockList.size(); i++) {
				CodeBlock codeBlock = codeBlockList.get(i);
				String language = codeBlock.language();
				String code = codeBlock.code();
				logger.info("\n>>>>>>>> EXECUTING CODE BLOCK {} (inferred language is {})...", i + 1, language);
				result = executeCode(language, code, codeExecutionConfig, outputBuffer, executionWorkDir);
				if (result.exitCode() != 0) {
					return result;
				}
			}
			return new CodeExecutionResult(0, outputBuffer.text().trim());
		}
		finally {
			FileUtils.deleteRecursively(executionWorkDir);
		}
	}

	@Override
	public void restart() {

		logger.warn("Restarting local command line code executor is not supported. No action is taken.");
	}

	public CodeExecutionResult executeCode(String language, String code, CodeExecutionConfig config) throws Exception {
		Path executionWorkDir = FileUtils.createExecutionDirectory(config.getWorkDir());
		try {
			return executeCode(language, code, config, new ExecutionOutputBuffer(config.getMaxOutputBytes()),
					executionWorkDir);
		}
		finally {
			FileUtils.deleteRecursively(executionWorkDir);
		}
	}

	private CodeExecutionResult executeCode(String language, String code, CodeExecutionConfig config,
			ExecutionOutputBuffer outputBuffer, Path executionWorkDir) throws Exception {
		if (Objects.isNull(language) || Objects.isNull(code)) {
			throw new Exception("Either language or code must be provided.");
		}
		logger.warn(
				"Local command line code executor runs code directly on the host and must only be used with trusted input.");
		String workDir = executionWorkDir.toString();
		String codeHash = DigestUtils.md5Hex(code);
		String fileExt = CodeUtils.getFileExtForLanguage(language);
		String filename = String.format("tmp_code_%s.%s", codeHash, fileExt);

		FileUtils.writeCodeToFile(workDir, filename, code);
		if ("java".equals(language)) {
			FileUtils.copyResourceJarToWorkDir(workDir);
		}
		return executeCodeLocally(language, workDir, filename, config, outputBuffer);
	}

	private CodeExecutionResult executeCodeLocally(String language, String workDir, String filename,
			CodeExecutionConfig config, ExecutionOutputBuffer outputBuffer) throws Exception {
		// Set up command line based on language
		String executable = CodeUtils.getExecutableForLanguage(language);
		CommandLine commandLine = new CommandLine(executable);

		if ("java".equals(language)) {
			commandLine.addArgument("-cp");
			StringBuilder classPathBuilder = new StringBuilder();
			classPathBuilder.append(".").append(File.pathSeparator).append(workDir);

			// Add all JAR files in workDir to classpath
			try {
				Path workDirPath = Path.of(workDir);
				if (Files.exists(workDirPath)) {
					try (var stream = Files.walk(workDirPath)) {
						stream.filter(path -> path.toString().endsWith(".jar")).forEach(jarPath -> {
							classPathBuilder.append(File.pathSeparator).append(jarPath.toString());
						});
					}
				}
			}
			catch (IOException e) {
				logger.warn("Failed to scan JAR files in work directory", e);
			}

			if (config.getClassPath() != null && !config.getClassPath().isEmpty()) {
				classPathBuilder.append(File.pathSeparator).append(config.getClassPath());
			}

			String classPath = classPathBuilder.toString();
			commandLine.addArgument(classPath).addArgument(filename);
		}
		else {
			commandLine.addArgument(filename);
		}

		// Configure executor
		DefaultExecutor executor = new DefaultExecutor();
		executor.setWorkingDirectory(new File(workDir));
		executor.setExitValue(0);

		// Set up stream handling
		var outputStream = outputBuffer.newStream();
		var errorStream = outputBuffer.newStream();
		executor.setStreamHandler(new PumpStreamHandler(outputStream, errorStream));

		// Set timeout
		executor.setWatchdog(new ExecuteWatchdog(TimeUnit.SECONDS.toMillis(config.getTimeout())));

		try {
			executor.execute(commandLine);
			return new CodeExecutionResult(0, outputBuffer.text().trim());
		}
		catch (ExecuteException e) {
			String errorOutput = outputBuffer.text()
				.replace(Path.of(workDir).toAbsolutePath() + File.separator, "")
				.trim();
			return new CodeExecutionResult(e.getExitValue(), errorOutput);
		}
		catch (IOException e) {
			throw new Exception("Failed to execute code", e);
		}
		finally {
			// Cleanup Java class files
			if ("java".equals(language)) {
				FileUtils.deleteFile(workDir, filename.replace(".java", ".class"));
			}
		}
	}

}
