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

package io.github.agentic.ai.graph.node.code.entity;

/**
 * @author HeYQ
 */
public class CodeExecutionConfig {

	private String workDir = "workspace";

	/**
	 * the docker image to use for code execution.
	 */
	private String docker;

	private String containerName = "argi-container";

	private String dockerHost = "unix:///var/run/docker.sock";

	private int timeout = 600;

	private int lastMessagesNumber = 1;

	private String classPath;

	private int maxConnections = 100;

	private int connectionTimeout = 30;

	private int responseTimeout = 50;

	private long maxOutputBytes = 1024L * 1024L;

	private boolean disableNetwork = true;

	private boolean readOnlyRootFilesystem = true;

	private long memoryLimitBytes = 256L * 1024L * 1024L;

	private long memorySwapBytes = 256L * 1024L * 1024L;

	private long cpuPeriodMicros = 100_000L;

	private long cpuQuotaMicros = 100_000L;

	private long pidsLimit = 128L;

	private boolean dropAllCapabilities = true;

	private boolean noNewPrivileges = true;

	/**
	 * Optional container user. Leave empty for compatibility with bind-mounted work
	 * directories that may not be readable by an arbitrary non-root uid.
	 */
	private String containerUser;

	public String getWorkDir() {
		return workDir;
	}

	public CodeExecutionConfig setWorkDir(String workDir) {
		this.workDir = workDir;
		return this;
	}

	public String getDocker() {
		return docker;
	}

	public CodeExecutionConfig setDocker(String docker) {
		this.docker = docker;
		return this;
	}

	public int getTimeout() {
		return timeout;
	}

	public CodeExecutionConfig setTimeout(int timeout) {
		this.timeout = timeout;
		return this;
	}

	public int getLastMessagesNumber() {
		return lastMessagesNumber;
	}

	public CodeExecutionConfig setLastMessagesNumber(int lastMessagesNumber) {
		this.lastMessagesNumber = lastMessagesNumber;
		return this;
	}

	public String getContainerName() {
		return containerName;
	}

	public CodeExecutionConfig setContainerName(String containerName) {
		this.containerName = containerName;
		return this;
	}

	public String getDockerHost() {
		return dockerHost;
	}

	public CodeExecutionConfig setDockerHost(String dockerHost) {
		this.dockerHost = dockerHost;
		return this;
	}

	public String getClassPath() {
		return classPath;
	}

	public CodeExecutionConfig setClassPath(String classPath) {
		this.classPath = classPath;
		return this;

	}

	public int getMaxConnections() {
		return maxConnections;
	}

	public void setMaxConnections(final int maxConnections) {
		this.maxConnections = maxConnections;
	}

	public int getConnectionTimeout() {
		return connectionTimeout;
	}

	public void setConnectionTimeout(final int connectionTimeout) {
		this.connectionTimeout = connectionTimeout;
	}

	public int getResponseTimeout() {
		return responseTimeout;
	}

	public void setResponseTimeout(final int responseTimeout) {
		this.responseTimeout = responseTimeout;
	}

	public long getMaxOutputBytes() {
		return maxOutputBytes;
	}

	public CodeExecutionConfig setMaxOutputBytes(long maxOutputBytes) {
		if (maxOutputBytes < 1) {
			throw new IllegalArgumentException("maxOutputBytes must be greater than zero");
		}
		this.maxOutputBytes = maxOutputBytes;
		return this;
	}

	public boolean isDisableNetwork() {
		return disableNetwork;
	}

	public CodeExecutionConfig setDisableNetwork(boolean disableNetwork) {
		this.disableNetwork = disableNetwork;
		return this;
	}

	public boolean isReadOnlyRootFilesystem() {
		return readOnlyRootFilesystem;
	}

	public CodeExecutionConfig setReadOnlyRootFilesystem(boolean readOnlyRootFilesystem) {
		this.readOnlyRootFilesystem = readOnlyRootFilesystem;
		return this;
	}

	public long getMemoryLimitBytes() {
		return memoryLimitBytes;
	}

	public CodeExecutionConfig setMemoryLimitBytes(long memoryLimitBytes) {
		this.memoryLimitBytes = memoryLimitBytes;
		return this;
	}

	public long getMemorySwapBytes() {
		return memorySwapBytes;
	}

	public CodeExecutionConfig setMemorySwapBytes(long memorySwapBytes) {
		this.memorySwapBytes = memorySwapBytes;
		return this;
	}

	public long getCpuPeriodMicros() {
		return cpuPeriodMicros;
	}

	public CodeExecutionConfig setCpuPeriodMicros(long cpuPeriodMicros) {
		this.cpuPeriodMicros = cpuPeriodMicros;
		return this;
	}

	public long getCpuQuotaMicros() {
		return cpuQuotaMicros;
	}

	public CodeExecutionConfig setCpuQuotaMicros(long cpuQuotaMicros) {
		this.cpuQuotaMicros = cpuQuotaMicros;
		return this;
	}

	public long getPidsLimit() {
		return pidsLimit;
	}

	public CodeExecutionConfig setPidsLimit(long pidsLimit) {
		this.pidsLimit = pidsLimit;
		return this;
	}

	public boolean isDropAllCapabilities() {
		return dropAllCapabilities;
	}

	public CodeExecutionConfig setDropAllCapabilities(boolean dropAllCapabilities) {
		this.dropAllCapabilities = dropAllCapabilities;
		return this;
	}

	public boolean isNoNewPrivileges() {
		return noNewPrivileges;
	}

	public CodeExecutionConfig setNoNewPrivileges(boolean noNewPrivileges) {
		this.noNewPrivileges = noNewPrivileges;
		return this;
	}

	public String getContainerUser() {
		return containerUser;
	}

	public CodeExecutionConfig setContainerUser(String containerUser) {
		this.containerUser = containerUser;
		return this;
	}

}
