#!/usr/bin/env bash
# verify-version-lockstep.sh (rf2-ace2; substrate-paths updated rf2-zha9;
# adapters/ rename rf2-0imy; tools/ coverage rf2-lwtke)
#
# Asserts the lockstep-version contract documented in spec/Conventions.md
# §Packaging conventions: every published artefact picks up its version
# from the single repo-root VERSION file via :clein/build :version,
# and every artefact references its in-repo dependencies via :local/root
# coordinates (which the release workflow rewrites to the matching
# :mvn/version at deploy time).
#
# Per rf2-zha9 the adapters (reagent, uix, helix) live at
# implementation/adapters/<name>/ (renamed from substrates/ per
# rf2-0imy) — one level deeper than the per-feature artefacts
# (schemas, machines, routing, flows, http, ssr, epoch) which stay at
# implementation/<name>/. The script tracks the difference: adapters
# declare :version "../../../VERSION" and :local/root "../../core";
# per-feature artefacts and core declare :version "../../VERSION" and
# (for non-core) :local/root "../core".
#
# Per rf2-lwtke the deployable jars under tools/* also participate in
# lockstep — every Clojars-publishable tool (causa, story, story-mcp)
# carries :clein/build :version "../../VERSION" and must not hand-edit
# a literal :mvn/version for any day8/re-frame2-* artefact.
# tools/re-frame2-pair-mcp/ ships as a Node binary on npm and carries no
# :clein/build alias, so it is intentionally excluded. tools/template/
# is similarly excluded as of rf2-40vmd (rf2-dolpf §2.5): it ships via
# git-coord (no Clojars publish, no :clein/build alias) and the version
# literals consumed by the emitted app are guarded by the in-template
# `version_lockstep_test.clj` suite rather than by this script.
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
# rf2-ace2 / rf2-w05l / rf2-zha9 / rf2-lwtke.

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
#   - core + per-feature (implementation/<name>/):  :version "../../VERSION"     :local/root "../core"
#   - adapters (implementation/adapters/<name>/):   :version "../../../VERSION"  :local/root "../../core"

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

# Map artefact name → on-disk subpath under implementation/. Adapters
# live under adapters/; per-feature artefacts (and core) stay flat.
# Order matches the topological deploy DAG in release.yml so a drift
# report reads top-down.
declare -A ARTEFACT_PATHS=(
  [core]="core"
  [schemas]="schemas"
  [reagent]="adapters/reagent"
  [reagent-slim]="adapters/reagent-slim"
  [uix]="adapters/uix"
  [helix]="adapters/helix"
  [machines]="machines"
  [routing]="routing"
  [flows]="flows"
  [http]="http"
  [ssr]="ssr"
  [epoch]="epoch"
)

ARTEFACTS=(core schemas reagent reagent-slim uix helix machines routing flows http ssr epoch)

# core is the lockstep root: it does not depend on any other re-frame2
# artefact, so the :local/root core-reference check below skips it.
NON_CORE=(schemas reagent reagent-slim uix helix machines routing flows http ssr epoch)

# Adapters (substrate adapters) are one directory deeper than per-feature
# artefacts.
ADAPTERS=(reagent reagent-slim uix helix)

is_adapter() {
  local needle="$1"
  for s in "${ADAPTERS[@]}"; do
    [[ "$s" == "$needle" ]] && return 0
  done
  return 1
}

errors=0

# Asserts (a) :clein/build :version points at the repo-root VERSION via
# the given relative path, and (b) the deps.edn carries no literal
# :mvn/version coordinate for any day8/re-frame2-* artefact in a
# non-comment line. Shared by implementation/* and tools/* artefacts.
#
# Args: $1 = absolute deps.edn path, $2 = repo-relative label for error
# lines, $3 = expected :version literal (e.g. '"../../VERSION"').
check_version_and_no_mvn_literal() {
  local deps_file="$1"
  local rel_label="$2"
  local expected_version="$3"

  if ! grep -qF ":version  ${expected_version}" "${deps_file}" \
     && ! grep -qF ":version ${expected_version}"  "${deps_file}"; then
    echo "::error file=${rel_label}::expected ':version ${expected_version}' in :clein/build (lockstep contract)"
    errors=$((errors + 1))
  fi

  # No artefact may carry a literal :mvn/version coordinate for any of
  # the day8/re-frame2-* artefacts in its committed deps.edn. The
  # release workflow rewrites :local/root → :mvn/version at deploy
  # time on a throwaway checkout; a literal in the committed file means
  # someone hand-edited it and the lockstep is broken.
  #
  # Strip comments first (deps.edn line comments start with `;;`) — the
  # consumer-facing usage examples in artefact deps.edn headers
  # legitimately show `day8/re-frame2 {:mvn/version "..."}` snippets.
  if sed 's/;;.*$//' "${deps_file}" | grep -qE 'day8/re-frame2[^[:space:]]*[[:space:]]+\{:mvn/version'; then
    echo "::error file=${rel_label}::found literal :mvn/version for a day8/re-frame2-* artefact in non-comment line (lockstep expects :local/root in committed deps.edn)"
    errors=$((errors + 1))
  fi
}

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
  if is_adapter "${artefact}"; then
    # Adapters live one level deeper: implementation/adapters/<name>/.
    check_version_and_no_mvn_literal "${deps_file}" "${rel_label}" '"../../../VERSION"'
  else
    check_version_and_no_mvn_literal "${deps_file}" "${rel_label}" '"../../VERSION"'
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
  if is_adapter "${artefact}"; then
    expected_local_root='day8/re-frame2 {:local/root "../../core"}'
  else
    expected_local_root='day8/re-frame2 {:local/root "../core"}'
  fi
  if ! grep -qF "${expected_local_root}" "${deps_file}"; then
    echo "::error file=${rel_label}::expected '${expected_local_root}' (lockstep contract; the release workflow rewrites this to :mvn/version at deploy time)"
    errors=$((errors + 1))
  fi
