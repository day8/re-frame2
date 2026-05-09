#!/usr/bin/env bash
# verify-version-lockstep.sh (rf2-ace2)
#
# Asserts the lockstep-version contract documented in spec/Conventions.md
# §Packaging conventions: every published artefact picks up its version
# from the single repo-root VERSION file via :clein/build :version
# "../../VERSION", and every artefact references core via the in-repo
# :local/root coordinate (which the release workflow rewrites to the
# matching :mvn/version at deploy time).
#
# This script is the single source of truth for the lockstep contract;
# both .github/workflows/test.yml (PR-time drift detection) and
# .github/workflows/release.yml (pre-deploy gate) invoke it.
#
# Exits 0 on success; non-zero on the first detected drift, with a
# GitHub-Actions-friendly ::error:: line on stderr-or-stdout. Running
# locally from the repo root prints the same messages and is the
# fastest way to debug a CI lockstep failure.
#
# Usage:
#   ./.github/scripts/verify-version-lockstep.sh
#
# rf2-ace2 / rf2-w05l.

set -euo pipefail

# Resolve repo root from script location so the script is callable from
# anywhere (CI working-directory, local dev shell, sub-repo).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Lockstep policy through 1.0 (per rf2-w05l): single root VERSION drives
# every artefact via :clein/build :version "../../VERSION". Each non-core
# artefact references core via {:local/root "../core"} so changes to
# VERSION propagate to every artefact's pom at deploy time. Anything else
# is drift.

VERSION_FILE="${REPO_ROOT}/VERSION"
if [[ ! -f "${VERSION_FILE}" ]]; then
  echo "::error file=VERSION::repo-root VERSION file is missing"
  exit 2
fi
VERSION="$(tr -d '[:space:]' < "${VERSION_FILE}")"
if [[ -z "${VERSION}" ]]; then
  echo "::error file=VERSION::repo-root VERSION file is empty"
  exit 2
fi
echo "lockstep VERSION = ${VERSION}"

# The artefact set the lockstep contract covers. Order matches the
# topological deploy DAG in release.yml so a drift report reads
# top-down. Add per-feature splits to this list as they land (rf2-uo7v
# ssr, rf2-lt4e epoch, ...).
ARTEFACTS=(core schemas reagent machines routing flows http)

# core is the lockstep root: it does not depend on any other re-frame-2
# artefact, so the :local/root core-reference check below skips it.
NON_CORE=(schemas reagent machines routing flows http)

errors=0

for artefact in "${ARTEFACTS[@]}"; do
  deps_file="${REPO_ROOT}/implementation/${artefact}/deps.edn"
  if [[ ! -f "${deps_file}" ]]; then
    echo "::error file=implementation/${artefact}/deps.edn::deps.edn missing for artefact '${artefact}'"
    errors=$((errors + 1))
    continue
  fi

  # Every artefact's :clein/build :version must point at "../../VERSION".
  # Any literal version string here is the canonical drift signal — it
  # bypasses the single-source-of-truth and would let an artefact ship
  # at a stale version number.
  if ! grep -qF ':version  "../../VERSION"' "${deps_file}" \
     && ! grep -qF ':version "../../VERSION"' "${deps_file}"; then
    echo "::error file=implementation/${artefact}/deps.edn::expected ':version \"../../VERSION\"' in :clein/build (lockstep contract)"
    errors=$((errors + 1))
  fi

  # No artefact may carry a literal :mvn/version coordinate for any of
  # the day8/re-frame-2-* artefacts in its committed deps.edn. The
  # release workflow rewrites :local/root → :mvn/version at deploy
  # time on a throwaway checkout; a literal in the committed file means
  # someone hand-edited it and the lockstep is broken.
  #
  # Strip comments first (deps.edn line comments start with `;;`) — the
  # consumer-facing usage examples in artefact deps.edn headers
  # legitimately show `day8/re-frame-2 {:mvn/version "..."}` snippets.
  if sed 's/;;.*$//' "${deps_file}" | grep -qE 'day8/re-frame-2[^[:space:]]*[[:space:]]+\{:mvn/version'; then
    echo "::error file=implementation/${artefact}/deps.edn::found literal :mvn/version for a day8/re-frame-2-* artefact in non-comment line (lockstep expects :local/root \"../<artefact>\" in committed deps.edn)"
    errors=$((errors + 1))
  fi
done

# Every non-core artefact must reference core via :local/root "../core".
# The release workflow swaps this for :mvn/version $VERSION at deploy
# time; the swap only works if the in-repo source declares :local/root.
for artefact in "${NON_CORE[@]}"; do
  deps_file="${REPO_ROOT}/implementation/${artefact}/deps.edn"
  [[ -f "${deps_file}" ]] || continue
  if ! grep -qF 'day8/re-frame-2 {:local/root "../core"}' "${deps_file}"; then
    echo "::error file=implementation/${artefact}/deps.edn::expected 'day8/re-frame-2 {:local/root \"../core\"}' (lockstep contract; the release workflow rewrites this to :mvn/version at deploy time)"
    errors=$((errors + 1))
  fi
done

if [[ "${errors}" -gt 0 ]]; then
  echo "::error::lockstep version verification FAILED (${errors} drift(s) detected)"
  exit 1
fi

echo "lockstep version verification PASSED — all ${#ARTEFACTS[@]} artefacts pinned to repo-root VERSION ${VERSION}"
exit 0
