#!/usr/bin/env bash
# verify-version-lockstep.sh (rf2-ace2; substrate-paths updated rf2-zha9)
#
# Asserts the lockstep-version contract documented in spec/Conventions.md
# §Packaging conventions: every published artefact picks up its version
# from the single repo-root VERSION file via :clein/build :version,
# and every artefact references core via the in-repo :local/root
# coordinate (which the release workflow rewrites to the matching
# :mvn/version at deploy time).
#
# Per rf2-zha9 the substrate adapters (reagent, uix, helix) live at
# implementation/substrates/<name>/ — one level deeper than the
# per-feature artefacts (schemas, machines, routing, flows, http, ssr,
# epoch) which stay at implementation/<name>/. The script tracks the
# difference: substrate adapters declare :version "../../../VERSION"
# and :local/root "../../core"; per-feature artefacts and core declare
# :version "../../VERSION" and (for non-core) :local/root "../core".
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
# rf2-ace2 / rf2-w05l / rf2-zha9.

set -euo pipefail

# Resolve repo root from script location so the script is callable from
# anywhere (CI working-directory, local dev shell, sub-repo).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Lockstep policy through 1.0 (per rf2-w05l): single root VERSION drives
# every artefact via :clein/build :version. Each non-core artefact
# references core via :local/root so changes to VERSION propagate to
# every artefact's pom at deploy time. Anything else is drift.
#
# Per rf2-zha9 the relative paths differ by tier:
#   - core + per-feature (implementation/<name>/):    :version "../../VERSION"     :local/root "../core"
#   - substrates (implementation/substrates/<name>/): :version "../../../VERSION"  :local/root "../../core"

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

# Map artefact name → on-disk subpath under implementation/. Substrate
# adapters live under substrates/; per-feature artefacts (and core)
# stay flat. Order matches the topological deploy DAG in release.yml so
# a drift report reads top-down.
declare -A ARTEFACT_PATHS=(
  [core]="core"
  [schemas]="schemas"
  [reagent]="substrates/reagent"
  [uix]="substrates/uix"
  [helix]="substrates/helix"
  [machines]="machines"
  [routing]="routing"
  [flows]="flows"
  [http]="http"
  [ssr]="ssr"
  [epoch]="epoch"
)

ARTEFACTS=(core schemas reagent uix helix machines routing flows http ssr epoch)

# core is the lockstep root: it does not depend on any other re-frame-2
# artefact, so the :local/root core-reference check below skips it.
NON_CORE=(schemas reagent uix helix machines routing flows http ssr epoch)

# Substrate adapters are one directory deeper than per-feature artefacts.
SUBSTRATES=(reagent uix helix)

is_substrate() {
  local needle="$1"
  for s in "${SUBSTRATES[@]}"; do
    [[ "$s" == "$needle" ]] && return 0
  done
  return 1
}

errors=0

for artefact in "${ARTEFACTS[@]}"; do
  subpath="${ARTEFACT_PATHS[$artefact]}"
  deps_file="${REPO_ROOT}/implementation/${subpath}/deps.edn"
  rel_label="implementation/${subpath}/deps.edn"

  if [[ ! -f "${deps_file}" ]]; then
    echo "::error file=${rel_label}::deps.edn missing for artefact '${artefact}'"
    errors=$((errors + 1))
    continue
  fi

  # Every artefact's :clein/build :version must point at the repo-root
  # VERSION via the right relative path for its tier. Any literal version
  # string here is the canonical drift signal — it bypasses the
  # single-source-of-truth and would let an artefact ship at a stale
  # version number.
  if is_substrate "${artefact}"; then
    expected_version='"../../../VERSION"'
    if ! grep -qF ":version  ${expected_version}" "${deps_file}" \
       && ! grep -qF ":version ${expected_version}"  "${deps_file}"; then
      echo "::error file=${rel_label}::expected ':version ${expected_version}' in :clein/build (lockstep contract; substrate adapters live one level deeper)"
      errors=$((errors + 1))
    fi
  else
    expected_version='"../../VERSION"'
    if ! grep -qF ":version  ${expected_version}" "${deps_file}" \
       && ! grep -qF ":version ${expected_version}"  "${deps_file}"; then
      echo "::error file=${rel_label}::expected ':version ${expected_version}' in :clein/build (lockstep contract)"
      errors=$((errors + 1))
    fi
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
    echo "::error file=${rel_label}::found literal :mvn/version for a day8/re-frame-2-* artefact in non-comment line (lockstep expects :local/root in committed deps.edn)"
    errors=$((errors + 1))
  fi
done

# Every non-core artefact must reference core via :local/root, with
# the relative path matching its tier. The release workflow swaps this
# for :mvn/version $VERSION at deploy time; the swap only works if the
# in-repo source declares the right :local/root coordinate.
for artefact in "${NON_CORE[@]}"; do
  subpath="${ARTEFACT_PATHS[$artefact]}"
  deps_file="${REPO_ROOT}/implementation/${subpath}/deps.edn"
  rel_label="implementation/${subpath}/deps.edn"
  [[ -f "${deps_file}" ]] || continue
  if is_substrate "${artefact}"; then
    expected_local_root='day8/re-frame-2 {:local/root "../../core"}'
  else
    expected_local_root='day8/re-frame-2 {:local/root "../core"}'
  fi
  if ! grep -qF "${expected_local_root}" "${deps_file}"; then
    echo "::error file=${rel_label}::expected '${expected_local_root}' (lockstep contract; the release workflow rewrites this to :mvn/version at deploy time)"
    errors=$((errors + 1))
  fi
done

if [[ "${errors}" -gt 0 ]]; then
  echo "::error::lockstep version verification FAILED (${errors} drift(s) detected)"
  exit 1
fi

echo "lockstep version verification PASSED — all ${#ARTEFACTS[@]} artefacts pinned to repo-root VERSION ${VERSION}"
exit 0
