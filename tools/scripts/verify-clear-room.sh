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

fail() {
	echo "Clear Room gate failed: $*" >&2
	exit 1
}

[[ -d "${REPO_ROOT}/argi-loop-core" ]] || fail "argi-loop-core is missing"
[[ ! -e "${REPO_ROOT}/argi-graph-core" ]] || fail "legacy argi-graph-core directory still exists"

grep -q '<module>argi-loop-core</module>' "${REPO_ROOT}/pom.xml" \
	|| fail "root reactor does not declare argi-loop-core"
grep -q '<artifactId>argi-loop-core</artifactId>' "${REPO_ROOT}/argi-loop-core/pom.xml" \
	|| fail "Loop Core artifact id is missing"

for module in argi-agent-api argi-agent-core argi-workflow-core argi-runtime-api argi-runtime-core; do
	if rg -n 'argi-loop-core' "${REPO_ROOT}/${module}/pom.xml"; then
		fail "${module} must not depend on Loop Core"
	fi
done

legacy_production_refs="$(rg -l 'argi-graph-core' "${REPO_ROOT}" \
	--glob '!AGENTS.md' \
	--glob '!docs/**' \
	--glob '!argi-loop-core/README.md' \
	--glob '!argi-loop-core/src/test/**' \
	--glob '!benchmark/**' \
	--glob '!example/**' \
	--glob '!tools/scripts/verify-clear-room.sh' \
	--glob '!**/target/**' \
	--glob '!**/.flattened-pom.xml' || true)"
[[ -z "${legacy_production_refs}" ]] || fail "legacy artifact references remain:${legacy_production_refs}"

rg -q 'GRAPH\("graph"\)' "${REPO_ROOT}/argi-runtime-api/src/main/java" \
	|| fail "DefinitionKind.GRAPH wire name is not preserved"
rg -q 'graphId|graphVersion' "${REPO_ROOT}/argi-runtime-core/src/main/java" \
	|| fail "graph definition identifiers are not documented in runtime code"
rg -q 'ARGI_CHAT_MESSAGE|LegacyChatRecordSupport' "${REPO_ROOT}/argi-loop-core/src/main" \
	|| fail "Loop serializer compatibility boundary is missing"

for document in clear-room-baseline.md checkpoint-compatibility.md api-boundaries.md; do
	[[ -f "${REPO_ROOT}/docs/architecture/${document}" ]] \
		|| fail "architecture document is missing: ${document}"
done

if [[ -d "${REPO_ROOT}/benchmark" ]]; then
	if rg -n 'argi-graph-core' "${REPO_ROOT}/benchmark" --glob '*.xml' --glob '!**/target/**'; then
		fail "benchmark submodule still references argi-graph-core"
	fi
fi
if [[ -d "${REPO_ROOT}/example" ]]; then
	if rg -n 'argi-graph-core' "${REPO_ROOT}/example" --glob '*.xml' --glob '!**/target/**'; then
		fail "example submodule still references argi-graph-core"
	fi
fi

echo "Clear Room ownership, artifact, wire-name, and consumer-boundary gates passed"
