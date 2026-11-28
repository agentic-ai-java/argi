#!/usr/bin/env bash
#
# Copyright 2026 the original author or authors.
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

# Cross-repository compatibility gate (issue #229): install the candidate Core into
# an isolated Maven repository, then build the Extensions repository against it with
# the candidate version bound explicitly, proving the candidate artifacts were
# actually resolved instead of silently falling back to the Extensions default.
#
# Rebuilt for #229 after the legacy gate was removed in b51312054 ("remove legacy
# gates"): the legacy version never passed -Dargi.version, so Extensions kept
# resolving its own default (e.g. 2.1.0-RC1) while the candidate was RC2-SNAPSHOT.
#
# Usage:
#   verify-extensions-compatibility.sh /path/to/argi-extensions [--core-dir /path/to/argi]
#   verify-extensions-compatibility.sh --self-test
#
# Environment:
#   MAVEN_CMD                Maven binary used for Extensions when its own mvnw is missing
#   EXTENSIONS_COMPAT_MAVEN_REPO  caller-owned repository path (kept isolated, not deleted)

set -euo pipefail

GROUP_ID="io.github.agentic-ai-java"
EXTENSIONS_DEFAULT_VERSION_NOTE="the Extensions repository default (argi.version)"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core_dir_default="$(cd "${script_dir}/../.." && pwd)"

usage() {
	echo "Usage: $0 [--self-test] /path/to/argi-extensions [--core-dir /path/to/argi]" >&2
	echo "   or: $0 --self-test" >&2
}

# --- pure helpers, covered by --self-test ---------------------------------------------

# Parse the effective project version out of a `mvn help:evaluate -DforceStdout`
# output: the last non-empty line that looks like a version.
candidate_version_from_output() {
	local output="$1"
	local version
	version="$(printf '%s\n' "${output}" | sed '/^[[:space:]]*$/d' | tail -n 1 | tr -d '\r')"
	if [[ -z "${version}" || "${version}" == "null" || "${version}" == *'$'*
			|| ! "${version}" =~ ^[0-9][0-9A-Za-z._-]*$ ]]; then
		return 1
	fi
	printf '%s' "${version}"
}

