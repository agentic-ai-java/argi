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

readonly RUNTIME_CORE_FIXTURE="${REPO_ROOT}/tools/compatibility/runtime-core-consumer"
"${REPO_ROOT}/mvnw" -B -f "${RUNTIME_CORE_FIXTURE}/pom.xml" clean compile \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -Dmdep.outputFile=target/runtime-classpath.txt
runtime_core_classpath="$(cat "${RUNTIME_CORE_FIXTURE}/target/runtime-classpath.txt")"
IFS=: read -r -a runtime_jars <<< "${runtime_core_classpath}"
for dependency in "${runtime_jars[@]}"; do
    case "$(basename "${dependency}")" in
        argi-runtime-api-*.jar|argi-runtime-core-*.jar|jackson-annotations-*.jar|jackson-core-*.jar|jackson-databind-*.jar) ;;
        *) echo "Unexpected runtime core dependency: ${dependency}" >&2; exit 1 ;;
    esac
done
for kind in agent workflow graph; do
    java -cp "${RUNTIME_CORE_FIXTURE}/target/classes:${runtime_core_classpath}" \
        io.github.agentic.ai.contract.RuntimeCoreConsumer "${kind}"
done
echo "Standalone agent, workflow and graph runtime contract consumers passed"

runtime_core_jar=""
for dependency in "${runtime_jars[@]}"; do
    case "$(basename "${dependency}")" in
        argi-runtime-core-*.jar) runtime_core_jar="${dependency}" ;;
    esac
done
test -n "${runtime_core_jar}"
jdeps --multi-release 17 -verbose:class --class-path "${runtime_core_classpath}" \
    "${runtime_core_jar}" > "${RUNTIME_CORE_FIXTURE}/target/bytecode-dependencies.txt"
if grep -q "not found" "${RUNTIME_CORE_FIXTURE}/target/bytecode-dependencies.txt"; then
    echo "Runtime Core has unresolved bytecode dependencies" >&2
    exit 1
fi

graph_jar="${REPO_ROOT}/argi-graph-core/target/$(basename "${runtime_core_jar}" | sed s/argi-runtime-core/argi-graph-core/)"
jar tf "${graph_jar}" | grep '.class$' | sort > "${RUNTIME_CORE_FIXTURE}/target/graph-classes.txt"
for dependency in "${runtime_jars[@]}"; do
    case "$(basename "${dependency}")" in
        argi-runtime-*.jar)
            jar tf "${dependency}" | grep '.class$' | sort > "${RUNTIME_CORE_FIXTURE}/target/runtime-classes.txt"
            if [[ -n "$(comm -12 "${RUNTIME_CORE_FIXTURE}/target/graph-classes.txt" "${RUNTIME_CORE_FIXTURE}/target/runtime-classes.txt")" ]]; then
                echo "Graph duplicates extracted runtime classes" >&2
                exit 1
            fi
            ;;
    esac
done