done

# Tools/* deployable jars (rf2-lwtke). Each tools/<name>/deps.edn that
# carries a :clein/build alias publishes to Clojars at the same lockstep
# version as the framework artefacts above — every consumer that pins
# `day8/re-frame2 {:mvn/version X}` should be able to pin
# `day8/re-frame2-causa {:mvn/version X}` and get a coherent set. Without
# this loop a hand-edit to tools/<name>/deps.edn would not surface as a
# drift report and could cut a broken release.
#
# Each entry lists the tool's on-disk subpath under tools/ and the
# expected :local/root references that the release workflow's
# :local/root → :mvn/version rewrite consumes. Tools live two levels
# down from the repo root (tools/<name>/), same depth as
# implementation/<name>/, so :version "../../VERSION" is correct.
#
# tools/re-frame2-pair-mcp/ is deliberately excluded: it ships as a Node binary
# on npm (@day8/re-frame2-pair-mcp) and carries no :clein/build alias —
# there is no Clojars publish path for it to drift on. Per its
# deps.edn header it has no :local/root dep on implementation/ either.
#
# tools/template/ is similarly excluded as of rf2-40vmd (rf2-dolpf §2.5):
# it ships via git-coord rather than Clojars and no longer carries a
# :clein/build alias. The template's pin literals (rf2-version,
# shadow-version, react-version) are guarded by an in-template lockstep
# test (`test/day8/re_frame2_template/version_lockstep_test.clj`) which
# reads the same sources of truth this script does (repo-root VERSION,
# implementation/package.json).
declare -A TOOLS_PATHS=(
  [causa]="causa"
  [story]="story"
  [story-mcp]="story-mcp"
)

# Newline-separated `tool|"day8/re-frame2-x {:local/root \"…\"}"` pairs
# expressing every re-frame2-* :local/root coordinate the release workflow
# would need to rewrite to :mvn/version at deploy time. A bash
# associative array can't carry multi-valued entries cleanly, so we use
# a single multi-line string and split on `|`.
TOOLS_LOCAL_ROOTS=$(cat <<'EOF'
causa|day8/re-frame2 {:local/root "../../implementation/core"}
story|day8/re-frame2 {:local/root "../../implementation/core"}
story|day8/re-frame2-reagent {:local/root "../../implementation/adapters/reagent"}
story|day8/re-frame2-machines {:local/root "../../implementation/machines"}
story-mcp|day8/re-frame2-story {:local/root "../story"}
EOF
)

TOOLS=(causa story story-mcp)

for tool in "${TOOLS[@]}"; do
  subpath="${TOOLS_PATHS[$tool]}"
  deps_file="${REPO_ROOT}/tools/${subpath}/deps.edn"
  rel_label="tools/${subpath}/deps.edn"

  if [[ ! -f "${deps_file}" ]]; then
    echo "::error file=${rel_label}::deps.edn missing for tool '${tool}'"
    errors=$((errors + 1))
    continue
  fi

  # Tools sit at tools/<name>/, same depth from VERSION as the
  # per-feature artefacts under implementation/<name>/.
  check_version_and_no_mvn_literal "${deps_file}" "${rel_label}" '"../../VERSION"'

  # Belt-and-braces: assert each expected :local/root coordinate. The
  # release workflow's rewrite step (release.yml) keys off the
  # `:local/root "<path>"` substring; the artefact-key pairing here is
  # an extra signal that a hand-edit hasn't, say, swapped the keys.
  #
  # tools/story/deps.edn pads its dep map with column-aligned whitespace
  # (`day8/re-frame2          {:local/root …}`) so we collapse all
  # runs of whitespace to a single space before matching.
  normalised="$(tr -s '[:space:]' ' ' < "${deps_file}")"
  while IFS='|' read -r entry_tool entry_local_root; do
    [[ -z "${entry_tool}" ]] && continue
    [[ "${entry_tool}" == "${tool}" ]] || continue
    if ! grep -qF "${entry_local_root}" <<< "${normalised}"; then
      echo "::error file=${rel_label}::expected '${entry_local_root}' (lockstep contract; the release workflow rewrites this to :mvn/version at deploy time)"
      errors=$((errors + 1))
    fi
  done <<< "${TOOLS_LOCAL_ROOTS}"
done

if [[ "${errors}" -gt 0 ]]; then
  echo "::error::lockstep version verification FAILED (${errors} drift(s) detected)"
  exit 1
fi

total_count=$((${#ARTEFACTS[@]} + ${#TOOLS[@]}))
echo "lockstep version verification PASSED — all ${total_count} artefacts (${#ARTEFACTS[@]} implementation/ + ${#TOOLS[@]} tools/) pinned to repo-root VERSION ${VERSION}"
exit 0
