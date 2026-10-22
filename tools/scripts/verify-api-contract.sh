#!/usr/bin/env bash
#
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
readonly REPO_ROOT
readonly CONTRACT_MODULES=':argi-agent-framework,:argi-studio,:argi-starter-graph-observation,:argi-starter-builtin-nodes'
readonly FIXTURE_POM="${REPO_ROOT}/tools/compatibility/neutral-api-consumer/pom.xml"
readonly RUNTIME_FIXTURE="${REPO_ROOT}/tools/compatibility/runtime-api-consumer"

# Install current artifacts, then compile a separate consumer with no provider SDK.
if [[ $# -gt 1 ]]; then
    echo "Usage: $0 [--skip-install]" >&2
    exit 2
fi
case "${1:-}" in
    '') "${REPO_ROOT}/mvnw" -B -Dmaven.test.skip=true -pl "${CONTRACT_MODULES}" -am install ;;
    --skip-install) ;; # CI has already installed and tested this checkout's artifacts.
    *) echo "Usage: $0 [--skip-install]" >&2; exit 2 ;;
esac
"${REPO_ROOT}/mvnw" -B -f "${FIXTURE_POM}" clean compile

echo "Neutral public API consumer gate passed"

# A separate JVM verifies that shared contracts do not load the graph or provider stack.
"${REPO_ROOT}/mvnw" -B -f "${RUNTIME_FIXTURE}/pom.xml" clean compile \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -Dmdep.outputFile=target/runtime-classpath.txt
runtime_classpath="$(cat "${RUNTIME_FIXTURE}/target/runtime-classpath.txt")"
if [[ -z "${runtime_classpath}" || "${runtime_classpath}" == *:* ]]; then
    echo "Runtime API must resolve to exactly one jar without transitive dependencies" >&2
    exit 1
fi
java -cp "${RUNTIME_FIXTURE}/target/classes:${runtime_classpath}" \
    io.github.agentic.ai.contract.RuntimeApiConsumer
if [[ "$(jdeps --multi-release 17 --print-module-deps "${runtime_classpath}")" != 'java.base' ]]; then
    echo "Runtime API bytecode must depend only on java.base" >&2
    exit 1
fi
echo "Standalone runtime API consumer and bytecode gates passed"