# Verify that a dependency:list output actually contains candidate coordinates:
# every resolved argi artifact must carry the candidate version. Prints the matched
# coordinates so the run log proves the candidate artifacts were used.
verify_candidate_coordinates() {
	local list_file="$1"
	local candidate_version="$2"
	if [[ ! -f "${list_file}" ]]; then
		echo "VERIFY-FAIL: dependency list file is missing: ${list_file}" >&2
		return 1
	fi
	local matched
	matched="$(grep -E "${GROUP_ID}:argi-.*:${candidate_version//./\\.}(:|$)" "${list_file}" | wc -l || true)"
	if [[ "${matched}" -eq 0 ]]; then
		echo "VERIFY-FAIL: no ${GROUP_ID}:argi-*:${candidate_version} artifact was resolved; the build silently fell back to ${EXTENSIONS_DEFAULT_VERSION_NOTE}" >&2
		return 1
	fi
	echo "COORDINATES-OK: ${matched} artifacts resolved at ${GROUP_ID}:argi-*:${candidate_version}"
	grep -E "${GROUP_ID}:argi-.*:${candidate_version//./\\.}(:|$)" "${list_file}" | sed 's/^/COORDINATE: /'
	return 0
}

# --- candidate version extraction ------------------------------------------------------

extract_candidate_version() {
	local core_dir="$1"
	local output
	if [[ -x "${core_dir}/mvnw" ]]; then
		output="$(cd "${core_dir}" && ./mvnw -B -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null || true)"
	else
		output="$(cd "${core_dir}" && ${MAVEN_CMD:-mvn} -B -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null || true)"
	fi
	candidate_version_from_output "${output}"
}

# --- self-test (issue #229 acceptance: script tests, no Maven needed) ------------------

self_test() {
	local failures=0
	local tmp
	tmp="$(mktemp -d "${TMPDIR:-/tmp}/argi-compat-selftest.XXXXXX")"
	trap 'rm -rf "${tmp}"' EXIT

	# 1. Two repositories with different versions: the candidate version wins over the
	#    Extensions default. A fake help:evaluate output parses to the candidate.
	local parsed
	if parsed="$(candidate_version_from_output $'Some warnings\n2.1.0-RC2-SNAPSHOT\n')" \
		&& [[ "${parsed}" == "2.1.0-RC2-SNAPSHOT" ]]; then
		echo "SELF-TEST 1 OK: candidate version extracted from mixed output (2.1.0-RC2-SNAPSHOT)"
	else
		echo "SELF-TEST 1 FAIL: expected 2.1.0-RC2-SNAPSHOT, got: ${parsed:-<empty>}" >&2
		failures=$((failures + 1))
	fi

	# 2. A placeholder or blank output is rejected instead of being bound literally.
	if parsed="$(candidate_version_from_output '${revision}')" ; then
		echo "SELF-TEST 2 FAIL: placeholder output must be rejected, got: ${parsed}" >&2
		failures=$((failures + 1))
	elif parsed="$(candidate_version_from_output '')"; then
		echo "SELF-TEST 2 FAIL: empty output must be rejected" >&2
		failures=$((failures + 1))
	else
		echo "SELF-TEST 2 OK: placeholder and empty outputs are rejected"
	fi

	# 3. Default version resolvable: a dependency list carrying the candidate version
	#    passes the coordinate verification and prints the evidence.
	printf '%s\n' \
		"${GROUP_ID}:argi-agent-api:jar:2.1.0-RC2-SNAPSHOT:compile" \
		"${GROUP_ID}:argi-bom:jar:2.1.0-RC2-SNAPSHOT:compile" \
		> "${tmp}/list-ok"
	if verify_candidate_coordinates "${tmp}/list-ok" "2.1.0-RC2-SNAPSHOT" > "${tmp}/verify-ok.out" 2>&1; then
		if grep -q "COORDINATES-OK: 2 artifacts" "${tmp}/verify-ok.out" \
			&& grep -q "COORDINATE: ${GROUP_ID}:argi-agent-api:jar:2.1.0-RC2-SNAPSHOT" "${tmp}/verify-ok.out"; then
			echo "SELF-TEST 3 OK: candidate coordinates verified and printed as evidence"
		else
			echo "SELF-TEST 3 FAIL: verification passed without printing the evidence" >&2
			failures=$((failures + 1))
		fi
	else
		echo "SELF-TEST 3 FAIL: a resolvable candidate must pass the coordinate check" >&2
		failures=$((failures + 1))
	fi

	# 4. Default version not resolvable / an explicitly wrong version selected: the
	#    coordinate check must fail instead of silently continuing.
	printf '%s\n' "${GROUP_ID}:argi-agent-api:jar:2.1.0-RC1:compile" > "${tmp}/list-old"
	if verify_candidate_coordinates "${tmp}/list-old" "2.1.0-RC2-SNAPSHOT" > "${tmp}/verify-fallback.out" 2>&1; then
		echo "SELF-TEST 4 FAIL: a non-candidate dependency list must fail the gate" >&2
		failures=$((failures + 1))
	elif grep -q "silently fell back" "${tmp}/verify-fallback.out"; then
		echo "SELF-TEST 4 OK: silent fallback to the Extensions default is rejected"
	else
		echo "SELF-TEST 4 FAIL: rejection must explain the silent fallback" >&2
		failures=$((failures + 1))
	fi

	if [[ "${failures}" -ne 0 ]]; then
		echo "SELF-TEST FAILED: ${failures} scenario(s)" >&2
		return 1
	fi
	trap - EXIT
	rm -rf "${tmp}"
	echo "SELF-TEST PASSED: 4 scenarios"
	return 0
}

# --- main gate --------------------------------------------------------------------------

main() {
	local extensions_dir=""
	local core_dir="${core_dir_default}"
	# Cleanup runs from the EXIT trap after main has returned: these must be global.
	declare -g maven_repo owned_maven_repo tmp_parent
	while [[ $# -gt 0 ]]; do
		case "$1" in
			--core-dir)
				core_dir="$(cd "$2" && pwd)"
				shift 2
				;;
			*)
				if [[ -z "${extensions_dir}" ]]; then
					extensions_dir="$1"
				else
					usage
					exit 2
				fi
				shift
				;;
		esac
	done
	if [[ -z "${extensions_dir}" || ! -d "${extensions_dir}" || ! -f "${extensions_dir}/pom.xml" ]]; then
		usage
		exit 2
	fi
	extensions_dir="$(cd "${extensions_dir}" && pwd)"
	if [[ ! -f "${core_dir}/pom.xml" ]]; then
		echo "Core directory must contain a pom.xml: ${core_dir}" >&2
		exit 2
	fi

	local tmp_parent="${TMPDIR:-/tmp}"
	local maven_repo
	if [[ -n "${EXTENSIONS_COMPAT_MAVEN_REPO:-}" ]]; then
		maven_repo="$(cd "${EXTENSIONS_COMPAT_MAVEN_REPO}" && pwd)"
		local owned_maven_repo="0"
	else
		maven_repo="$(mktemp -d "${tmp_parent%/}/argi-extensions-compat-m2.XXXXXX")"
		local owned_maven_repo="1"
	fi
	readonly maven_repo

	local inside=false
	[[ "${maven_repo}/" == "${core_dir}"/* || "${core_dir}/" == "${maven_repo}"/* ]] && inside=true
	[[ "${maven_repo}/" == "${extensions_dir}"/* || "${extensions_dir}/" == "${maven_repo}"/* ]] && inside=true
	if [[ "${inside}" == "true" ]]; then
		echo "The Maven repository must not live inside a Core or Extensions source tree: ${maven_repo}" >&2
		exit 2
	fi

	cleanup() {
		if [[ "${owned_maven_repo}" == "1" && "${maven_repo}" == "${tmp_parent%/}"/argi-extensions-compat-m2.* && -d "${maven_repo}" ]]; then
			rm -rf "${maven_repo}"
		fi
	}
	trap cleanup EXIT

	echo "Extracting the candidate Core version from ${core_dir}"
	local candidate_version
	if ! candidate_version="$(extract_candidate_version "${core_dir}")"; then
		echo "VERIFY-FAIL: could not extract the candidate Core version from ${core_dir}; check that the build resolves its own revision" >&2
		exit 3
	fi
	echo "Candidate Core version: ${candidate_version} (Extensions default is deliberately overridden)"

	echo "Testing and installing Core ${candidate_version} into ${maven_repo}"
	(
		cd "${core_dir}"
		./mvnw -B -Dmaven.repo.local="${maven_repo}" clean install
	)

	local extensions_maven="${extensions_dir}/mvnw"
	if [[ ! -x "${extensions_maven}" ]]; then
		extensions_maven="${MAVEN_CMD:-mvn}"
	fi

	local list_file="${maven_repo}/candidate-dependency-list.txt"
	echo "Resolving Extensions dependencies bound to ${GROUP_ID}:argi-*:${candidate_version}"
	(
		cd "${extensions_dir}"
		"${extensions_maven}" -B -f pom.xml -Dmaven.repo.local="${maven_repo}" -Dargi.version="${candidate_version}" \
			dependency:list -DoutputFile="${list_file}" -DincludeScope=test
	)
	if ! verify_candidate_coordinates "${list_file}" "${candidate_version}"; then
		exit 3
	fi

	echo "Testing Extensions from ${extensions_dir} against candidate Core ${candidate_version}"
	(
		cd "${extensions_dir}"
		"${extensions_maven}" -B -f pom.xml -Dmaven.repo.local="${maven_repo}" \
			-Dargi.version="${candidate_version}" clean test
	)
	echo "Compatibility gate passed: Extensions ran against candidate Core ${candidate_version}"
}

if [[ "${1:-}" == "--self-test" ]]; then
	self_test
	exit $?
fi

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
	usage
	exit 0
fi

main "$@"
